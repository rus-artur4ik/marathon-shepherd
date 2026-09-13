package dev.shepherd.infra.auth

import dev.shepherd.domain.model.LdapConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.Hashtable
import javax.naming.AuthenticationException
import javax.naming.Context
import javax.naming.NamingException
import javax.naming.SizeLimitExceededException
import javax.naming.directory.Attributes
import javax.naming.directory.SearchControls
import javax.naming.directory.SearchResult
import javax.naming.ldap.InitialLdapContext
import javax.naming.ldap.LdapContext
import javax.naming.ldap.LdapName
import javax.naming.ldap.StartTlsRequest
import javax.naming.ldap.StartTlsResponse

/**
 * Sign-in against LDAP or Active Directory: find the person with the service account, then bind as
 * them with the password they typed. Filters take the username as a parameter, never by string
 * concatenation, so a username cannot change what the search matches.
 */
class LdapSignIn(private val environment: Map<String, String> = System.getenv()) {
    private val logger = LoggerFactory.getLogger(LdapSignIn::class.java)

    /**
     * The person behind [username] when the directory accepts [password]; null for an unknown user or
     * a wrong password.
     *
     * @throws DirectoryUnavailableException when the directory cannot be asked at all.
     */
    suspend fun authenticate(config: LdapConfig, username: String, password: String): ExternalIdentity? = withContext(Dispatchers.IO) {
        // Most servers treat a bind with an empty password as an anonymous bind, and accept it.
        if (username.isBlank() || password.isEmpty()) {
            return@withContext null
        }
        val search: DirectoryConnection = try {
            connect(config, config.bindDn, config.bindDn?.let { config.resolvedBindPassword(environment) })
        } catch (error: NamingException) {
            throw DirectoryUnavailableException(
                "Cannot bind to ${config.url} with the service account: ${error.explanation ?: error}",
                error
            )
        }
        try {
            val entry: SearchResult = findUser(search.context, config, username) ?: return@withContext null
            val userDn: String = entry.nameInNamespace
            try {
                connect(config, userDn, password).close()
            } catch (_: AuthenticationException) {
                return@withContext null
            }
            val attributes: Attributes = entry.attributes
            ExternalIdentity(
                source = UserSource.LDAP,
                provider = PROVIDER,
                externalId = userDn,
                username = attributes.firstValue(config.usernameAttribute) ?: username,
                displayName = attributes.firstValue(config.displayNameAttribute),
                email = attributes.firstValue(config.emailAttribute),
                groups = groupsOf(search.context, config, userDn, username, attributes),
                roleMapping = config.roleMapping,
                defaultRole = config.defaultRole
            )
        } catch (error: NamingException) {
            throw DirectoryUnavailableException("The directory at ${config.url} failed: ${error.explanation ?: error}", error)
        } finally {
            search.close()
        }
    }

    private fun findUser(context: LdapContext, config: LdapConfig, username: String): SearchResult? {
        val controls = SearchControls().apply {
            searchScope = SearchControls.SUBTREE_SCOPE
            countLimit = 2
            timeLimit = config.readTimeoutSeconds * MILLIS
            returningAttributes = listOfNotNull(
                config.usernameAttribute,
                config.displayNameAttribute,
                config.emailAttribute,
                config.groupAttribute
            ).toTypedArray()
        }
        val matches = mutableListOf<SearchResult>()
        try {
            val results = context.search(config.userSearchBase, config.userSearchFilter, arrayOf<Any>(username.trim()), controls)
            while (results.hasMore()) {
                matches += results.next()
            }
        } catch (_: SizeLimitExceededException) {
            logger.warn("auth.ldap.userSearchFilter matches more than one entry for one username; nobody is signed in with it")
            return null
        }
        if (matches.size > 1) {
            logger.warn("auth.ldap.userSearchFilter matches {} entries for one username; nobody is signed in with it", matches.size)
            return null
        }
        return matches.singleOrNull()
    }

    private fun groupsOf(context: LdapContext, config: LdapConfig, userDn: String, username: String, attributes: Attributes): Set<String> {
        val groups = linkedSetOf<String>()
        config.groupAttribute?.let { attribute ->
            attributes.allValues(attribute).forEach { groupDn ->
                groups += groupDn
                commonName(groupDn)?.let(groups::add)
            }
        }
        config.groupSearchBase?.let { base ->
            val controls = SearchControls().apply {
                searchScope = SearchControls.SUBTREE_SCOPE
                countLimit = MAX_GROUPS.toLong()
                timeLimit = config.readTimeoutSeconds * MILLIS
                returningAttributes = arrayOf(config.groupNameAttribute)
            }
            try {
                val results = context.search(base, config.groupSearchFilter, arrayOf<Any>(userDn, username), controls)
                while (results.hasMore()) {
                    val group: SearchResult = results.next()
                    groups += group.nameInNamespace
                    group.attributes.firstValue(config.groupNameAttribute)?.let(groups::add)
                }
            } catch (_: SizeLimitExceededException) {
                logger.warn("More than {} groups matched auth.ldap.groupSearchFilter; the rest are ignored", MAX_GROUPS)
            }
        }
        return groups
    }

    private fun connect(config: LdapConfig, principal: String?, credentials: String?): DirectoryConnection {
        val environment = Hashtable<String, Any>().apply {
            put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
            put(Context.PROVIDER_URL, config.url)
            put(Context.REFERRAL, "ignore")
            put("com.sun.jndi.ldap.connect.timeout", (config.connectTimeoutSeconds * MILLIS).toString())
            put("com.sun.jndi.ldap.read.timeout", (config.readTimeoutSeconds * MILLIS).toString())
        }
        if (!config.startTls) {
            if (principal != null) {
                environment[Context.SECURITY_AUTHENTICATION] = "simple"
                environment[Context.SECURITY_PRINCIPAL] = principal
                environment[Context.SECURITY_CREDENTIALS] = credentials.orEmpty()
            } else {
                environment[Context.SECURITY_AUTHENTICATION] = "none"
            }
            return DirectoryConnection(InitialLdapContext(environment, null), tls = null)
        }
        val context = InitialLdapContext(environment, null)
        try {
            val tls = context.extendedOperation(StartTlsRequest()) as StartTlsResponse
            tls.negotiate()
            if (principal != null) {
                context.addToEnvironment(Context.SECURITY_AUTHENTICATION, "simple")
                context.addToEnvironment(Context.SECURITY_PRINCIPAL, principal)
                context.addToEnvironment(Context.SECURITY_CREDENTIALS, credentials.orEmpty())
                // The bind happens on the next operation, over the TLS channel.
                context.getAttributes("", arrayOf("namingContexts"))
            }
            return DirectoryConnection(context, tls)
        } catch (error: Exception) {
            runCatching { context.close() }
            throw error
        }
    }

    private class DirectoryConnection(val context: LdapContext, private val tls: StartTlsResponse?) : AutoCloseable {
        override fun close() {
            runCatching { tls?.close() }
            runCatching { context.close() }
        }
    }

    private fun Attributes.firstValue(name: String): String? = get(name)?.get()?.toString()?.takeIf { value -> value.isNotBlank() }

    private fun Attributes.allValues(name: String): List<String> {
        val attribute = get(name) ?: return emptyList()
        return (0 until attribute.size()).mapNotNull { index -> attribute.get(index)?.toString() }
    }

    private fun commonName(dn: String): String? = runCatching { LdapName(dn).rdns.lastOrNull()?.value?.toString() }.getOrNull()

    companion object {
        const val PROVIDER: String = "ldap"
        private const val MILLIS = 1000
        private const val MAX_GROUPS = 500
    }
}
