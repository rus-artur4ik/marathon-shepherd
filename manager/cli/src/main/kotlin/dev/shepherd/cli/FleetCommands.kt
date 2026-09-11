package dev.shepherd.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.associate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import dev.shepherd.client.ShepherdClient
import dev.shepherd.protocol.DeviceDto
import dev.shepherd.protocol.DeviceQuery
import dev.shepherd.protocol.DevicesResponse
import dev.shepherd.protocol.EventDto
import dev.shepherd.protocol.ProviderInfoDto
import dev.shepherd.protocol.ShepherdHealthResponse
import dev.shepherd.protocol.StatusResponse
import dev.shepherd.protocol.WhoAmIResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.serialization.builtins.ListSerializer

class DevicesCommand(clients: ClientFactory) : ShepherdCommand("devices", "List devices and provider pools", clients) {
    override val invokeWithoutSubcommand: Boolean = true

    private val state: String? by option("--state", help = "available, busy, offline or maintenance")
    private val provider: String? by option("--provider", help = "Only this provider's devices")
    private val deviceType: String? by option("--device-type", help = "physical or emulator")
    private val api: String? by option("--api", help = "API levels: 34, >=33, 33..35 or 33,34")
    private val labels: Map<String, String> by option("--label", help = "Device label KEY=VALUE; repeatable").associate()
    private val refresh: Boolean by option("--refresh", help = "Ask every adapter now instead of using the last poll").flag()

    override suspend fun execute(client: ShepherdClient) {
        val devices: DevicesResponse = client.listDevices(DeviceQuery(state, provider, deviceType, api, labels, refresh))
        if (jsonOutput) {
            printJson(DevicesResponse.serializer(), devices)
        } else {
            echo(formatDevices(devices))
        }
    }
}

class DeviceShow(clients: ClientFactory) : ShepherdCommand("show", "Show one device", clients) {
    private val deviceId: String by argument("DEVICE_ID", help = "Global id, provider:device")

    override suspend fun execute(client: ShepherdClient) {
        val device: DeviceDto = client.getDevice(deviceId)
        if (jsonOutput) {
            printJson(DeviceDto.serializer(), device)
        } else {
            echo(formatDevice(device))
        }
    }
}

class DeviceMaintenance(clients: ClientFactory) :
    ShepherdCommand("maintenance", "Take a device out of allocation, or return it with --off (admin)", clients) {
    private val deviceId: String by argument("DEVICE_ID", help = "Global id, provider:device")
    private val reason: String? by option("--reason", help = "Why; shown to everyone who lists devices")
    private val off: Boolean by option("--off", help = "End maintenance").flag()

    override suspend fun execute(client: ShepherdClient) {
        if (off) {
            val status: StatusResponse = client.leaveMaintenance(deviceId)
            if (jsonOutput) {
                printJson(StatusResponse.serializer(), status)
            } else {
                echo("Device $deviceId: ${status.status}.")
            }
            return
        }
        val device: DeviceDto = client.enterMaintenance(deviceId, reason)
        if (jsonOutput) {
            printJson(DeviceDto.serializer(), device)
        } else {
            echo("Device ${device.id} is in maintenance; new sessions will not get it.")
        }
    }
}

class ProvidersCommand(clients: ClientFactory) : ShepherdCommand("providers", "List providers, configured and self-registered", clients) {
    override val invokeWithoutSubcommand: Boolean = true

    override suspend fun execute(client: ShepherdClient) {
        val providers: List<ProviderInfoDto> = client.listProviders()
        if (jsonOutput) {
            printJson(ListSerializer(ProviderInfoDto.serializer()), providers)
        } else {
            echo(formatProviders(providers))
        }
    }
}

class ProviderRemove(clients: ClientFactory) : ShepherdCommand("remove", "Remove a self-registered provider (admin)", clients) {
    private val providerName: String by argument("NAME")

    override suspend fun execute(client: ShepherdClient) {
        val status: StatusResponse = client.deregisterProvider(providerName)
        if (jsonOutput) {
            printJson(StatusResponse.serializer(), status)
        } else {
            echo("Provider $providerName removed.")
        }
    }
}

class EventsCommand(clients: ClientFactory) : ShepherdCommand("events", "Follow the manager's event stream", clients) {
    private val types: List<String> by option("--type", help = "Event type prefix, e.g. session. or device.; repeatable").multiple()
    private val since: Long? by option("--since", help = "First replay the events after this id").long()
    private val limit: Int? by option("--limit", help = "Stop after this many events").int()

    override suspend fun execute(client: ShepherdClient) {
        val stream: Flow<EventDto> = client.events(types, since)
        val events: Flow<EventDto> = limit?.let { count -> stream.take(count) } ?: stream
        events.collect { event ->
            echo(if (jsonOutput) CliJsonLines.encodeToString(EventDto.serializer(), event) else formatEvent(event))
        }
    }
}

class HealthCommand(clients: ClientFactory) : ShepherdCommand("health", "Check manager and provider health", clients) {
    override suspend fun execute(client: ShepherdClient) {
        val health: ShepherdHealthResponse = client.health()
        if (jsonOutput) {
            printJson(ShepherdHealthResponse.serializer(), health)
        } else {
            echo(formatHealth(health))
        }
        if (health.status == UNHEALTHY) {
            throw ProgramResult(1)
        }
    }

    private companion object {
        const val UNHEALTHY = "unhealthy"
    }
}

class WhoAmICommand(clients: ClientFactory) : ShepherdCommand("whoami", "Show the API key's client, role, quota and usage", clients) {
    override suspend fun execute(client: ShepherdClient) {
        val me: WhoAmIResponse = client.whoAmI()
        if (jsonOutput) {
            printJson(WhoAmIResponse.serializer(), me)
        } else {
            echo(formatWhoAmI(me))
        }
    }
}
