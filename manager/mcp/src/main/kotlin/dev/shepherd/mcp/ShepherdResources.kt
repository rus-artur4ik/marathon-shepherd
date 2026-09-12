package dev.shepherd.mcp

import dev.shepherd.protocol.DevicesResponse
import dev.shepherd.protocol.SessionResponse
import dev.shepherd.protocol.ShepherdApi
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal const val DEVICES_URI: String = "shepherd://devices"
internal const val SESSIONS_URI: String = "shepherd://sessions"
private const val JSON_MIME: String = "application/json"

private val ResourceJson = Json {
    prettyPrint = true
    explicitNulls = false
}

/** Read-only JSON views for clients that attach resources rather than call tools. */
internal class ShepherdResources(private val api: ShepherdApi) {
    fun register(server: Server) {
        server.addResource(
            uri = DEVICES_URI,
            name = "Devices",
            description = "Every device and provider pool Marathon Shepherd manages",
            mimeType = JSON_MIME
        ) { _ -> json(DEVICES_URI, ResourceJson.encodeToString(DevicesResponse.serializer(), api.listDevices())) }

        server.addResource(
            uri = SESSIONS_URI,
            name = "My sessions",
            description = "The active sessions of this API key",
            mimeType = JSON_MIME
        ) { _ ->
            json(
                SESSIONS_URI,
                ResourceJson.encodeToString(ListSerializer(SessionResponse.serializer()), api.listSessions(owner = "me"))
            )
        }
    }

    private fun json(uri: String, text: String): ReadResourceResult =
        ReadResourceResult(contents = listOf(TextResourceContents(text = text, uri = uri, mimeType = JSON_MIME)))
}
