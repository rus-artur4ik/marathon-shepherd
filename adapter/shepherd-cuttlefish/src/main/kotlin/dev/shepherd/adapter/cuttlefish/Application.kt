package dev.shepherd.adapter.cuttlefish

import dev.shepherd.adapter.api.AdapterEnv
import dev.shepherd.adapter.api.startAdapterServer
import org.slf4j.LoggerFactory
import java.io.File

fun main() {
    val env: AdapterEnv = AdapterEnv.fromEnvironment(defaultPort = 7037, defaultAdbPort = 6520)
    val orchestratorUrl: String = System.getenv("CUTTLEFISH_ORCHESTRATOR_URL")
        .orEmpty()
        .ifBlank { "https://127.0.0.1:2443" }
    val authToken: String = System.getenv("CUTTLEFISH_ORCHESTRATOR_TOKEN").orEmpty()
    val basicUsername: String = System.getenv("CUTTLEFISH_ORCHESTRATOR_USERNAME")
        .orEmpty()
        .ifBlank { "shepherd-cuttlefish" }
    // Secure by default. The previous default disabled certificate validation exactly
    // when the URL was https://, which silently downgraded every TLS deployment to an
    // unauthenticated channel. Opt out explicitly, and only for self-signed dev hosts.
    val insecureTls: Boolean = System.getenv("CUTTLEFISH_ORCHESTRATOR_INSECURE_TLS")
        ?.toBooleanStrictOrNull()
        ?: false
    if (insecureTls) {
        LoggerFactory.getLogger("dev.shepherd.adapter.cuttlefish").warn(
            "CUTTLEFISH_ORCHESTRATOR_INSECURE_TLS=true — TLS certificate validation is DISABLED " +
                "for $orchestratorUrl. Traffic to the orchestrator, including its bearer token, " +
                "can be intercepted. Use this only against a local self-signed dev host."
        )
    }
    val defaultLeasesPath: String = "${System.getProperty("user.home")}/.msh/cuttlefish-orchestrator-leases.json"
    val leasesPath: String = System.getenv("CUTTLEFISH_ORCHESTRATOR_LEASES_PATH") ?: defaultLeasesPath
    startAdapterServer(
        CuttlefishAdapterHandler(
            cloudOrchestratorService = CloudOrchestratorService(
                orchestratorUrl = orchestratorUrl,
                authToken = authToken,
                basicUsername = basicUsername,
                httpClient = buildCloudOrchestratorHttpClient(insecureTls = insecureTls),
                leaseStore = FileCloudOrchestratorLeaseStore(File(leasesPath))
            )
        ),
        env
    )
}
