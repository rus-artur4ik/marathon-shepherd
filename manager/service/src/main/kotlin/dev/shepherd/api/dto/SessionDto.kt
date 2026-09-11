package dev.shepherd.api.dto

import dev.shepherd.domain.model.Session
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
    expiresAt = expiresAt.toString()
)
