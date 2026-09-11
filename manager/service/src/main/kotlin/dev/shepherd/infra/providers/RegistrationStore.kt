package dev.shepherd.infra.providers

import dev.shepherd.domain.provider.ProviderRegistration
import dev.shepherd.domain.provider.RegistrationRepository
import dev.shepherd.infra.db.ShepherdDatabase
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll

/**
 * Self-registered adapters. The secret is stored as given because the manager must present
 * it to the adapter on every call — the same trust as `secret:` in msh.yaml.
 */
object RegisteredProviders : Table("registered_providers") {
    val name = varchar("name", 100)
    val url = varchar("url", 500)
    val accessHost = varchar("access_host", 255).nullable()
    val secret = text("secret")
    val adapterType = varchar("adapter_type", 64).nullable()
    val clientId = varchar("client_id", 64)
    val clientName = varchar("client_name", 128)
    val registeredAt = timestamp("registered_at")
    val lastSeenAt = timestamp("last_seen_at")

    override val primaryKey = PrimaryKey(name)
}

class RegistrationStore(private val db: ShepherdDatabase) : RegistrationRepository {

    override suspend fun save(registration: ProviderRegistration) = db.tx {
        RegisteredProviders.deleteWhere { RegisteredProviders.name eq registration.name }
        RegisteredProviders.insert { row ->
            row[name] = registration.name
            row[url] = registration.url
            row[accessHost] = registration.accessHost
            row[secret] = registration.secret
            row[adapterType] = registration.adapterType
            row[clientId] = registration.clientId
            row[clientName] = registration.clientName
            row[registeredAt] = registration.registeredAt
            row[lastSeenAt] = registration.lastSeenAt
        }
        Unit
    }

    override suspend fun delete(name: String) = db.tx {
        RegisteredProviders.deleteWhere { RegisteredProviders.name eq name }
        Unit
    }

    override suspend fun all(): List<ProviderRegistration> = db.tx {
        RegisteredProviders.selectAll().map { row ->
            ProviderRegistration(
                name = row[RegisteredProviders.name],
                url = row[RegisteredProviders.url],
                accessHost = row[RegisteredProviders.accessHost],
                secret = row[RegisteredProviders.secret],
                adapterType = row[RegisteredProviders.adapterType],
                clientId = row[RegisteredProviders.clientId],
                clientName = row[RegisteredProviders.clientName],
                registeredAt = row[RegisteredProviders.registeredAt],
                lastSeenAt = row[RegisteredProviders.lastSeenAt]
            )
        }
    }
}
