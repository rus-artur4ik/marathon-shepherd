package dev.shepherd.api

import io.ktor.http.CacheControl
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Where the web UI lives. */
const val UI_PATH: String = "/ui/"

/** The UI's sign-in page. */
const val UI_SIGN_IN_PATH: String = "/ui/#/sign-in"

/**
 * The UI's files may load scripts, styles and images from this manager and call its API, nothing
 * else: there is no inline script or style to allow and no other origin to trust.
 */
internal const val UI_CONTENT_SECURITY_POLICY: String =
    "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; " +
        "font-src 'self'; manifest-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"

/** The web UI: plain HTML, CSS and JavaScript modules from the jar, a client of the same `/api/v1`. */
fun Route.uiRoutes() {
    get("/") { call.respondRedirect(UI_PATH) }
    get("/ui") { call.respondRedirect(UI_PATH) }
    staticResources("/ui", "ui") {
        // Revalidate on every load, so an upgraded manager never runs with yesterday's scripts.
        cacheControl { listOf(CacheControl.NoCache(null)) }
        enableAutoHeadResponse()
        modify { _, call -> call.response.header("Content-Security-Policy", UI_CONTENT_SECURITY_POLICY) }
    }
}
