package dev.shepherd.adapter.cuttlefish

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Longest response body echoed into a log line before it is truncated. */
private const val CLOUD_LOG_BODY_LIMIT: Int = 400

/** Raw HTTP result: the orchestrator signals failure through status codes, not exceptions. */
internal data class CloudHttpResponse(val statusCode: Int, val body: String)

/**
 * The HTTP transport for the Cloud Orchestrator API: auth headers, timeouts, URL joining
 * and status-code handling.
 *
 * Extracted from `CloudOrchestratorService` so that the service is about orchestrating
 * Cuttlefish instances, and everything about *how* a request reaches the orchestrator
 * lives in one place with one set of tests.
 */
internal class CloudOrchestratorTransport(
    private val orchestratorUrl: String,
    private val authToken: String,
    private val basicUsername: String,
    private val httpClient: HttpClient,
    private val requestTimeoutSeconds: Long
) {
    private val logger = LoggerFactory.getLogger(CloudOrchestratorTransport::class.java)

    /**
     * Performs a request and returns the body on 2xx, or null on any transport error or
     * non-2xx status.
     *
     * Null-on-failure rather than throwing: nearly every caller probes an endpoint that is
     * legitimately allowed to be absent (the two API dialects expose different paths), so
     * exceptions would be control flow. Set [logFailures] to false for those probes.
     */
    fun send(method: String, path: String, requestBody: String? = null, logFailures: Boolean = true): String? {
        val response: CloudHttpResponse = try {
            execute(method, path, requestBody)
        } catch (error: Exception) {
            if (logFailures) {
                logger.error("Cloud Orchestrator {} {} error: {}", method, path, error.message)
            }
            return null
        }
        if (response.statusCode in 200..299) {
            return response.body
        }
        if (logFailures) {
            logger.error(
                "Cloud Orchestrator {} {} failed with {}: {}",
                method,
                path,
                response.statusCode,
                summarizeResponseBody(response.body)
            )
        }
        return null
    }

    fun execute(method: String, path: String, requestBody: String? = null): CloudHttpResponse {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(buildUrl(path)))
            .timeout(Duration.ofSeconds(requestTimeoutSeconds))
            .header("Accept", "application/json")
        if (authToken.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $authToken")
        } else if (basicUsername.isNotBlank()) {
            requestBuilder.header("Authorization", buildBasicAuthorizationHeader(basicUsername))
        }
        val request: HttpRequest = when {
            method == "GET" -> requestBuilder.GET().build()
            requestBody == null -> requestBuilder.method(method, HttpRequest.BodyPublishers.noBody()).build()
            else ->
                requestBuilder
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()
        }
        val response: HttpResponse<String> = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return CloudHttpResponse(statusCode = response.statusCode(), body = response.body())
    }

    private fun buildUrl(path: String): String = "${orchestratorUrl.trimEnd('/')}${if (path.startsWith("/")) path else "/$path"}"
}

/**
 * Collapses whitespace and truncates a response body, so one bad response cannot flood
 * the log. Shared by the transport and by the service's own diagnostic logging.
 */
internal fun summarizeResponseBody(body: String): String {
    val normalizedBody: String = body.replace(Regex("\\s+"), " ").trim()
    return if (normalizedBody.length <= CLOUD_LOG_BODY_LIMIT) {
        normalizedBody
    } else {
        normalizedBody.take(CLOUD_LOG_BODY_LIMIT) + "...(truncated)"
    }
}

private fun buildBasicAuthorizationHeader(username: String): String {
    val encodedValue: String = Base64.getEncoder().encodeToString("$username:".toByteArray())
    return "Basic $encodedValue"
}

/**
 * Builds the JDK HTTP client used to reach the orchestrator.
 *
 * [insecureTls] disables certificate validation entirely. It exists for local, self-signed
 * development hosts only, defaults to off, and the adapter logs a warning at startup when
 * it is switched on — see `Application.kt`.
 */
internal fun buildCloudOrchestratorHttpClient(insecureTls: Boolean = false): HttpClient {
    val builder: HttpClient.Builder = HttpClient.newBuilder()
    if (!insecureTls) {
        return builder.build()
    }
    val trustAllCertificates = arrayOf<TrustManager>(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    )
    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(null, trustAllCertificates, SecureRandom())
    return builder
        .sslContext(sslContext)
        .build()
}
