package dev.shepherd.protocol

import kotlinx.serialization.Serializable

/** Body of every non-2xx response from the manager API. */
@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class StatusResponse(val status: String)
