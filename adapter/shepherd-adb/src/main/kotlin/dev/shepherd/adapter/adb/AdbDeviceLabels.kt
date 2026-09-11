package dev.shepherd.adapter.adb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File

/** Key of the labels-file entry that applies to every device. */
const val ADB_LABELS_WILDCARD: String = "*"

/**
 * Operator-assigned device labels, e.g. `form=tablet`, that sessions can select devices by.
 *
 * They come from the JSON file named by `ADB_DEVICE_LABELS_FILE`, which maps a serial to its
 * labels. The [ADB_LABELS_WILDCARD] entry applies to every device, and a serial's own entry
 * overrides it key by key:
 * ```
 * { "*": { "rack": "a" }, "R58M123ABC": { "form": "tablet", "rack": "b" } }
 * ```
 * The file is read again whenever it changes, so relabelling a device needs no adapter restart,
 * which would drop its leases. A version that does not parse is logged and the last good labels
 * stay in force, so a half-saved edit cannot strip every label at once.
 */
class AdbDeviceLabels(private val file: File? = null) {
    private val logger = LoggerFactory.getLogger(AdbDeviceLabels::class.java)
    private var labels: AdbLabelTable = AdbLabelTable()
    private var loadedVersion: FileVersion? = null
    private var reportedMissing: Boolean = false

    /** The labels as of the latest version of the file that parsed. */
    @Synchronized
    fun current(): AdbLabelTable {
        if (file == null) {
            return labels
        }
        if (!file.isFile) {
            if (!reportedMissing) {
                logger.warn("Device labels file {} does not exist; devices carry no labels until it does", file)
                reportedMissing = true
            }
            labels = AdbLabelTable()
            loadedVersion = null
            return labels
        }
        reportedMissing = false
        val version = FileVersion(modifiedAt = file.lastModified(), size = file.length())
        if (version == loadedVersion) {
            return labels
        }
        // Remember the version even when it fails to parse, so a broken file is reported once
        // instead of on every status poll until someone fixes it.
        loadedVersion = version
        try {
            labels = parse(file.readText())
            logger.info("Loaded device labels for {} entries from {}", labels.entries.size, file)
        } catch (error: Exception) {
            logger.error("Cannot parse device labels file {}, keeping the previous labels: {}", file, error.message)
        }
        return labels
    }

    private fun parse(text: String): AdbLabelTable {
        val root = Json.parseToJsonElement(text)
        require(root is JsonObject) { "expected a JSON object mapping serials to labels" }
        return AdbLabelTable(
            root.mapValues { (serial, serialLabels) ->
                require(serialLabels is JsonObject) { "labels of '$serial' must be a JSON object" }
                serialLabels.mapValues { (key, value) ->
                    require(value is JsonPrimitive && value !is JsonNull) { "label '$key' of '$serial' must be a string" }
                    value.content
                }
            }
        )
    }

    companion object {
        fun fromEnvironment(): AdbDeviceLabels =
            AdbDeviceLabels(System.getenv("ADB_DEVICE_LABELS_FILE")?.takeIf { path -> path.isNotBlank() }?.let(::File))
    }
}

/** One parsed version of the labels file. */
data class AdbLabelTable(val entries: Map<String, Map<String, String>> = emptyMap()) {
    /** Labels of [serial]: the wildcard entry, overridden key by key by the serial's own entry. */
    fun labelsFor(serial: String): Map<String, String> = entries[ADB_LABELS_WILDCARD].orEmpty() + entries[serial].orEmpty()
}

/** Modification time alone can miss a quick second save on coarse-grained file systems; size catches most of those. */
private data class FileVersion(val modifiedAt: Long, val size: Long)
