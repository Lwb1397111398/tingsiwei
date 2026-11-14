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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    /**
     * 首页轻量列表，带分类筛选。categoryId 语义：
     * null = 全部；-1 = 未分类（categoryId IS NULL）；>0 = 该分类。
     * 排序：具体分类内按 createdAt DESC（上课/事件时间顺序，最新在前），其余按 updatedAt DESC。
     */
    @Query(
        "SELECT id, title, updatedAt, status, source, durationMs, audioPath, categoryId, " +
            "substr(COALESCE(thinking, content), 1, 120) AS preview FROM notes " +
            "WHERE (:categoryId IS NULL OR (:categoryId = -1 AND categoryId IS NULL) OR categoryId = :categoryId) " +
            "ORDER BY CASE WHEN :categoryId > 0 THEN createdAt ELSE updatedAt END DESC"
    )
    fun observeList(categoryId: Long?): Flow<List<NoteListItem>>

    /** 搜索走 SQL LIKE，同样只取轻量列；搜索范围跟随当前分类筛选 */
    @Query(
        "SELECT id, title, updatedAt, status, source, durationMs, audioPath, categoryId, " +
            "substr(COALESCE(thinking, content), 1, 120) AS preview FROM notes " +
            "WHERE (title LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%') " +
            "AND (:categoryId IS NULL OR (:categoryId = -1 AND categoryId IS NULL) OR categoryId = :categoryId) " +
            "ORDER BY CASE WHEN :categoryId > 0 THEN createdAt ELSE updatedAt END DESC"
    )
    fun searchList(q: String, categoryId: Long?): Flow<List<NoteListItem>>

    /** 拼接分类总导图：该分类下已完成且有导图的笔记，按 createdAt 正序（最早一节在第一个分支） */
    @Query(
        "SELECT * FROM notes WHERE categoryId = :categoryId AND status = 'READY' " +
            "AND mapJson IS NOT NULL ORDER BY createdAt ASC"
    )
    suspend fun readyWithMapInCategory(categoryId: Long): List<NoteEntity>

    /** 同上的 observe 版：总导图页用，笔记生成/完成时自动重拼 */
    @Query(
        "SELECT * FROM notes WHERE categoryId = :categoryId AND status = 'READY' " +
            "AND mapJson IS NOT NULL ORDER BY createdAt ASC"
    )
    fun observeReadyWithMapInCategory(categoryId: Long): Flow<List<NoteEntity>>

    /** 该分类的 AI 优化整合产出笔记（每分类至多一篇，重复优化时覆盖） */
    @Query("SELECT * FROM notes WHERE categoryId = :categoryId AND source = 'MERGED' LIMIT 1")
    suspend fun mergedNoteInCategory(categoryId: Long): NoteEntity?

    /** 归类/改归类：不动 updatedAt（归类不算编辑内容，避免「全部」视图排序跳动） */
    @Query("UPDATE notes SET categoryId = :categoryId WHERE id = :id")
    suspend fun setCategory(id: Long, categoryId: Long?)

    /** 分类删除善后第一步：该分类 AI 整合产出随分类一起删（录音/笔记本身不受影响） */
    @Query("DELETE FROM notes WHERE categoryId = :categoryId AND source = 'MERGED'")
    suspend fun deleteMergedInCategory(categoryId: Long)

    /** 分类删除善后第二步：其余笔记回到未分类（笔记与录音文件一律保留） */
    @Query("UPDATE notes SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun detachNotesInCategory(categoryId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Delete
    suspend fun delete(note: NoteEntity)
}

@Dao
interface CategoryDao {
    /** 分类条数据：分类 + 排除总导图笔记后的成员数；按 sortOrder 排（用户可上移下移） */
    @Query(
        "SELECT c.id AS id, c.name AS name, c.sortOrder AS sortOrder, c.createdAt AS createdAt, " +
            "(SELECT COUNT(*) FROM notes n WHERE n.categoryId = c.id AND n.source != 'MERGED') AS noteCount " +
            "FROM categories c ORDER BY c.sortOrder ASC, c.createdAt ASC"
    )
    fun observeWithCount(): Flow<List<CategoryWithCount>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun byId(id: Long): CategoryEntity?

    @Query("SELECT MAX(sortOrder) FROM categories")
    suspend fun maxSortOrder(): Int?

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)
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
@Database(
    entities = [NoteEntity::class, VersionEntity::class, CategoryEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun versionDao(): VersionDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        /** v1→v2（2026-10-31）：notes 加 timings 列，播放录音时高亮跟随当前句用；旧行为 null 即无时间轴 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN timings TEXT")
            }
        }

        /** v2→v3（2026-11-07）：新表 categories + notes 加 categoryId 列（null=未分类）+ 索引 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS categories (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "sortOrder INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL("ALTER TABLE notes ADD COLUMN categoryId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_categoryId ON notes(categoryId)")
            }
        }

        /** v3→v4（2026-11-14）：notes 加 diagramJson 列，思路分段图示 JSON；旧行为 null 即回退整段思路 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN diagramJson TEXT")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tingsiwei.db")
                // 2026-10-25 移除 fallbackToDestructiveMigration：升版本不再静默清库。
                // 每次升 version 必须新增 Migration 并挂到这里（v1→v2、v2→v3、v3→v4 已挂）。
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
