package app.opah.tv.data.persistence

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Version marker for structured local state; it contains no credentials or private media. */
@Entity(tableName = "local_schema_metadata")
data class LocalSchemaMetadataEntity(
    @PrimaryKey
    val key: String,
    val value: String,
)

@Dao
interface LocalSchemaMetadataDao {
    @Query("SELECT value FROM local_schema_metadata WHERE `key` = :key LIMIT 1")
    suspend fun value(key: String): String?

    @Upsert
    suspend fun upsert(entity: LocalSchemaMetadataEntity)
}

@Database(
    entities = [
        LocalSchemaMetadataEntity::class,
        PlaybackStrategyEntity::class,
        PendingPlaybackStrategyEntity::class,
        PlaybackStrategyWriteAttemptEntity::class,
        PlaybackStrategyResetBarrierEntity::class,
        PlaybackStrategyGenerationEntity::class,
        PlaybackPersistenceRecoveryObligationEntity::class,
        NotificationLedgerEntity::class,
        AlertConfigurationEntity::class,
        BriefingScopeEntity::class,
        BriefingCandidateEntity::class,
        BriefingAcknowledgementEntity::class,
        BriefingCompletedHighlightEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class OpahDatabase : RoomDatabase() {
    abstract fun localSchemaMetadata(): LocalSchemaMetadataDao

    abstract fun playbackStrategyDao(): PlaybackStrategyDao

    abstract fun playbackPersistenceRecoveryDao(): PlaybackPersistenceRecoveryDao

    abstract fun notificationLedgerDao(): NotificationLedgerDao

    abstract fun alertConfigurationDao(): AlertConfigurationDao

    abstract fun briefingDao(): BriefingDao

    companion object {
        const val FILE_NAME = "opah-local-state.db"

        fun create(context: Context): OpahDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                OpahDatabase::class.java,
                FILE_NAME,
            ).addMigrations(MIGRATION_1_2).build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `briefing_scope` (`profileKey` TEXT NOT NULL, " +
                        "`scopeKey` TEXT NOT NULL, `lastSuccessfulQueryAtEpochMillis` INTEGER NOT NULL, " +
                        "`lowerBoundEpochMillis` INTEGER NOT NULL, `upperBoundEpochMillis` INTEGER NOT NULL, " +
                        "`capped` INTEGER NOT NULL, `privacySchemaVersion` INTEGER NOT NULL, " +
                        "`privacyEpoch` INTEGER NOT NULL, PRIMARY KEY(`profileKey`, `scopeKey`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `briefing_candidate` (`profileKey` TEXT NOT NULL, " +
                        "`scopeKey` TEXT NOT NULL, `reviewId` TEXT NOT NULL, `cameraId` TEXT NOT NULL, " +
                        "`startEpochMillis` INTEGER NOT NULL, `endEpochMillis` INTEGER, `severity` TEXT NOT NULL, " +
                        "`rawSeverity` TEXT, `objectsJson` TEXT NOT NULL, `zonesJson` TEXT NOT NULL, " +
                        "`audioJson` TEXT NOT NULL, `detectionIdsJson` TEXT NOT NULL, `subLabelsJson` TEXT NOT NULL, " +
                        "`reviewed` INTEGER NOT NULL, `summaryTitle` TEXT, `summaryShort` TEXT, `threatLevel` INTEGER, " +
                        "`concernsJson` TEXT NOT NULL, `contentVersion` TEXT NOT NULL, " +
                        "PRIMARY KEY(`profileKey`, `scopeKey`, `reviewId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_briefing_candidate_profileKey_scopeKey_startEpochMillis` " +
                        "ON `briefing_candidate` (`profileKey`, `scopeKey`, `startEpochMillis`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `briefing_acknowledgement` (`profileKey` TEXT NOT NULL, " +
                        "`scopeKey` TEXT NOT NULL, `reviewId` TEXT NOT NULL, `contentVersion` TEXT NOT NULL, " +
                        "`reason` TEXT NOT NULL, `acknowledgedAtEpochMillis` INTEGER NOT NULL, " +
                        "`retainUntilEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`profileKey`, `scopeKey`, `reviewId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_briefing_acknowledgement_profileKey_scopeKey_retainUntilEpochMillis` " +
                        "ON `briefing_acknowledgement` (`profileKey`, `scopeKey`, `retainUntilEpochMillis`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `briefing_completed_highlight` (`profileKey` TEXT NOT NULL, " +
                        "`scopeKey` TEXT NOT NULL, `reviewId` TEXT NOT NULL, `completedAtEpochMillis` INTEGER NOT NULL, " +
                        "`retainUntilEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`profileKey`, `scopeKey`, `reviewId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_briefing_completed_highlight_profileKey_scopeKey_retainUntilEpochMillis` " +
                        "ON `briefing_completed_highlight` (`profileKey`, `scopeKey`, `retainUntilEpochMillis`)",
                )
            }
        }
    }
}
