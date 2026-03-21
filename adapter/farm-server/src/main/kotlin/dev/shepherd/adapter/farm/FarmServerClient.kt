package dev.shepherd.adapter.farm

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * HTTP client for the farm-server process running on the same host.
 * Translates farm-server's native API into adapter-level operations.
 */
class FarmServerClient(
    private val farmServerUrl: String,
    private val httpClient: HttpClient
) {
    private val logger = LoggerFactory.getLogger(FarmServerClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getStatus(): FarmStatus {
        return try {
            val response = httpClient.get("$farmServerUrl/status")
            if (response.status.isSuccess()) {
                json.decodeFromString<FarmStatus>(response.bodyAsText())
            } else {
                logger.warn("Farm status failed: ${response.status}")
                FarmStatus(available = 0, busy = 0, total = 0)
            }
        } catch (e: Exception) {
            logger.error("Farm status error: ${e.message}")
            FarmStatus(available = 0, busy = 0, total = 0)
        }
    }

    suspend fun acquireEmulators(count: Int, apiLevel: String, ttlSeconds: Long): FarmAcquireResult {
        val request = FarmAcquireRequest(count = count, apiLevel = apiLevel, ttlSeconds = ttlSeconds)
        return try {
            val response = httpClient.post("$farmServerUrl/acquire") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(FarmAcquireRequest.serializer(), request))
            }
            if (response.status.isSuccess()) {
                json.decodeFromString<FarmAcquireResult>(response.bodyAsText())
            } else {
                logger.error("Farm acquire failed: ${response.status} — ${response.bodyAsText()}")
                FarmAcquireResult(leaseId = "", acquiredCount = 0)
            }
        } catch (e: Exception) {
            logger.error("Farm acquire error: ${e.message}")
            FarmAcquireResult(leaseId = "", acquiredCount = 0)
        }
    }

    suspend fun releaseEmulators(leaseId: String): Boolean {
        return try {
            val response = httpClient.delete("$farmServerUrl/release/$leaseId")
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.error("Farm release error for $leaseId: ${e.message}")
            false
        }
    }

    suspend fun isHealthy(): Boolean {
        return try {
            httpClient.get("$farmServerUrl/health").status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }
}

@Serializable
data class FarmStatus(val available: Int, val busy: Int, val total: Int)

@Serializable
private data class FarmAcquireRequest(val count: Int, val apiLevel: String, val ttlSeconds: Long)

@Serializable
data class FarmAcquireResult(val leaseId: String, val acquiredCount: Int)
