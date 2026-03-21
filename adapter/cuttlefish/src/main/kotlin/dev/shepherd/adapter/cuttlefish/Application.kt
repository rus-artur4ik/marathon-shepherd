package dev.shepherd.adapter.cuttlefish

import dev.shepherd.adapter.api.AdapterEnv
import dev.shepherd.adapter.api.startAdapterServer
import java.io.File

fun main() {
    val env = AdapterEnv.fromEnvironment(defaultPort = 7037, defaultAdbPort = 6520)
    val cvdrPath = System.getenv("CVDR_PATH") ?: "cvdr"
    val defaultLeasesPath = "${System.getProperty("user.home")}/.msh/cuttlefish-leases.json"
    val leasesPath = System.getenv("CVDR_LEASES_PATH") ?: defaultLeasesPath
    startAdapterServer(
        CuttlefishAdapterHandler(
            cvdrService = CvdrService(
                cvdrPath = cvdrPath,
                leaseStore = FileCvdrLeaseStore(File(leasesPath))
            )
        ),
        env
    )
}
