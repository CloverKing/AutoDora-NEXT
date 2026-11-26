package com.autodora.next.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "team_presets")
data class TeamPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val ruleConfigJson: String,
    val isBuiltIn: Boolean = false,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "collected_training_samples")
data class TrainingSampleEntity(
    @PrimaryKey val id: String,
    val domain: String,
    val priority: Int,
    val perceptualHash: Long,
    val primaryImageRelPath: String,
    val secondaryImageRelPath: String?,
    val fullViewportRelPath: String?,
    val metadataRelPath: String,
    val tagsCsv: String,
    val totalBytes: Long,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "captured_enemy_skills")
data class CapturedEnemySkillEntity(
    @PrimaryKey val skillHash: String,
    val titleZhTw: String,
    val rawBodyZhTw: String,
    val sourceType: String,
    val stageName: String,
    val waveIndex: Int,
    val parseStatus: String,
    val mappedChipsJson: String,
    val hitCount: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long
)

@Entity(tableName = "stage_wave_scripts")
data class StageWaveScriptEntity(
    @PrimaryKey val stageKey: String,
    val stageName: String,
    val wavesJson: String,
    val sourceType: String,
    val updatedAtMs: Long
)

@Dao
interface TeamPresetDao {
    @Query("SELECT * FROM team_presets ORDER BY isBuiltIn DESC, updatedAtMs DESC")
    fun observeAll(): Flow<List<TeamPresetEntity>>

    @Query("SELECT * FROM team_presets WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TeamPresetEntity?

    @Query("SELECT COUNT(*) FROM team_presets")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TeamPresetEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entities: List<TeamPresetEntity>)

    @Query("DELETE FROM team_presets WHERE id = :id AND id != 'builtin_general'")
    suspend fun deleteCustom(id: String)

    @Query("DELETE FROM team_presets WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("UPDATE team_presets SET name = :newName WHERE id = :id AND name = :oldName")
    suspend fun renameIfExact(id: String, oldName: String, newName: String)
}

@Dao
interface TrainingSampleDao {
    @Query("SELECT COUNT(*) FROM collected_training_samples")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM collected_training_samples WHERE domain IN ('BOARD_SOLVE', 'BOARD_EDITOR_CORRECTION', 'BOARD_AUTO_IDLE')")
    fun observeBoardCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM collected_training_samples WHERE domain = 'BOARD_EDITOR_CORRECTION'")
    fun observeEditorCorrectionCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM collected_training_samples WHERE domain IN ('ENEMY_POPUP_LONG_PRESS', 'STAGE_INFO_MODAL')")
    fun observeEnemyModalCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(totalBytes), 0) FROM collected_training_samples")
    fun observeTotalBytes(): Flow<Long>

    @Query("SELECT * FROM collected_training_samples ORDER BY createdAtMs ASC")
    suspend fun getAllOrdered(): List<TrainingSampleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TrainingSampleEntity)

    @Query("DELETE FROM collected_training_samples")
    suspend fun deleteAll()
}

@Dao
interface CapturedEnemySkillDao {
    @Query("SELECT COUNT(*) FROM captured_enemy_skills")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM captured_enemy_skills WHERE skillHash = :hash LIMIT 1")
    suspend fun getByHash(hash: String): CapturedEnemySkillEntity?

    @Query("SELECT * FROM captured_enemy_skills ORDER BY lastSeenMs DESC")
    fun observeAll(): Flow<List<CapturedEnemySkillEntity>>

    @Query("SELECT * FROM captured_enemy_skills ORDER BY lastSeenMs DESC")
    suspend fun getAllOrdered(): List<CapturedEnemySkillEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CapturedEnemySkillEntity)

    @Query("DELETE FROM captured_enemy_skills")
    suspend fun deleteAll()
}

@Dao
interface StageWaveScriptDao {
    @Query("SELECT * FROM stage_wave_scripts ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<StageWaveScriptEntity>>

    @Query("SELECT COUNT(*) FROM stage_wave_scripts")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM stage_wave_scripts WHERE stageKey = :stageKey LIMIT 1")
    suspend fun getByKey(stageKey: String): StageWaveScriptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StageWaveScriptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<StageWaveScriptEntity>)

    @Query("DELETE FROM stage_wave_scripts WHERE stageKey = :stageKey")
    suspend fun deleteByKey(stageKey: String)

    @Query("DELETE FROM stage_wave_scripts")
    suspend fun deleteAll()
}

@Database(
    entities = [
        TeamPresetEntity::class,
        TrainingSampleEntity::class,
        CapturedEnemySkillEntity::class,
        StageWaveScriptEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AutoDoraDatabase : RoomDatabase() {
    abstract fun teamPresetDao(): TeamPresetDao
    abstract fun trainingSampleDao(): TrainingSampleDao
    abstract fun capturedEnemySkillDao(): CapturedEnemySkillDao
    abstract fun stageWaveScriptDao(): StageWaveScriptDao

    companion object {
        @Volatile
        private var instance: AutoDoraDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `collected_training_samples` (
                        `id` TEXT NOT NULL,
                        `domain` TEXT NOT NULL,
                        `priority` INTEGER NOT NULL,
                        `perceptualHash` INTEGER NOT NULL,
                        `primaryImageRelPath` TEXT NOT NULL,
                        `secondaryImageRelPath` TEXT,
                        `fullViewportRelPath` TEXT,
                        `metadataRelPath` TEXT NOT NULL,
                        `tagsCsv` TEXT NOT NULL,
                        `totalBytes` INTEGER NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `captured_enemy_skills` (
                        `skillHash` TEXT NOT NULL,
                        `titleZhTw` TEXT NOT NULL,
                        `rawBodyZhTw` TEXT NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `stageName` TEXT NOT NULL,
                        `waveIndex` INTEGER NOT NULL,
                        `parseStatus` TEXT NOT NULL,
                        `mappedChipsJson` TEXT NOT NULL,
                        `hitCount` INTEGER NOT NULL,
                        `firstSeenMs` INTEGER NOT NULL,
                        `lastSeenMs` INTEGER NOT NULL,
                        PRIMARY KEY(`skillHash`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `stage_wave_scripts` (
                        `stageKey` TEXT NOT NULL,
                        `stageName` TEXT NOT NULL,
                        `wavesJson` TEXT NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`stageKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AutoDoraDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AutoDoraDatabase::class.java,
                    "autodora_next.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
