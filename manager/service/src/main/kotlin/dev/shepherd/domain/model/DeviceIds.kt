package dev.shepherd.domain.model

/**
 * Global device ids: `<provider>:<device id>`, e.g. `rack-1:R58M123` or
 * `rack-1:192.168.1.5:5555` for an adb-over-TCP device. Provider names cannot contain ':',
 * so the first ':' always separates the two parts.
 */
object DeviceIds {
    private const val SEPARATOR: Char = ':'

    fun global(provider: String, localId: String): String = "$provider$SEPARATOR$localId"

    /** Provider and local id of [globalId], or null when it is not a global id. */
    fun parse(globalId: String): Pair<String, String>? {
        val index: Int = globalId.indexOf(SEPARATOR)
        if (index <= 0 || index == globalId.length - 1) {
            return null
        }
        return globalId.substring(0, index) to globalId.substring(index + 1)
    }
}
