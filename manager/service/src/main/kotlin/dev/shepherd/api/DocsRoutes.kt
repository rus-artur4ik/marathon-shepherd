package dev.shepherd.api

import dev.shepherd.common.BuildInfo
import io.ktor.http.ContentType
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The OpenAPI document shipped in the jar, with the build version filled in. */
object OpenApiDocument {
    private const val RESOURCE: String = "/openapi/documentation.yaml"
    private const val VERSION_PLACEHOLDER: String = "__VERSION__"

    val yaml: String by lazy {
        val raw: String = OpenApiDocument::class.java.getResourceAsStream(RESOURCE)
            ?.bufferedReader()
            ?.use { reader -> reader.readText() }
            ?: error("OpenAPI document $RESOURCE is missing from the classpath")
        raw.replace(VERSION_PLACEHOLDER, BuildInfo.version)
    }
}

private val YAML: ContentType = ContentType.parse("application/yaml; charset=utf-8")

fun Route.docsRoutes() {
    get("/openapi.yaml") {
        call.respondText(OpenApiDocument.yaml, YAML)
    }
    // Swagger UI pulls its assets from unpkg; the raw document above also works offline.
    swaggerUI(path = "docs", apiUrl = "openapi.yaml", api = OpenApiDocument.yaml)
}
