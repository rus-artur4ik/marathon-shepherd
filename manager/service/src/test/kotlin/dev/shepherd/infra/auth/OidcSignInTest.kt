package dev.shepherd.infra.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.shepherd.domain.model.AuthConfig
import dev.shepherd.domain.provider.SettableClock
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OidcSignInTest {

    private val clock = SettableClock(Instant.parse("2026-09-13T10:00:00Z"))
    private val provider = FakeOidcProvider(clock)
    private val config = AuthConfig(publicUrl = "https://shepherd.example.com", oidc = listOf(provider.config()))
    private val oidc = OidcSignIn(provider.client, { config }, clock, environment = emptyMap())

    @Test
    fun `a verified ID token signs the person in, with PKCE and a single-use state`() = runBlocking {
        val authorization = Url(oidc.start("keycloak", "/ui/devices"))
        val state: String = checkNotNull(authorization.parameters["state"])
        provider.nextIdToken = provider.idToken(nonce = checkNotNull(authorization.parameters["nonce"]))

        val (identity, returnTo) = oidc.complete("keycloak", "code-1", state, error = null)
        val replay = assertFailsWith<SignInFailure> { oidc.complete("keycloak", "code-1", state, error = null) }

        assertEquals("https://idp.example.com/realms/qa/auth", authorization.toString().substringBefore('?'))
        assertEquals("https://shepherd.example.com/auth/oidc/keycloak/callback", authorization.parameters["redirect_uri"])
        assertEquals("openid profile email", authorization.parameters["scope"])
        assertEquals("S256", authorization.parameters["code_challenge_method"])
        val verifier: String = checkNotNull(provider.tokenForms.single()["code_verifier"])
        assertEquals(authorization.parameters["code_challenge"], base64Url(sha256(verifier)))
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("shepherd:client-secret".toByteArray()),
            provider.tokenRequests.single().headers[HttpHeaders.Authorization]
        )
        assertEquals("user-123", identity.externalId)
        assertEquals("carol", identity.username)
        assertEquals("carol@example.com", identity.email)
        assertEquals(setOf("shepherd-admins"), identity.groups)
        assertEquals("/ui/devices", returnTo)
        assertEquals("This sign-in attempt expired or was already used; start again", replay.message)
    }

    @Test
    fun `forged, expired, misaddressed and unsigned tokens are refused`() = runBlocking {
        val stranger = RSAKeyGenerator(2048).keyID("key-1").generate()
        val attempts: List<(String) -> String> = listOf(
            { nonce -> provider.idToken(nonce, key = stranger) },
            { nonce -> provider.idToken(nonce, expiresAt = clock.instant().minusSeconds(600)) },
            { nonce -> provider.idToken(nonce, audience = "another-app") },
            { nonce -> provider.idToken(nonce, issuer = "https://evil.example.com") },
            { _ -> provider.idToken("a different nonce") },
            { nonce -> hmacSigned(nonce) },
            { _ -> "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ1c2VyLTEyMyJ9." }
        )

        attempts.forEach { forge ->
            val authorization = Url(oidc.start("keycloak", null))
            provider.nextIdToken = forge(checkNotNull(authorization.parameters["nonce"]))
            assertFailsWith<SignInFailure> { oidc.complete("keycloak", "code", authorization.parameters["state"], error = null) }
        }
    }

    @Test
    fun `groups missing from the ID token come from userinfo, only for the same subject`() = runBlocking {
        provider.userinfo = """{"sub":"user-123","groups":["qa"]}"""
        val first = signIn(groups = null)
        provider.userinfo = """{"sub":"someone-else","groups":["shepherd-admins"]}"""
        val second = signIn(groups = null)

        assertEquals(setOf("qa"), first.groups)
        assertEquals(emptySet(), second.groups)
    }

    @Test
    fun `sign-ins only ever return to paths on this manager`() {
        assertEquals("/ui/devices?state=busy", OidcSignIn.safeReturnTo("/ui/devices?state=busy"))
        assertEquals("/", OidcSignIn.safeReturnTo("https://evil.example.com/"))
        assertEquals("/", OidcSignIn.safeReturnTo("//evil.example.com/"))
        assertEquals("/", OidcSignIn.safeReturnTo("/\\evil.example.com"))
        assertEquals("/", OidcSignIn.safeReturnTo(null))
    }

    private suspend fun signIn(groups: List<String>?): ExternalIdentity {
        val authorization = Url(oidc.start("keycloak", null))
        provider.nextIdToken = provider.idToken(nonce = checkNotNull(authorization.parameters["nonce"]), groups = groups)
        return oidc.complete("keycloak", "code", authorization.parameters["state"], error = null).first
    }

    /** A token signed with the client secret as an HMAC key: never acceptable for a confidential client. */
    private fun hmacSigned(nonce: String): String {
        val claims = JWTClaimsSet.Builder().issuer(provider.issuer).subject("user-123").audience(FakeOidcProvider.CLIENT_ID)
            .expirationTime(java.util.Date.from(clock.instant().plusSeconds(300))).claim("nonce", nonce).build()
        return SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims).apply {
            sign(MACSigner("a-shared-secret-that-is-long-enough-for-hs256"))
        }.serialize()
    }

    private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
