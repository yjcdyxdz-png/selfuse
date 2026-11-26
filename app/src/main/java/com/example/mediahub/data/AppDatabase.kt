package com.example.mediahub.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY kind, name")
    fun observeAll(): Flow<List<RuleEntity>>
    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun get(id: String): RuleEntity?
    @Upsert suspend fun upsert(rule: RuleEntity)
    @Upsert suspend fun upsertAll(rules: List<RuleEntity>)
    @Query("UPDATE rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: String)
}

class Converters {
    @TypeConverter fun fromKind(k: RuleKind) = k.name
    @TypeConverter fun toKind(s: String) = RuleKind.valueOf(s)
}

@Database(entities = [RuleEntity::class, ShelfItem::class, Progress::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao
    abstract fun libraryDao(): LibraryDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `shelf` (`key` TEXT NOT NULL, `ruleId` TEXT NOT NULL, " +
                    "`itemId` TEXT NOT NULL, `contentType` TEXT NOT NULL, `title` TEXT NOT NULL, `cover` TEXT NOT NULL, " +
                    "`author` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `progress` (`key` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, " +
                    "`chapterTitle` TEXT NOT NULL, `position` INTEGER NOT NULL, `extra` TEXT NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            }
        }

        fun build(ctx: Context) =
            Room.databaseBuilder(ctx, AppDatabase::class.java, "mediahub.db")
                .addMigrations(MIGRATION_1_2).build()
    }
}
