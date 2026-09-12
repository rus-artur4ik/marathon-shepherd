package dev.shepherd.mcp

/**
 * Limits for sessions that agents open through MCP, on top of the API key's own quota. An agent
 * forgets to clean up more often than a CI job does, so its sessions are small and short, and are
 * released soon after the agent stops checking on them.
 */
data class McpGuardrails(
    /** Most devices one acquire_devices call may ask for. */
    val maxDevicesPerSession: Int = 2,
    /** Lifetime of a session when the agent does not ask for one. */
    val defaultTtlSeconds: Long = 1_800,
    /** Longest lifetime an agent may ask for, when acquiring or extending. */
    val maxTtlSeconds: Long = 14_400,
    /** A session is released after this long without get_session or wait_for_session. */
    val idleTimeoutSeconds: Long = 900,
    /** Longest one tool call waits for busy devices before answering with the still-queued session. */
    val maxWaitSeconds: Long = 60
) {
    init {
        require(maxDevicesPerSession > 0) { "maxDevicesPerSession must be positive" }
        require(defaultTtlSeconds > 0) { "defaultTtlSeconds must be positive" }
        require(maxTtlSeconds >= defaultTtlSeconds) { "maxTtlSeconds must be at least defaultTtlSeconds" }
        require(idleTimeoutSeconds >= MIN_IDLE_TIMEOUT_SECONDS) { "idleTimeoutSeconds must be at least $MIN_IDLE_TIMEOUT_SECONDS" }
        require(maxWaitSeconds >= 0) { "maxWaitSeconds must not be negative" }
    }

    companion object {
        /** The manager rejects shorter idle timeouts. */
        const val MIN_IDLE_TIMEOUT_SECONDS: Long = 30

        /**
         * Reads `MSH_MCP_MAX_DEVICES`, `MSH_MCP_DEFAULT_TTL_SECONDS`, `MSH_MCP_MAX_TTL_SECONDS`,
         * `MSH_MCP_IDLE_TIMEOUT_SECONDS` and `MSH_MCP_MAX_WAIT_SECONDS`; unset ones keep their defaults.
         */
        fun fromEnvironment(environment: Map<String, String>): McpGuardrails {
            fun number(name: String): Long? = environment[name]?.trim()?.takeIf { value -> value.isNotEmpty() }?.let { value ->
                value.toLongOrNull() ?: throw IllegalArgumentException("$name must be a whole number, got '$value'")
            }
            val defaults = McpGuardrails()
            return McpGuardrails(
                maxDevicesPerSession = number("MSH_MCP_MAX_DEVICES")?.toInt() ?: defaults.maxDevicesPerSession,
                defaultTtlSeconds = number("MSH_MCP_DEFAULT_TTL_SECONDS") ?: defaults.defaultTtlSeconds,
                maxTtlSeconds = number("MSH_MCP_MAX_TTL_SECONDS") ?: defaults.maxTtlSeconds,
                idleTimeoutSeconds = number("MSH_MCP_IDLE_TIMEOUT_SECONDS") ?: defaults.idleTimeoutSeconds,
                maxWaitSeconds = number("MSH_MCP_MAX_WAIT_SECONDS") ?: defaults.maxWaitSeconds
            )
        }
    }
}
