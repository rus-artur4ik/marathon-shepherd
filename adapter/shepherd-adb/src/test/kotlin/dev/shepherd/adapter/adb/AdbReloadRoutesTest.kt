package dev.shepherd.adapter.adb

import dev.shepherd.adapter.api.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdbReloadRoutesTest {

    @Test
    fun `post adb reload should return latest status snapshot after successful restart`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(
                    Json {
                        prettyPrint = true
                        encodeDefaults = true
                    }
                )
            }
            configureAdapterAuth(secret = "secret")
            routing {
                adbReloadRoutes(
                    handler = FakeAdbReloadHandler(),
                    env = testEnv(),
                    adminService = FakeAdbAdminService(AdbReloadOutcome.Success("daemon started"))
                )
            }
        }

        val response = client.post("/adb-reload") {
            bearerAuth("secret")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"adbReload\": \"ok\""), body)
        assertTrue(body.contains("\"deviceType\": \"physical\""), body)
    }

    @Test
    fun `blank secret should serve protected routes without credentials instead of failing`() = testApplication {
        // Documented local-dev mode: a blank ADAPTER_SECRET runs the adapter without
        // auth. The auth scheme must still be registered, otherwise every protected
        // route fails to resolve its provider and the adapter is unusable.
        application {
            install(ContentNegotiation) {
                json(
                    Json {
                        prettyPrint = true
                        encodeDefaults = true
                    }
                )
            }
            configureAdapterAuth(secret = "")
            routing {
                adbReloadRoutes(
                    handler = FakeAdbReloadHandler(),
                    env = testEnv(secret = ""),
                    adminService = FakeAdbAdminService(AdbReloadOutcome.Success("daemon started"))
                )
            }
        }

        val response = client.post("/adb-reload")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"adbReload\": \"ok\""), response.bodyAsText())
    }

    @Test
    fun `post adb reload should reject a wrong bearer token`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(
                    Json {
                        prettyPrint = true
                        encodeDefaults = true
                    }
                )
            }
            configureAdapterAuth(secret = "secret")
            routing {
                adbReloadRoutes(
                    handler = FakeAdbReloadHandler(),
                    env = testEnv(),
                    adminService = FakeAdbAdminService(AdbReloadOutcome.Success("daemon started"))
                )
            }
        }

        assertEquals(HttpStatusCode.Unauthorized, client.post("/adb-reload") { bearerAuth("wrong") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/adb-reload").status)
    }

    @Test
    fun `post adb reload should reject when leases are active`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(
                    Json {
                        prettyPrint = true
                        encodeDefaults = true
                    }
                )
            }
            configureAdapterAuth(secret = "secret")
            routing {
                adbReloadRoutes(
                    handler = FakeAdbReloadHandler(),
                    env = testEnv(),
                    adminService = FakeAdbAdminService(AdbReloadOutcome.Busy(listOf("adb_deadbeef")))
                )
            }
        }

        val response = client.post("/adb-reload") {
            bearerAuth("secret")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("adb_deadbeef"))
    }

    @Test
    fun `post adb reload should return server error when adb restart fails`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(
                    Json {
                        prettyPrint = true
                        encodeDefaults = true
                    }
                )
            }
            configureAdapterAuth(secret = "secret")
            routing {
                adbReloadRoutes(
                    handler = FakeAdbReloadHandler(),
                    env = testEnv(),
                    adminService = FakeAdbAdminService(AdbReloadOutcome.Failed("adb reload failed: boom"))
                )
            }
        }

        val response = client.post("/adb-reload") {
            bearerAuth("secret")
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(response.bodyAsText().contains("adb reload failed: boom"))
    }
}

private fun testEnv(secret: String = "secret") = AdapterEnv(
    port = 7037,
    advertisedAdbPort = 5038,
    secret = secret,
    accessMode = ACCESS_EXPOSURE_DIRECT_TCP,
    accessAuthType = ACCESS_AUTH_NETWORK,
    accessScope = "private-network"
)

private class FakeAdbReloadHandler : AdapterHandler(adapterType = "adb") {
    override suspend fun isHealthy(): Boolean = true

    override suspend fun status(): AdapterStatus {
        return AdapterStatus(
            pool = AdapterPool(available = 1, busy = 0, total = 1),
            inventory = listOf(
                AdapterDeviceProfile(
                    deviceType = DEVICE_TYPE_PHYSICAL,
                    apiLevel = "34",
                    manufacturer = "Google",
                    model = "Pixel",
                    abi = "arm64-v8a",
                    count = 1
                )
            )
        )
    }

    override suspend fun acquire(request: AcquireRequest): AcquireResult = AcquireResult(leaseId = null, acquiredCount = 0)

    override suspend fun release(leaseId: String): Boolean = true
}

private class FakeAdbAdminService(
    private val outcome: AdbReloadOutcome
) : AdbAdminService(
    adbService = AdbService(),
    leaseManager = AdbLeaseManager(
        leaseStore = InMemoryAdbLeaseStore(),
        proxyController = object : AdbProxyController {
            override fun startDeviceProxy(leaseId: String, device: AdbPhysicalDevice, preferredPort: Int?): Int = 7600
            override fun stopLease(leaseId: String) = Unit
        }
    )
) {
    override suspend fun reloadAdbDaemon(): AdbReloadOutcome = outcome
}
