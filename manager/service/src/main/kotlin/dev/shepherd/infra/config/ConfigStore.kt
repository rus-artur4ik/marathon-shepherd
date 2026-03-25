package dev.shepherd.infra.config

import com.charleskorn.kaml.Yaml
import dev.shepherd.domain.model.ShepherdConfig
import org.slf4j.LoggerFactory
import java.io.File

class ConfigStore(private val configPath: String) {
    private val logger = LoggerFactory.getLogger(ConfigStore::class.java)
    private val lock = Any()

    private var cachedConfig: ShepherdConfig? = null
    private var lastModified: Long = 0

    fun loadConfig(): ShepherdConfig {
        synchronized(lock) {
            val file = File(configPath)
            if (!file.exists()) {
                throw IllegalStateException("Config file not found: $configPath")
            }

            val currentModified = file.lastModified()
            val cached = cachedConfig
            if (cached != null && currentModified == lastModified) {
                return cached
            }

            logger.info("Loading config from $configPath")
            val content = file.readText()
            val config = Yaml.default.decodeFromString(ShepherdConfig.serializer(), content)

            cachedConfig = config
            lastModified = currentModified
            return config
        }
    }

    fun updateConfig(config: ShepherdConfig) {
        synchronized(lock) {
            val file = File(configPath)
            try {
                val content = Yaml.default.encodeToString(ShepherdConfig.serializer(), config)
                file.writeText(content)
                lastModified = file.lastModified()
                logger.info("Config updated at $configPath")
            } catch (e: java.io.IOException) {
                logger.warn(
                    "Config file '{}' is not writable — change applied in-memory only: {}",
                    configPath, e.message
                )
            }
            cachedConfig = config
        }
    }

    fun reloadConfig(): ShepherdConfig {
        synchronized(lock) {
            cachedConfig = null
            lastModified = 0
            return loadConfig()
        }
    }
}
