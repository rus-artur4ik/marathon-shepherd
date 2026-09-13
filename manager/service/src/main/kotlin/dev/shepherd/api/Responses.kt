package dev.shepherd.api

import dev.shepherd.ApiJson
import dev.shepherd.protocol.ErrorResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey

/** Set on calls whose error body was written by [respondError], so status pages do not replace it. */
internal val ErrorBodyWritten: AttributeKey<Unit> = AttributeKey("msh.error-body-written")

/** Writes the API's error body, `{"error": "..."}`, independently of content negotiation. */
suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) {
    attributes.put(ErrorBodyWritten, Unit)
    respondText(
        text = ApiJson.encodeToString(ErrorResponse.serializer(), ErrorResponse(message)),
        contentType = ContentType.Application.Json,
        status = status
    )
}
