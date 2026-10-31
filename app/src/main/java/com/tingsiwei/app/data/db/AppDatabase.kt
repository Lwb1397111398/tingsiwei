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

    /** 首页轻量列表：整篇原文/导图 JSON 不进内存，只取卡片展示的列，预览在 SQL 里截 120 字 */
    @Query(
        "SELECT id, title, updatedAt, status, source, durationMs, audioPath, " +
            "substr(COALESCE(thinking, content), 1, 120) AS preview FROM notes ORDER BY updatedAt DESC"
    )
    fun observeList(): Flow<List<NoteListItem>>

    /** 搜索走 SQL LIKE，同样只取轻量列；旧的整实体版本已由它替代 */
    @Query(
        "SELECT id, title, updatedAt, status, source, durationMs, audioPath, " +
            "substr(COALESCE(thinking, content), 1, 120) AS preview FROM notes " +
            "WHERE title LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%' ORDER BY updatedAt DESC"
    )
    fun searchList(q: String): Flow<List<NoteListItem>>

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

    /** 每份快照都是整幅 mapJson 的拷贝，不封顶 versions 表会随改图/重生成无限膨胀：只留每篇最近 keep 份 */
    @Query(
        "DELETE FROM versions WHERE noteId = :noteId AND id NOT IN " +
            "(SELECT id FROM versions WHERE noteId = :noteId ORDER BY createdAt DESC, id DESC LIMIT :keep)"
    )
    suspend fun pruneTo(noteId: Long, keep: Int)
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
