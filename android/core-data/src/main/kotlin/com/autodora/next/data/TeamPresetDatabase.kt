package com.autodora.next.data

import android.content.Context
import androidx.room.*
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

    @Query("DELETE FROM team_presets WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteCustom(id: String)
}

@Database(
    entities = [TeamPresetEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AutoDoraDatabase : RoomDatabase() {
    abstract fun teamPresetDao(): TeamPresetDao

    companion object {
        @Volatile
        private var instance: AutoDoraDatabase? = null

        fun getInstance(context: Context): AutoDoraDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AutoDoraDatabase::class.java,
                    "autodora_next.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}
