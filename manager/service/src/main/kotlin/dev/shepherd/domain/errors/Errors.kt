package dev.shepherd.domain.errors

/** Failures with a definite HTTP meaning. Their messages are shown to API clients as-is. */
sealed class ShepherdException(message: String) : RuntimeException(message)

/** 404: the resource does not exist. */
class ResourceNotFoundException(message: String) : ShepherdException(message)

/** 403: the caller is authenticated but not allowed to do this. */
class AccessDeniedException(message: String) : ShepherdException(message)

/** 409: the change conflicts with the current state. */
class ConflictException(message: String) : ShepherdException(message)

/** 429: the caller's quota does not allow this right now. */
class QuotaExceededException(message: String) : ShepherdException(message)
