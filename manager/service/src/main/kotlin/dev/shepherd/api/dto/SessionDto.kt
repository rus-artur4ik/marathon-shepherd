package dev.shepherd.api.dto

import dev.shepherd.domain.model.Session
import dev.shepherd.domain.model.SessionDevice
import dev.shepherd.protocol.SessionDeviceDto
import dev.shepherd.protocol.SessionResponse

fun Session.toResponse(queuePosition: Int? = null) = SessionResponse(
    id = id,
    status = status.name,
    requestedDevices = requestedDevices,
    allocatedDevices = allocatedDevices,
    api = api,
    deviceType = deviceType,
    adbServers = adbServers,
    queuePosition = queuePosition,
    createdAt = createdAt.toString(),
    expiresAt = expiresAt.toString(),
    owner = ownerName,
    name = name,
    metadata = metadata,
    priority = priority,
    idleTimeoutSeconds = idleTimeoutSeconds,
    labels = labels,
    deviceIds = deviceIds,
    devices = devices.map { device -> device.toDto() },
    lastHeartbeatAt = lastHeartbeatAt.toString()
)

fun SessionDevice.toDto(): SessionDeviceDto = SessionDeviceDto(
    id = id,
    provider = provider,
    localId = localId,
    adbServer = adbServer,
    model = model,
    apiLevel = apiLevel
)
