package dev.shepherd.api

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.yamlMap
import dev.shepherd.common.BuildInfo
import dev.shepherd.configureServer
import dev.shepherd.infra.state.StateStore
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Keeps `openapi/documentation.yaml` honest: every served operation is documented and every
 * documented operation is served. A new route without a spec entry fails here.
 */
class OpenApiSpecTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `routes and the OpenAPI document describe the same operations`() = testApplication {
        val stateStore = StateStore(File(tempDir, "spec.db").absolutePath)
        val providerRegistry = createRouteProviderRegistry(tempDir, "spec.yaml", listOf(RouteTestProvider()))
        var routingRoot: RoutingNode? = null
        application {
            configureServer(managerServices(providerRegistry, stateStore))
            routingRoot = plugin(RoutingRoot)
        }
        startApplication()

        val served: Set<String> = checkNotNull(routingRoot).descendants()
            .mapNotNull { node -> node.operation() }
            // Swagger UI's assets and the web UI's pages are not part of the API.
            .filterNot { operation ->
                operation.substringAfter(
                    ' '
                ).let { path -> path == "/" || path.startsWith("/docs") || path.startsWith("/ui") }
            }
            .toSortedSet()
        val documented: Set<String> = documentedOperations(OpenApiDocument.yaml).toSortedSet()

        assertEquals(documented, served, "Served routes and openapi/documentation.yaml disagree")
    }

    @Test
    fun `document carries the build version`() {
        assertFalse(OpenApiDocument.yaml.contains("__VERSION__"))
        assertContains(OpenApiDocument.yaml, "version: \"${BuildInfo.version}\"")
    }

    private fun Route.operation(): String? {
        val method = (selector as? HttpMethodRouteSelector)?.method ?: return null
        val segments: List<String> = generateSequence(parent) { node -> node.parent }
            .toList()
            .asReversed()
            .mapNotNull { node ->
                when (val nodeSelector = node.selector) {
                    is PathSegmentConstantRouteSelector -> nodeSelector.value
                    is PathSegmentParameterRouteSelector -> "{${nodeSelector.name}}"
                    else -> null
                }
            }
        return "${method.value} /${segments.joinToString("/")}"
    }

    private fun documentedOperations(yaml: String): List<String> {
        val paths: YamlMap = Yaml.default.parseToYamlNode(yaml).yamlMap.get<YamlMap>("paths")
            ?: error("OpenAPI document has no paths")
        return paths.entries.flatMap { (path, item) ->
            item.yamlMap.entries.keys
                .map { key -> key.content }
                .filter { key -> key in HTTP_METHODS }
                .map { method -> "${method.uppercase()} ${path.content}" }
        }
    }

    private companion object {
        val HTTP_METHODS: Set<String> = setOf("get", "put", "post", "delete", "patch", "head", "options")
    }
}
