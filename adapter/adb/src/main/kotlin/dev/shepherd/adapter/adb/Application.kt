package dev.shepherd.adapter.adb

import dev.shepherd.adapter.api.AdapterEnv
import dev.shepherd.adapter.api.startAdapterServer

fun main() {
    val env = AdapterEnv.fromEnvironment(defaultPort = 7037)
    startAdapterServer(
        AdbAdapterHandler(adbService = AdbService()),
        env
    )
}
