package dev.shepherd.infra.devices

import dev.shepherd.domain.devices.MaintenanceInfo
import dev.shepherd.domain.devices.MaintenanceRegistry
import dev.shepherd.infra.db.ShepherdDatabase
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll

object DeviceMaintenance : Table("device_maintenance") {
    val deviceId = varchar("device_id", 300)
    val reason = text("reason").nullable()
    val setBy = varchar("set_by", 128)
    val setAt = timestamp("set_at")

    override val primaryKey = PrimaryKey(deviceId)
}

class MaintenanceStore(private val db: ShepherdDatabase) : MaintenanceRegistry {

    override suspend fun all(): Map<String, MaintenanceInfo> = db.tx {
        DeviceMaintenance.selectAll().associate { row ->
            row[DeviceMaintenance.deviceId] to MaintenanceInfo(
                reason = row[DeviceMaintenance.reason],
                setBy = row[DeviceMaintenance.setBy],
                setAt = row[DeviceMaintenance.setAt]
            )
        }
    }

    override suspend fun set(deviceId: String, info: MaintenanceInfo) = db.tx {
        DeviceMaintenance.deleteWhere { DeviceMaintenance.deviceId eq deviceId }
        DeviceMaintenance.insert { row ->
            row[DeviceMaintenance.deviceId] = deviceId
            row[reason] = info.reason
            row[setBy] = info.setBy
            row[setAt] = info.setAt
        }
        Unit
    }

    override suspend fun clear(deviceId: String): Boolean = db.tx {
        DeviceMaintenance.deleteWhere { DeviceMaintenance.deviceId eq deviceId } > 0
    }
}
