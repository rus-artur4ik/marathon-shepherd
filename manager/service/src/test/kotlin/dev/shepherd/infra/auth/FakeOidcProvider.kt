package dev.shepherd.infra.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.shepherd.domain.model.OidcProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant
import java.util.Date

/** An OIDC provider behind a mock HTTP engine: discovery, keys, token and userinfo endpoints. */
internal class FakeOidcProvider(private val clock: Clock, val issuer: String = "https://idp.example.com/realms/qa") {
    val signingKey: RSAKey = RSAKeyGenerator(KEY_BITS).keyID("key-1").generate()

    /** What the token endpoint answers with next. */
    var nextIdToken: String = ""
    var userinfo: String = """{"sub":"user-123"}"""
    val tokenRequests = mutableListOf<HttpRequestData>()
    val tokenForms = mutableListOf<Parameters>()

    val client: HttpClient = HttpClient(
        MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (request.url.toString().substringBefore('?')) {
                "$issuer/.well-known/openid-configuration" -> respond(
                    """{"issuer":"$issuer","authorization_endpoint":"$issuer/auth","token_endpoint":"$issuer/token",""" +
                        """"jwks_uri":"$issuer/certs","userinfo_endpoint":"$issuer/userinfo",""" +
                        """"token_endpoint_auth_methods_supported":["client_secret_basic","client_secret_post"]}""",
                    HttpStatusCode.OK,
                    json
                )
                "$issuer/certs" -> respond(JWKSet(signingKey.toPublicJWK()).toString(), HttpStatusCode.OK, json)
                "$issuer/token" -> {
                    tokenRequests += request
                    (request.body as? FormDataContent)?.let { form -> tokenForms += form.formData }
                    val body = buildJsonObject {
                        put("id_token", nextIdToken)
                        put("access_token", "access-1")
                        put("token_type", "Bearer")
                    }
                    respond(body.toString(), HttpStatusCode.OK, json)
                }
                "$issuer/userinfo" -> respond(userinfo, HttpStatusCode.OK, json)
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
    )

    fun idToken(
        nonce: String,
        subject: String = "user-123",
        username: String = "carol",
        groups: List<String>? = listOf("shepherd-admins"),
        key: RSAKey = signingKey,
        audience: String = CLIENT_ID,
        issuer: String = this.issuer,
        expiresAt: Instant = clock.instant().plusSeconds(300),
        algorithm: JWSAlgorithm = JWSAlgorithm.RS256
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .audience(audience)
            .issueTime(Date.from(clock.instant()))
            .expirationTime(Date.from(expiresAt))
            .claim("nonce", nonce)
            .claim("preferred_username", username)
            .claim("email", "$username@example.com")
            .claim("name", username.replaceFirstChar { first -> first.uppercase() })
            .apply { groups?.let { names -> claim("groups", names) } }
            .build()
        return SignedJWT(JWSHeader.Builder(algorithm).keyID(key.keyID).build(), claims)
            .apply { sign(RSASSASigner(key)) }
            .serialize()
    }

    fun config(roleMapping: Map<String, String> = mapOf("shepherd-admins" to "admin")): OidcProviderConfig = OidcProviderConfig(
        id = "keycloak",
        displayName = "Keycloak",
        issuer = issuer,
        clientId = CLIENT_ID,
        clientSecret = CLIENT_SECRET,
        roleMapping = roleMapping
    )

    companion object {
        const val CLIENT_ID = "shepherd"
        const val CLIENT_SECRET = "client-secret"
        private const val KEY_BITS = 2048
    }
}
