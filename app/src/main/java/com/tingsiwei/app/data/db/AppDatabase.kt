package com.tingsiwei.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun byId(id: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: Long): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE status IN (:statuses)")
    suspend fun byStatuses(statuses: List<String>): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE title LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%' ORDER BY updatedAt DESC")
    fun search(q: String): Flow<List<NoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Delete
    suspend fun delete(note: NoteEntity)
}

@Dao
interface VersionDao {
    @Query("SELECT * FROM versions WHERE noteId = :noteId ORDER BY createdAt DESC")
    fun observeForNote(noteId: Long): Flow<List<VersionEntity>>

    @Query("SELECT * FROM versions WHERE id = :id")
    suspend fun byId(id: Long): VersionEntity?

    @Insert
    suspend fun insert(version: VersionEntity): Long

    @Delete
    suspend fun delete(version: VersionEntity)
}

// exportSchema=true：每次升版本在 app/schemas/ 生成 JSON 快照入库，表结构改动可 diff。
// 铁律：升 version 必须同步写 Migration 并加进 addMigrations——没有破坏性回退，
// 忘写迁移会在启动时直接抛异常（宁可崩在明处，不可静默清空用户全部笔记）。
@Database(entities = [NoteEntity::class, VersionEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun versionDao(): VersionDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tingsiwei.db")
                // 2026-10-25 移除 fallbackToDestructiveMigration：升版本不再静默清库。
                // version 仍是 1、尚无迁移；首次升版本时必须配 MIGRATION_1_2 挂到这里。
                .build()
    }
}
