package dev.shepherd.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** What `mshctl login` remembers: a personal token for one manager. */
@Serializable
data class SavedCredentials(
    val manager: String,
    val username: String,
    val tokenId: String,
    val token: String
)

/** The credentials file, readable by its owner only. */
class CredentialsFile(val file: File) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun read(): SavedCredentials? = try {
        file.takeIf { candidate -> candidate.isFile }?.readText()?.let { text ->
            json.decodeFromString(
                SavedCredentials.serializer(),
                text
            )
        }
    } catch (_: Exception) {
        null
    }

    fun write(credentials: SavedCredentials) {
        file.parentFile?.mkdirs()
        val path = file.toPath()
        Files.deleteIfExists(path)
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (_: UnsupportedOperationException) {
            Files.createFile(path)
        }
        Files.writeString(path, json.encodeToString(SavedCredentials.serializer(), credentials))
    }

    fun delete(): Boolean = try {
        Files.deleteIfExists(file.toPath())
    } catch (_: IOException) {
        false
    }

    companion object {
        fun defaultLocation(): File = File(System.getProperty("user.home"), ".msh/mshctl-credentials.json")
    }
}
