package dev.shepherd.api

import dev.shepherd.domain.auth.Actor
import dev.shepherd.domain.auth.ClientQuota
import dev.shepherd.domain.auth.Role
import dev.shepherd.domain.provider.SettableClock
import dev.shepherd.infra.auth.FakeOidcProvider
import dev.shepherd.protocol.AuthMethodsResponse
import dev.shepherd.protocol.CreatedUserResponse
import dev.shepherd.protocol.IssuedTokenResponse
import dev.shepherd.protocol.PasswordResetResponse
import dev.shepherd.protocol.UserDto
import dev.shepherd.protocol.WebSessionResponse
import dev.shepherd.protocol.WhoAmIResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.setCookie
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebSignInRoutesTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a person signs in, works through the session cookie and signs out`() = testApplication {
        val services = startManager(tempDir, "web")
        services.createUser("dana", PASSWORD)

        val login: HttpResponse = login("dana", PASSWORD)
        val cookie = login.setCookie().single { cookie -> cookie.name == SESSION_COOKIE }
        val session: WebSessionResponse = TestJson.decodeFromString(login.bodyAsText())
        val devices = client.get("/api/v1/devices") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=${cookie.value}") }
        val withoutCsrf = client.post("/api/v1/sessions") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=${cookie.value}")
            contentType(ContentType.Application.Json)
            setBody(SESSION_BODY)
        }
        val created = client.post("/api/v1/sessions") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=${cookie.value}")
            header(CSRF_HEADER, session.csrfToken)
            contentType(ContentType.Application.Json)
            setBody(SESSION_BODY)
        }
        val me: WhoAmIResponse = TestJson.decodeFromString(
            client.get("/api/v1/me") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=${cookie.value}") }.bodyAsText()
        )
        val logout = client.post("/api/v1/auth/logout") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=${cookie.value}")
            header(CSRF_HEADER, session.csrfToken)
        }
        val afterLogout = client.get("/api/v1/devices") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=${cookie.value}") }

        assertEquals(HttpStatusCode.OK, login.status)
        assertTrue(cookie.httpOnly)
        assertEquals("Lax", cookie.extensions["SameSite"])
        assertEquals("dana", session.user.username)
        assertEquals(HttpStatusCode.OK, devices.status)
        assertEquals(HttpStatusCode.Forbidden, withoutCsrf.status)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        assertEquals("dana", created.session().owner)
        assertEquals("user", me.kind)
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertEquals(HttpStatusCode.Unauthorized, afterLogout.status)
    }

    @Test
    fun `failed sign-ins look the same for every account and lock the username out`() = testApplication {
        val services = startManager(
            tempDir,
            "lockout",
            extraConfig = """
                |auth:
                |  sessions:
                |    maxFailedAttempts: 3
            """.trimMargin()
        )
        services.createUser("erin", PASSWORD)

        val unknown: HttpResponse = login("nobody", PASSWORD)
        val wrong: HttpResponse = login("erin", "not the password")
        repeat(2) { login("erin", "not the password") }
        val locked: HttpResponse = login("erin", PASSWORD)

        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals(unknown.bodyAsText(), wrong.bodyAsText())
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        assertNotNull(locked.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `a temporary password must be replaced before anything else`() = testApplication {
        val services = startManager(tempDir, "temporary")
        val temporary: String = checkNotNull(
            services.accounts.createLocalUser(Actor.SYSTEM, "frank", null, null, null, Role.USER, ClientQuota.UNLIMITED).temporaryPassword
        )

        val login: HttpResponse = login("frank", temporary)
        val cookie: String = "$SESSION_COOKIE=" + login.setCookie().single { cookie -> cookie.name == SESSION_COOKIE }.value
        val session: WebSessionResponse = TestJson.decodeFromString(login.bodyAsText())
        val blocked = client.get("/api/v1/devices") { header(HttpHeaders.Cookie, cookie) }
        val tokenRefused = client.post("/api/v1/auth/tokens") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"frank","password":"$temporary"}""")
        }
        val changed = client.post("/api/v1/me/password") {
            header(HttpHeaders.Cookie, cookie)
            header(CSRF_HEADER, session.csrfToken)
            contentType(ContentType.Application.Json)
            setBody("""{"currentPassword":"$temporary","newPassword":"$PASSWORD"}""")
        }
        val allowed = client.get("/api/v1/devices") { header(HttpHeaders.Cookie, cookie) }

        assertTrue(session.user.mustChangePassword)
        assertEquals(HttpStatusCode.Forbidden, blocked.status)
        assertEquals(HttpStatusCode.Forbidden, tokenRefused.status)
        assertEquals(HttpStatusCode.NoContent, changed.status, changed.bodyAsText())
        assertEquals(HttpStatusCode.OK, allowed.status, "the session that changed the password stays signed in")
    }

    @Test
    fun `a password sign-in hands out a personal token that works as a key until revoked`() = testApplication {
        val services = startManager(tempDir, "tokens")
        services.createUser("gina", PASSWORD)

        val issued: IssuedTokenResponse = TestJson.decodeFromString(
            client.post("/api/v1/auth/tokens") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"gina","password":"$PASSWORD","name":"laptop"}""")
            }.bodyAsText()
        )
        val me: WhoAmIResponse = TestJson.decodeFromString(client.get("/api/v1/me") { bearerAuth(issued.secret) }.bodyAsText())
        val listed = client.get("/api/v1/me/tokens") { bearerAuth(issued.secret) }.bodyAsText()
        val revoked = client.delete("/api/v1/me/tokens/${issued.token.id}") { bearerAuth(issued.secret) }
        val afterRevoke = client.get("/api/v1/me") { bearerAuth(issued.secret) }

        assertEquals("gina", me.name)
        assertEquals("user", me.kind)
        assertContains(listed, "laptop")
        assertEquals(HttpStatusCode.OK, revoked.status)
        assertEquals(HttpStatusCode.Unauthorized, afterRevoke.status)
    }

    @Test
    fun `admins create, change, reset and disable people`() = testApplication {
        startManager(tempDir, "admin-users")

        val created: CreatedUserResponse = TestJson.decodeFromString(
            client.post("/api/v1/admin/users") {
                bearerAuth(TEST_ADMIN_TOKEN)
                contentType(ContentType.Application.Json)
                setBody("""{"username":"hank","role":"viewer","displayName":"Hank"}""")
            }.bodyAsText()
        )
        val promoted: UserDto = TestJson.decodeFromString(
            client.patch("/api/v1/admin/users/${created.user.id}") {
                bearerAuth(TEST_ADMIN_TOKEN)
                contentType(ContentType.Application.Json)
                setBody("""{"role":"user"}""")
            }.bodyAsText()
        )
        val reset: PasswordResetResponse = TestJson.decodeFromString(
            client.post("/api/v1/admin/users/${created.user.id}/password") { bearerAuth(TEST_ADMIN_TOKEN) }.bodyAsText()
        )
        val disabled: UserDto = TestJson.decodeFromString(
            client.delete("/api/v1/admin/users/${created.user.id}") { bearerAuth(TEST_ADMIN_TOKEN) }.bodyAsText()
        )
        val signInWhileDisabled: HttpResponse = login("hank", checkNotNull(reset.temporaryPassword))

        assertNotNull(created.temporaryPassword)
        assertTrue(created.user.mustChangePassword)
        assertEquals("user", promoted.role)
        assertNotNull(reset.temporaryPassword)
        assertEquals(false, disabled.active)
        assertEquals(HttpStatusCode.Unauthorized, signInWhileDisabled.status)
    }

    @Test
    fun `an OIDC sign-in ends with a session cookie and a redirect back`() = testApplication {
        val provider = FakeOidcProvider(SettableClock(Instant.now()))
        val services = startManager(
            tempDir,
            "oidc",
            extraConfig = oidcConfig(provider),
            signInHttpClient = provider.client
        )
        val browser: HttpClient = createClient { followRedirects = false }

        val methods: String = client.get("/api/v1/auth/methods").bodyAsText()
        val start: HttpResponse = browser.get("/auth/oidc/keycloak/login?returnTo=/ui/devices")
        val authorization = Url(checkNotNull(start.headers[HttpHeaders.Location]))
        provider.nextIdToken = provider.idToken(nonce = checkNotNull(authorization.parameters["nonce"]))
        val callback: HttpResponse = browser.get("/auth/oidc/keycloak/callback?code=abc&state=${authorization.parameters["state"]}")
        val cookie = callback.setCookie().single { cookie -> cookie.name == SESSION_COOKIE }
        val me: WhoAmIResponse = TestJson.decodeFromString(
            client.get("/api/v1/me") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=${cookie.value}") }.bodyAsText()
        )
        val replayed: HttpResponse = browser.get("/auth/oidc/keycloak/callback?code=abc&state=${authorization.parameters["state"]}")

        assertContains(methods, "/auth/oidc/keycloak/login")
        assertEquals(HttpStatusCode.Found, start.status)
        assertEquals(HttpStatusCode.Found, callback.status)
        assertEquals("/ui/devices", callback.headers[HttpHeaders.Location])
        assertTrue(cookie.secure, "an https public URL makes the cookie secure-only")
        assertEquals("carol", me.name)
        assertEquals("admin", me.role)
        assertEquals(HttpStatusCode.Found, replayed.status, "a used state is refused")
        assertEquals("/ui/#/sign-in", replayed.headers[HttpHeaders.Location])
        assertEquals("carol", checkNotNull(services.accounts.findByUsername("carol")).username)
    }

    @Test
    fun `a failed OIDC sign-in shows its reason on the sign-in page once`() = testApplication {
        val provider = FakeOidcProvider(SettableClock(Instant.now()))
        startManager(tempDir, "oidc-failure", extraConfig = oidcConfig(provider), signInHttpClient = provider.client)
        val browser: HttpClient = createClient { followRedirects = false }

        val failed: HttpResponse = browser.get("/auth/oidc/keycloak/callback?code=abc&state=forged")
        val reason = failed.setCookie().single { cookie -> cookie.name == SIGN_IN_ERROR_COOKIE }
        val shown: HttpResponse = client.get("/api/v1/auth/methods") { header(HttpHeaders.Cookie, "$SIGN_IN_ERROR_COOKIE=${reason.value}") }
        val methods: AuthMethodsResponse = TestJson.decodeFromString(shown.bodyAsText())
        val later: AuthMethodsResponse = TestJson.decodeFromString(client.get("/api/v1/auth/methods").bodyAsText())

        assertEquals(HttpStatusCode.Found, failed.status)
        assertEquals("/ui/#/sign-in", failed.headers[HttpHeaders.Location])
        assertTrue(reason.httpOnly)
        assertEquals("/api/v1/auth", reason.path)
        assertTrue(methods.signInError.orEmpty().isNotBlank(), "the reason is shown")
        assertEquals(0, shown.setCookie().single { cookie -> cookie.name == SIGN_IN_ERROR_COOKIE }.maxAge, "reading it clears it")
        assertEquals(null, later.signInError)
    }

    @Test
    fun `a provider that cannot be reached sends the browser back to the sign-in page`() = testApplication {
        val provider = FakeOidcProvider(SettableClock(Instant.now()))
        startManager(
            tempDir,
            "oidc-down",
            extraConfig = oidcConfig(provider, issuer = "https://down.example.com/realms/qa"),
            signInHttpClient = provider.client
        )
        val browser: HttpClient = createClient { followRedirects = false }

        val start: HttpResponse = browser.get("/auth/oidc/keycloak/login?returnTo=/ui/")

        assertEquals(HttpStatusCode.Found, start.status)
        assertEquals("/ui/#/sign-in", start.headers[HttpHeaders.Location])
        assertTrue(start.setCookie().any { cookie -> cookie.name == SIGN_IN_ERROR_COOKIE && cookie.value.isNotEmpty() })
    }

    private fun oidcConfig(provider: FakeOidcProvider, issuer: String = provider.issuer): String = """
        |auth:
        |  publicUrl: "https://shepherd.example.com"
        |  oidc:
        |    - id: keycloak
        |      displayName: Keycloak
        |      issuer: "$issuer"
        |      clientId: ${FakeOidcProvider.CLIENT_ID}
        |      clientSecret: ${FakeOidcProvider.CLIENT_SECRET}
        |      roleMapping:
        |        shepherd-admins: admin
    """.trimMargin()

    private suspend fun ApplicationTestBuilder.login(username: String, password: String): HttpResponse = client.post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody("""{"username":"$username","password":"$password"}""")
    }

    private companion object {
        const val PASSWORD = "correct horse battery"
        const val SESSION_BODY = """{"maxDevices":1,"api":"34","deviceType":"emulator","ttlSeconds":600}"""
    }
}
