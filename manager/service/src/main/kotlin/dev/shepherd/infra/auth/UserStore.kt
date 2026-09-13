package dev.shepherd.infra.auth

import dev.shepherd.domain.auth.ClientQuota
import dev.shepherd.domain.auth.Role
import dev.shepherd.infra.db.ShepherdDatabase
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant

object Users : Table("users") {
    val id = varchar("id", 64)
    val username = varchar("username", 128)

    /** The lower-cased username: usernames are unique regardless of case. */
    val usernameKey = varchar("username_key", 128).uniqueIndex()
    val displayName = varchar("display_name", 200).nullable()
    val email = varchar("email", 254).nullable()
    val accountSource = varchar("source", 16)
    val provider = varchar("provider", 64).nullable()
    val externalId = varchar("external_id", 512).nullable()
    val passwordHash = text("password_hash").nullable()
    val mustChangePassword = bool("must_change_password").default(false)
    val role = varchar("role", 16)
    val roleManagedByProvider = bool("role_managed_by_provider").default(false)
    val maxDevices = integer("max_devices").nullable()
    val maxSessionLifetimeSeconds = long("max_session_lifetime_seconds").nullable()
    val maxPriority = integer("max_priority").nullable()
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 128).nullable()
    val lastLoginAt = timestamp("last_login_at").nullable()
    val disabledAt = timestamp("disabled_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("users_external_identity", provider, externalId)
    }
}

enum class UserSource {
    /** Password kept by the manager. */
    LOCAL,

    /** Signed in through LDAP or Active Directory. */
    LDAP,

    /** Signed in through an OIDC provider. */
    OIDC;

    val wireName: String get() = name.lowercase()

    companion object {
        fun parse(value: String): UserSource = entries.first { source -> source.wireName == value }
    }
}

/** A person as stored, without the password hash. */
data class UserRecord(
    val id: String,
    val username: String,
    val displayName: String?,
    val email: String?,
    val source: UserSource,
    /** The OIDC provider id, or `ldap`. */
    val provider: String?,
    /** The OIDC subject or the LDAP DN. */
    val externalId: String?,
    val role: Role,
    /** Directory groups decide the role at every sign-in. */
    val roleManagedByProvider: Boolean,
    val quota: ClientQuota,
    val mustChangePassword: Boolean,
    val createdAt: Instant,
    val createdBy: String?,
    val lastLoginAt: Instant?,
    val disabledAt: Instant?
) {
    val isActive: Boolean get() = disabledAt == null
}

class UserStore(private val db: ShepherdDatabase) {

    suspend fun insert(record: UserRecord, passwordHash: String?) = db.tx {
        Users.insert { row ->
            row[id] = record.id
            row[username] = record.username
            row[usernameKey] = record.username.lowercase()
            row[displayName] = record.displayName
            row[email] = record.email
            row[accountSource] = record.source.wireName
            row[provider] = record.provider
            row[externalId] = record.externalId
            row[Users.passwordHash] = passwordHash
            row[mustChangePassword] = record.mustChangePassword
            row[role] = record.role.wireName
            row[roleManagedByProvider] = record.roleManagedByProvider
            row[maxDevices] = record.quota.maxDevices
            row[maxSessionLifetimeSeconds] = record.quota.maxSessionLifetimeSeconds
            row[maxPriority] = record.quota.maxPriority
            row[createdAt] = record.createdAt
            row[createdBy] = record.createdBy
            row[lastLoginAt] = record.lastLoginAt
            row[disabledAt] = record.disabledAt
        }
        Unit
    }

    suspend fun findById(id: String): UserRecord? = db.tx {
        Users.selectAll().where { Users.id eq id }.map(::toRecord).firstOrNull()
    }

    suspend fun findByUsername(username: String): UserRecord? = db.tx {
        Users.selectAll().where { Users.usernameKey eq username.trim().lowercase() }.map(::toRecord).firstOrNull()
    }

    suspend fun findByExternalIdentity(provider: String, externalId: String): UserRecord? = db.tx {
        Users.selectAll().where { (Users.provider eq provider) and (Users.externalId eq externalId) }.map(::toRecord).firstOrNull()
    }

    suspend fun passwordHash(id: String): String? = db.tx {
        Users.select(Users.passwordHash).where { Users.id eq id }.map { row -> row[Users.passwordHash] }.firstOrNull()
    }

    suspend fun list(includeDisabled: Boolean): List<UserRecord> = db.tx {
        val query = if (includeDisabled) Users.selectAll() else Users.selectAll().where { Users.disabledAt.isNull() }
        query.orderBy(Users.usernameKey to SortOrder.ASC).map(::toRecord)
    }

    /** Profile, role and quota; the password and the sign-in history have their own writes. */
    suspend fun update(record: UserRecord) = db.tx {
        Users.update({ Users.id eq record.id }) { row ->
            row[displayName] = record.displayName
            row[email] = record.email
            row[role] = record.role.wireName
            row[roleManagedByProvider] = record.roleManagedByProvider
            row[maxDevices] = record.quota.maxDevices
            row[maxSessionLifetimeSeconds] = record.quota.maxSessionLifetimeSeconds
            row[maxPriority] = record.quota.maxPriority
        }
        Unit
    }

    suspend fun setPassword(id: String, passwordHash: String, mustChange: Boolean) = db.tx {
        Users.update({ Users.id eq id }) { row ->
            row[Users.passwordHash] = passwordHash
            row[mustChangePassword] = mustChange
        }
        Unit
    }

    suspend fun recordLogin(id: String, at: Instant) = db.tx {
        Users.update({ Users.id eq id }) { row -> row[lastLoginAt] = at }
        Unit
    }

    /** Disables the user at [at], or enables them again with null. */
    suspend fun setDisabled(id: String, at: Instant?) = db.tx {
        Users.update({ Users.id eq id }) { row -> row[disabledAt] = at }
        Unit
    }

    suspend fun countActiveAdmins(): Long = db.tx {
        Users.selectAll().where { (Users.role eq Role.ADMIN.wireName) and Users.disabledAt.isNull() }.count()
    }

    private fun toRecord(row: ResultRow): UserRecord = UserRecord(
        id = row[Users.id],
        username = row[Users.username],
        displayName = row[Users.displayName],
        email = row[Users.email],
        source = UserSource.parse(row[Users.accountSource]),
        provider = row[Users.provider],
        externalId = row[Users.externalId],
        role = Role.parse(row[Users.role]),
        roleManagedByProvider = row[Users.roleManagedByProvider],
        quota = ClientQuota(
            maxDevices = row[Users.maxDevices],
            maxSessionLifetimeSeconds = row[Users.maxSessionLifetimeSeconds],
            maxPriority = row[Users.maxPriority]
        ),
        mustChangePassword = row[Users.mustChangePassword],
        createdAt = row[Users.createdAt],
        createdBy = row[Users.createdBy],
        lastLoginAt = row[Users.lastLoginAt],
        disabledAt = row[Users.disabledAt]
    )
}
