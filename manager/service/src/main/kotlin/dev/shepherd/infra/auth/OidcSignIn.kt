package dev.shepherd.infra.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.shepherd.domain.model.AuthConfig
import dev.shepherd.domain.model.OidcProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Sign-in through OIDC providers with the authorization code flow and PKCE. The manager is a
 * confidential client: it exchanges the code itself and verifies the ID token's signature, issuer,
 * audience, lifetime and nonce before it trusts anything in it.
 *
 * Everything goes through [httpClient], including the provider's keys, so timeouts and proxies
 * apply the same way to every call.
 */
class OidcSignIn(
    private val httpClient: HttpClient,
    private val authConfig: () -> AuthConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val environment: Map<String, String> = System.getenv()
) {
    private data class Metadata(
        val issuer: String,
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val jwksUri: String,
        val userinfoEndpoint: String?,
        val tokenAuthMethods: List<String>,
        val fetchedAt: Instant
    )

    private data class PendingSignIn(
        val providerId: String,
        val nonce: String,
        val codeVerifier: String,
        val returnTo: String,
        val startedAt: Instant
    )

    private val logger = LoggerFactory.getLogger(OidcSignIn::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val metadataByIssuer = ConcurrentHashMap<String, Metadata>()
    private val keysByUri = ConcurrentHashMap<String, JWKSet>()
    private val pending = ConcurrentHashMap<String, PendingSignIn>()

    /** Where the provider sends the browser back to; register exactly this with the provider. */
    fun redirectUri(provider: OidcProviderConfig): String = "${checkNotNull(authConfig().baseUrl)}/auth/oidc/${provider.id}/callback"

    /** The provider's authorization URL for a new sign-in that ends at [returnTo] (a path on this manager). */
    suspend fun start(providerId: String, returnTo: String?): String {
        val provider: OidcProviderConfig = provider(providerId)
        val metadata: Metadata = metadata(provider)
        forgetExpired()
        val state: String = RandomTokens.urlSafe(STATE_BYTES)
        val verifier: String = RandomTokens.urlSafe(VERIFIER_BYTES)
        val signIn = PendingSignIn(provider.id, RandomTokens.urlSafe(STATE_BYTES), verifier, safeReturnTo(returnTo), clock.instant())
        pending[state] = signIn
        return URLBuilder(metadata.authorizationEndpoint).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", provider.clientId)
            parameters.append("redirect_uri", redirectUri(provider))
            parameters.append("scope", provider.scopes.joinToString(" "))
            parameters.append("state", state)
            parameters.append("nonce", signIn.nonce)
            parameters.append("code_challenge", base64Url(sha256(verifier)))
            parameters.append("code_challenge_method", "S256")
        }.buildString()
    }

    /**
     * Finishes a sign-in the provider redirected back with, and returns who signed in and where to send them.
     *
     * @throws SignInFailure for anything a person can do something about: an expired attempt, a refusal, an invalid token.
     */
    suspend fun complete(providerId: String, code: String?, state: String?, error: String?): Pair<ExternalIdentity, String> {
        val signIn: PendingSignIn = state?.let { value -> pending.remove(value) }
            ?.takeIf { attempt -> attempt.providerId == providerId && !isExpired(attempt) }
            ?: throw SignInFailure("This sign-in attempt expired or was already used; start again")
        val provider: OidcProviderConfig = provider(providerId)
        if (error != null) {
            throw SignInFailure("${provider.label} did not sign you in: $error")
        }
        if (code.isNullOrBlank()) {
            throw SignInFailure("${provider.label} returned no authorization code")
        }
        val metadata: Metadata = metadata(provider)
        val tokens: JsonObject = exchange(provider, metadata, code, signIn.codeVerifier)
        val idToken: String = tokens["id_token"]?.jsonPrimitive?.contentOrNull
            ?: throw SignInFailure("${provider.label} returned no ID token")
        val claims: JWTClaimsSet = verify(provider, metadata, idToken, signIn.nonce)

        var groups: Set<String>? = claims.stringSet(provider.groupsClaim)
        var username: String? = claims.string(provider.usernameClaim) ?: claims.string("email")
        var email: String? = claims.string("email")
        var name: String? = claims.string("name")
        val accessToken: String? = tokens["access_token"]?.jsonPrimitive?.contentOrNull
        if ((groups == null || username == null) && metadata.userinfoEndpoint != null && accessToken != null) {
            userinfo(provider, metadata.userinfoEndpoint, accessToken, claims.subject)?.let { info ->
                groups = groups ?: info.stringSet(provider.groupsClaim)
                username = username ?: info.string(provider.usernameClaim) ?: info.string("email")
                email = email ?: info.string("email")
                name = name ?: info.string("name")
            }
        }
        val identity = ExternalIdentity(
            source = UserSource.OIDC,
            provider = provider.id,
            externalId = claims.subject,
            username = username ?: claims.subject,
            displayName = name,
            email = email,
            groups = groups.orEmpty(),
            roleMapping = provider.roleMapping,
            defaultRole = provider.defaultRole
        )
        return identity to signIn.returnTo
    }

    private suspend fun exchange(provider: OidcProviderConfig, metadata: Metadata, code: String, verifier: String): JsonObject {
        val secret: String = provider.resolvedClientSecret(environment)
        // client_secret_basic is the default when a provider does not say what it supports.
        val basicSupported: Boolean = metadata.tokenAuthMethods.isEmpty() || "client_secret_basic" in metadata.tokenAuthMethods
        val basic: Boolean = secret.isNotEmpty() && basicSupported
        val response: HttpResponse = call(provider, "token endpoint") {
            httpClient.submitForm(
                url = metadata.tokenEndpoint,
                formParameters = parameters {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", redirectUri(provider))
                    append("code_verifier", verifier)
                    if (!basic) {
                        append("client_id", provider.clientId)
                        if (secret.isNotEmpty()) append("client_secret", secret)
                    }
                }
            ) {
                accept(ContentType.Application.Json)
                if (basic) {
                    val credentials = "${provider.clientId.encodeURLParameter()}:${secret.encodeURLParameter()}"
                    header(HttpHeaders.Authorization, "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray()))
                }
            }
        }
        if (!response.status.isSuccess()) {
            logger.warn(
                "The token endpoint of OIDC provider {} answered {}: {}",
                provider.id,
                response.status,
                response.bodyAsText().take(LOG_BODY_LIMIT)
            )
            throw SignInFailure("${provider.label} did not accept the sign-in; start again")
        }
        return parseObject(provider, response.bodyAsText())
    }

    private suspend fun verify(provider: OidcProviderConfig, metadata: Metadata, idToken: String, nonce: String): JWTClaimsSet {
        val jwt: SignedJWT = runCatching { SignedJWT.parse(idToken) }.getOrElse {
            throw SignInFailure("${provider.label} returned an ID token that is not a signed JWT")
        }
        val algorithm: JWSAlgorithm = jwt.header.algorithm
        if (algorithm !in ALLOWED_ALGORITHMS) {
            throw SignInFailure("${provider.label} signed the ID token with $algorithm, which is not accepted")
        }
        val key: JWK = signingKey(provider, metadata, jwt.header.keyID, refresh = false)
            ?: signingKey(provider, metadata, jwt.header.keyID, refresh = true)
            ?: throw SignInFailure("${provider.label} signed the ID token with a key it does not publish")
        val verifier: JWSVerifier = when (key) {
            is RSAKey -> RSASSAVerifier(key)
            is ECKey -> ECDSAVerifier(key)
            else -> throw SignInFailure("${provider.label} signed the ID token with an unsupported key type")
        }
        if (!runCatching { jwt.verify(verifier) }.getOrDefault(false)) {
            throw SignInFailure("The ID token from ${provider.label} has an invalid signature")
        }
        val claims: JWTClaimsSet = jwt.jwtClaimsSet
        val now: Instant = clock.instant()
        fun reject(reason: String): Nothing {
            logger.warn("Rejected an ID token from OIDC provider {}: {}", provider.id, reason)
            throw SignInFailure("The ID token from ${provider.label} is not valid for this manager")
        }
        if (claims.issuer != metadata.issuer) reject("issuer ${claims.issuer} is not ${metadata.issuer}")
        if (provider.clientId !in claims.audience.orEmpty()) reject("audience ${claims.audience} does not include ${provider.clientId}")
        if (claims.audience.size > 1 && claims.getStringClaim("azp") != provider.clientId) {
            reject(
                "several audiences and azp is not the client id"
            )
        }
        val expiresAt: Instant = claims.expirationTime?.toInstant() ?: reject("it has no expiry")
        if (expiresAt.plus(CLOCK_SKEW).isBefore(now)) reject("it expired at $expiresAt")
        claims.issueTime?.toInstant()?.let { issuedAt ->
            if (issuedAt.minus(
                    CLOCK_SKEW
                ).isAfter(now)
            ) {
                reject("it was issued in the future")
            }
        }
        if (claims.getStringClaim("nonce") != nonce) reject("the nonce does not match this sign-in")
        if (claims.subject.isNullOrBlank()) reject("it has no subject")
        return claims
    }

    private suspend fun signingKey(provider: OidcProviderConfig, metadata: Metadata, keyId: String?, refresh: Boolean): JWK? {
        if (refresh || !keysByUri.containsKey(metadata.jwksUri)) {
            val response: HttpResponse = call(provider, "keys") { httpClient.get(metadata.jwksUri) }
            if (!response.status.isSuccess()) {
                throw SignInFailure("Cannot read ${provider.label}'s signing keys (${response.status.value})")
            }
            keysByUri[metadata.jwksUri] = runCatching { JWKSet.parse(response.bodyAsText()) }.getOrElse {
                throw SignInFailure("${provider.label} publishes signing keys this manager cannot read")
            }
        }
        val keys: List<JWK> = keysByUri[metadata.jwksUri]?.keys.orEmpty()
            .filter { key -> key.keyUse == null || key.keyUse.identifier() == "sig" }
        return if (keyId != null) keys.firstOrNull { key -> key.keyID == keyId } else keys.singleOrNull()
    }

    private suspend fun userinfo(provider: OidcProviderConfig, endpoint: String, accessToken: String, subject: String): JWTClaimsSet? {
        val response: HttpResponse = call(provider, "userinfo endpoint") {
            httpClient.get(endpoint) {
                bearerAuth(accessToken)
                accept(ContentType.Application.Json)
            }
        }
        if (!response.status.isSuccess()) {
            logger.warn("The userinfo endpoint of OIDC provider {} answered {}", provider.id, response.status)
            return null
        }
        val claims: JWTClaimsSet = runCatching { JWTClaimsSet.parse(response.bodyAsText()) }.getOrNull() ?: return null
        // Userinfo about someone else must not be merged into this sign-in.
        return claims.takeIf { info -> info.subject == subject }
    }

    private suspend fun metadata(provider: OidcProviderConfig): Metadata {
        val issuer: String = provider.issuer.trimEnd('/')
        metadataByIssuer[issuer]?.takeIf { cached -> Duration.between(cached.fetchedAt, clock.instant()) < METADATA_TTL }?.let { cached ->
            return cached
        }
        val response: HttpResponse = call(provider, "discovery document") { httpClient.get("$issuer/.well-known/openid-configuration") }
        if (!response.status.isSuccess()) {
            throw SignInFailure("Cannot read ${provider.label}'s OpenID configuration (${response.status.value})")
        }
        val document: JsonObject = parseObject(provider, response.bodyAsText())
        fun field(name: String): String? = (document[name] as? JsonPrimitive)?.contentOrNull
        val advertisedIssuer: String = field("issuer") ?: throw SignInFailure("${provider.label}'s OpenID configuration names no issuer")
        if (advertisedIssuer.trimEnd('/') != issuer) {
            throw SignInFailure("${provider.label}'s OpenID configuration names issuer $advertisedIssuer instead of $issuer")
        }
        val metadata = Metadata(
            issuer = advertisedIssuer,
            authorizationEndpoint = field("authorization_endpoint")
                ?: throw SignInFailure("${provider.label} has no authorization endpoint"),
            tokenEndpoint = field("token_endpoint") ?: throw SignInFailure("${provider.label} has no token endpoint"),
            jwksUri = field("jwks_uri") ?: throw SignInFailure("${provider.label} publishes no signing keys"),
            userinfoEndpoint = field("userinfo_endpoint"),
            tokenAuthMethods = (document["token_endpoint_auth_methods_supported"] as? JsonArray)
                ?.mapNotNull { method -> (method as? JsonPrimitive)?.contentOrNull }
                .orEmpty(),
            fetchedAt = clock.instant()
        )
        metadataByIssuer[issuer] = metadata
        return metadata
    }

    private suspend fun call(provider: OidcProviderConfig, what: String, request: suspend () -> HttpResponse): HttpResponse = try {
        request()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: SignInFailure) {
        throw error
    } catch (error: Exception) {
        logger.warn("Cannot reach the {} of OIDC provider {}: {}", what, provider.id, error.message)
        throw SignInFailure("Cannot reach ${provider.label}; try again later")
    }

    private fun parseObject(provider: OidcProviderConfig, body: String): JsonObject = runCatching {
        json.parseToJsonElement(
            body
        ).jsonObject
    }
        .getOrElse { throw SignInFailure("${provider.label} answered with something that is not JSON") }

    private fun provider(id: String): OidcProviderConfig =
        authConfig().oidcProvider(id) ?: throw SignInFailure("There is no sign-in provider '$id'")

    private fun isExpired(signIn: PendingSignIn): Boolean = Duration.between(signIn.startedAt, clock.instant()) > PENDING_TTL

    private fun forgetExpired() {
        pending.entries.removeIf { (_, signIn) -> isExpired(signIn) }
        if (pending.size >= MAX_PENDING) {
            throw SignInFailure("Too many sign-ins are in progress; try again in a few minutes")
        }
    }

    private fun JWTClaimsSet.string(name: String): String? = runCatching {
        getStringClaim(name)
    }.getOrNull()?.takeIf { value -> value.isNotBlank() }

    private fun JWTClaimsSet.stringSet(name: String): Set<String>? = when (val value: Any? = getClaim(name)) {
        null -> null
        is String -> setOf(value)
        is Collection<*> -> value.mapNotNull { item -> item?.toString() }.toSet()
        else -> null
    }

    companion object {
        /** Asymmetric signatures only: never `none`, and never HMAC keyed with the client secret. */
        private val ALLOWED_ALGORITHMS: Set<JWSAlgorithm> = setOf(
            JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512,
            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512
        )
        private val CLOCK_SKEW: Duration = Duration.ofMinutes(2)
        private val METADATA_TTL: Duration = Duration.ofHours(1)
        private val PENDING_TTL: Duration = Duration.ofMinutes(10)
        private const val MAX_PENDING = 10_000
        private const val STATE_BYTES = 32
        private const val VERIFIER_BYTES = 48
        private const val LOG_BODY_LIMIT = 300

        /** Only paths on this manager: never another host, so the sign-in cannot be used to redirect people elsewhere. */
        fun safeReturnTo(returnTo: String?): String = returnTo
            ?.takeIf { path -> path.startsWith("/") && !path.startsWith("//") && '\\' !in path && path.length <= RETURN_TO_LIMIT }
            ?: "/"

        private const val RETURN_TO_LIMIT = 512

        private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))

        private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
