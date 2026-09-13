package dev.shepherd.domain.model

import dev.shepherd.domain.auth.Role
import kotlinx.serialization.Serializable

/**
 * How people sign in. API keys — clients, personal tokens and `MSH_ADMIN_TOKEN` — are accepted
 * regardless; this section is about humans: passwords kept by the manager, LDAP or Active
 * Directory, and OIDC providers.
 */
@Serializable
data class AuthConfig(
    /**
     * The manager's address as people reach it, e.g. `https://shepherd.example.com`. OIDC redirect
     * URIs are built from it, and an https address makes the session cookie secure-only.
     */
    val publicUrl: String? = null,
    val local: LocalAuthConfig = LocalAuthConfig(),
    val sessions: WebSessionConfig = WebSessionConfig(),
    val ldap: LdapConfig? = null,
    val oidc: List<OidcProviderConfig> = emptyList()
) {
    init {
        publicUrl?.let { url ->
            require(url.startsWith("https://") || url.startsWith("http://")) { "auth.publicUrl must be an http:// or https:// URL" }
        }
        require(oidc.map { provider -> provider.id }.toSet().size == oidc.size) { "auth.oidc provider ids must be unique" }
        require(oidc.isEmpty() || publicUrl != null) { "auth.publicUrl is required for OIDC sign-in: redirect URIs are built from it" }
    }

    /** [publicUrl] without a trailing slash. */
    val baseUrl: String? get() = publicUrl?.trimEnd('/')

    /** Secure-only cookies when configured so, or when the public URL is https. */
    val secureCookies: Boolean get() = sessions.secureCookie ?: (publicUrl?.startsWith("https://") == true)

    fun oidcProvider(id: String): OidcProviderConfig? = oidc.firstOrNull { provider -> provider.id == id }
}

@Serializable
data class LocalAuthConfig(
    /** Usernames and passwords kept by the manager itself. */
    val enabled: Boolean = true,
    val minPasswordLength: Int = 10
) {
    init {
        require(minPasswordLength in 8..128) { "auth.local.minPasswordLength must be between 8 and 128" }
    }
}

@Serializable
data class WebSessionConfig(
    /** A browser session ends after this long without a request... */
    val idleTimeoutMinutes: Long = 480,
    /** ...and this long after sign-in in any case. */
    val maxLifetimeHours: Long = 168,
    /** Send the session cookie over HTTPS only. Unset follows the scheme of `auth.publicUrl`. */
    val secureCookie: Boolean? = null,
    /** Failed sign-ins in a row before a username is locked out for [lockoutMinutes]. */
    val maxFailedAttempts: Int = 5,
    val lockoutMinutes: Long = 15
) {
    init {
        require(idleTimeoutMinutes > 0 && maxLifetimeHours > 0) { "auth.sessions timeouts must be positive" }
        require(maxFailedAttempts > 0 && lockoutMinutes > 0) { "auth.sessions.maxFailedAttempts and lockoutMinutes must be positive" }
    }
}

@Serializable
data class LdapConfig(
    /** `ldaps://host:636`, or `ldap://host:389` together with [startTls]. */
    val url: String,
    val startTls: Boolean = false,
    /** The account that searches for users; unset searches anonymously. */
    val bindDn: String? = null,
    val bindPassword: String = "",
    /** Environment variable holding the bind password, so it need not be written here. */
    val bindPasswordEnv: String? = null,
    val userSearchBase: String,
    /** `{0}` is the username as typed. Active Directory: `(&(objectClass=user)(sAMAccountName={0}))`. */
    val userSearchFilter: String = "(uid={0})",
    val usernameAttribute: String = "uid",
    val displayNameAttribute: String = "cn",
    val emailAttribute: String = "mail",
    /** Attribute of the user entry that lists the DNs of its groups, e.g. `memberOf`. */
    val groupAttribute: String? = "memberOf",
    /** Where to search for groups naming the user as a member; unset relies on [groupAttribute]. */
    val groupSearchBase: String? = null,
    /** `{0}` is the user's DN, `{1}` the username. */
    val groupSearchFilter: String = "(member={0})",
    val groupNameAttribute: String = "cn",
    /** Group name → `admin`, `user` or `viewer`. The highest role of any matching group wins. */
    val roleMapping: Map<String, String> = emptyMap(),
    /** Role for people in none of the mapped groups; null turns them away. */
    val defaultRole: String? = "viewer",
    val connectTimeoutSeconds: Int = 5,
    val readTimeoutSeconds: Int = 10
) {
    init {
        require(url.startsWith("ldaps://") || url.startsWith("ldap://")) { "auth.ldap.url must start with ldaps:// or ldap://" }
        require("{0}" in userSearchFilter) { "auth.ldap.userSearchFilter must contain {0}, where the username goes" }
        require(connectTimeoutSeconds > 0 && readTimeoutSeconds > 0) { "auth.ldap timeouts must be positive" }
        requirePeopleRoles("auth.ldap", roleMapping, defaultRole)
    }

    /** The bind password, from [bindPasswordEnv] when that is set. */
    fun resolvedBindPassword(environment: Map<String, String> = System.getenv()): String =
        bindPasswordEnv?.let { name -> environment[name] } ?: bindPassword
}

@Serializable
data class OidcProviderConfig(
    /** Letters, digits, '-' and '_'. Part of the redirect URI: `<publicUrl>/auth/oidc/<id>/callback`. */
    val id: String,
    /** The sign-in button's label. */
    val displayName: String? = null,
    /** The issuer URL; its `/.well-known/openid-configuration` is read at sign-in. */
    val issuer: String,
    val clientId: String,
    val clientSecret: String = "",
    /** Environment variable holding the client secret, so it need not be written here. */
    val clientSecretEnv: String? = null,
    val scopes: List<String> = listOf("openid", "profile", "email"),
    /** Claim holding the username; `email`, then `sub`, when it is missing. */
    val usernameClaim: String = "preferred_username",
    /** Claim holding group names, read from the ID token or else from the userinfo endpoint. */
    val groupsClaim: String = "groups",
    /** Group name → `admin`, `user` or `viewer`. The highest role of any matching group wins. */
    val roleMapping: Map<String, String> = emptyMap(),
    /** Role for people in none of the mapped groups; null turns them away. */
    val defaultRole: String? = "viewer"
) {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,64}$").matches(id)) { "auth.oidc id '$id' may only contain letters, digits, '-' and '_'" }
        require(issuer.startsWith("https://") || issuer.startsWith("http://")) { "auth.oidc[$id].issuer must be an http(s) URL" }
        require(clientId.isNotBlank()) { "auth.oidc[$id].clientId is required" }
        require("openid" in scopes) { "auth.oidc[$id].scopes must include openid" }
        requirePeopleRoles("auth.oidc[$id]", roleMapping, defaultRole)
    }

    val label: String get() = displayName ?: id

    /** The client secret, from [clientSecretEnv] when that is set. */
    fun resolvedClientSecret(environment: Map<String, String> = System.getenv()): String =
        clientSecretEnv?.let { name -> environment[name] } ?: clientSecret
}

/** Directory groups may make people admins, users or viewers; the provider role is for adapters. */
private fun requirePeopleRoles(section: String, mapping: Map<String, String>, defaultRole: String?) {
    (mapping.values + listOfNotNull(defaultRole)).forEach { value ->
        require(Role.parse(value) != Role.PROVIDER) { "$section: the provider role is for adapters, not for people" }
    }
}
