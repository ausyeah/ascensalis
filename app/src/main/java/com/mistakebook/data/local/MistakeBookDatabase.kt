package com.mistakebook.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mistakebook.data.local.entities.CaptureTask
import com.mistakebook.data.local.entities.ChatAttachment
import com.mistakebook.data.local.entities.ChatMessage
import com.mistakebook.data.local.entities.ChatSession
import com.mistakebook.data.local.entities.Notebook
import com.mistakebook.data.local.entities.PomodoroSession
import com.mistakebook.data.local.entities.PomodoroTask
import com.mistakebook.data.local.entities.Question
import com.mistakebook.data.local.entities.QuestionTagCrossRef
import com.mistakebook.data.local.entities.ReviewLog
import com.mistakebook.data.local.entities.Subject
import com.mistakebook.data.local.entities.Tag
import com.mistakebook.data.local.entities.WeeklyReport

@Database(
    entities = [
        Subject::class,
        Question::class,
        Tag::class,
        QuestionTagCrossRef::class,
        ReviewLog::class,
        CaptureTask::class,
        Notebook::class,
        ChatSession::class,
        ChatMessage::class,
        ChatAttachment::class,
        PomodoroTask::class,
        PomodoroSession::class,
        WeeklyReport::class
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MistakeBookDatabase : RoomDatabase() {

    abstract fun subjectDao(): SubjectDao

    abstract fun questionDao(): QuestionDao

    abstract fun tagDao(): TagDao

    abstract fun reviewLogDao(): ReviewLogDao

    abstract fun captureTaskDao(): CaptureTaskDao

    abstract fun notebookDao(): NotebookDao

    abstract fun chatDao(): ChatDao

    abstract fun pomodoroDao(): PomodoroDao

    abstract fun weeklyReportDao(): WeeklyReportDao

    companion object {
        const val DB_NAME = "mistake_book.db"

        /**
         * 1 → 2：新增错题本。
         *
         * **必须写 Migration，不能只加字段不改版本号。**
         * Room 开库时会核对 schema 指纹：版本号不变但表结构变了，
         * 老用户一打开就抛 IllegalStateException 直接闪退。
         * 也不能开 fallbackToDestructiveMigration——那会静默清空用户攒下的错题。
         *
         * 迁移内容与 [Notebook] / [Question.notebookId] 的定义严格对应：
         * 建表 + 建索引 + 加列。老题目的 notebookId 留 NULL，
         * 由 [com.mistakebook.data.repos.NotebookRepository.seedDefault]
         * 启动时统一归入默认错题本。
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(SQL_CREATE_NOTEBOOKS)
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notebooks_sortOrder` ON `notebooks` (`sortOrder`)"
                )
                db.execSQL("ALTER TABLE `questions` ADD COLUMN `notebookId` INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_questions_notebookId` ON `questions` (`notebookId`)"
                )
            }
        }

        /**
         * 2 -> 3：新增 AI 对话三张表。
         *
         * **必须写 Migration 并升版本号。** 只加表不改版本号的话，
         * Room 运行时会比对 schema 指纹，发现结构变了却没迁移，
         * 直接抛 IllegalStateException 闪退。
         * 更不能 `fallbackToDestructiveMigration()`——那会静默清空用户数据。
         *
         * 建表 SQL **必须与实体声明逐字对应**（列名、类型、可空性、索引名、
         * 外键 onDelete），否则 schema 校验同样会失败。改了实体就要同步改这里。
         */
        private val SQL_CREATE_NOTEBOOKS = """
            CREATE TABLE IF NOT EXISTS `notebooks` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `sortOrder` INTEGER NOT NULL,
                `isDefault` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent()

        private val SQL_CREATE_CHAT_SESSIONS = """
            CREATE TABLE IF NOT EXISTS `chat_sessions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `questionId` INTEGER,
                `title` TEXT NOT NULL,
                `titleLocked` INTEGER NOT NULL,
                `model` TEXT NOT NULL,
                `messageCount` INTEGER NOT NULL,
                `lastPreview` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `deletedAt` INTEGER
            )
        """.trimIndent()

        private val SQL_CREATE_CHAT_MESSAGES = """
            CREATE TABLE IF NOT EXISTS `chat_messages` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `role` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `errorMessage` TEXT,
                `attachmentIdsJson` TEXT NOT NULL,
                `questionId` INTEGER,
                `injected` INTEGER NOT NULL,
                `promptTokens` INTEGER NOT NULL,
                `completionTokens` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`questionId`) REFERENCES `questions`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
            )
        """.trimIndent()

        private val SQL_CREATE_CHAT_ATTACHMENTS = """
            CREATE TABLE IF NOT EXISTS `chat_attachments` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `messageId` INTEGER NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `kind` TEXT NOT NULL,
                `localPath` TEXT NOT NULL,
                `fileName` TEXT NOT NULL,
                `mimeType` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `sizeBytes` INTEGER NOT NULL,
                `widthPx` INTEGER NOT NULL,
                `heightPx` INTEGER NOT NULL,
                `textExcerpt` TEXT NOT NULL,
                `extractedChars` INTEGER NOT NULL,
                `errorMessage` TEXT,
                `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`messageId`) REFERENCES `chat_messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent()

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(SQL_CREATE_CHAT_SESSIONS)
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_sessions_questionId` ON `chat_sessions` (`questionId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_sessions_updatedAt` ON `chat_sessions` (`updatedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_sessions_deletedAt` ON `chat_sessions` (`deletedAt`)"
                )

                db.execSQL(SQL_CREATE_CHAT_MESSAGES)
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_messages_sessionId` ON `chat_messages` (`sessionId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_messages_createdAt` ON `chat_messages` (`createdAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_messages_questionId` ON `chat_messages` (`questionId`)"
                )

                db.execSQL(SQL_CREATE_CHAT_ATTACHMENTS)
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_attachments_messageId` ON `chat_attachments` (`messageId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_attachments_sessionId` ON `chat_attachments` (`sessionId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_attachments_status` ON `chat_attachments` (`status`)"
                )
            }
        }

        /**
         * 3 → 4：新增番茄钟两张表（任务 + 会话明细）。
         *
         * 建表 SQL 与实体定义**逐字对应**（列名、类型、可空性、索引名、
         * 外键动作），否则 Room 的 schema 校验会直接失败。
         * 任务表外键指向已有 `subjects`：学科复用错题本那套，不另起炉灶。
         */
        private val SQL_CREATE_POMODORO_TASKS = """
            CREATE TABLE IF NOT EXISTS `pomodoro_tasks` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `subjectId` INTEGER,
                `archived` INTEGER NOT NULL,
                `sortOrder` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`subjectId`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
            )
        """.trimIndent()

        private val SQL_CREATE_POMODORO_SESSIONS = """
            CREATE TABLE IF NOT EXISTS `pomodoro_sessions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `taskId` INTEGER NOT NULL,
                `mode` TEXT NOT NULL,
                `day` TEXT NOT NULL,
                `startedAt` INTEGER NOT NULL,
                `endedAt` INTEGER NOT NULL,
                `durationSec` INTEGER NOT NULL,
                `targetSec` INTEGER NOT NULL,
                `completed` INTEGER NOT NULL,
                FOREIGN KEY(`taskId`) REFERENCES `pomodoro_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent()

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(SQL_CREATE_POMODORO_TASKS)
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pomodoro_tasks_subjectId` ON `pomodoro_tasks` (`subjectId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pomodoro_tasks_sortOrder` ON `pomodoro_tasks` (`sortOrder`)"
                )
                db.execSQL(SQL_CREATE_POMODORO_SESSIONS)
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pomodoro_sessions_taskId` ON `pomodoro_sessions` (`taskId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pomodoro_sessions_day` ON `pomodoro_sessions` (`day`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pomodoro_sessions_startedAt` ON `pomodoro_sessions` (`startedAt`)"
                )
            }
        }

        /**
         * 4 → 5：questions 加 relatedQuestionId 列（F1 举一反三变式题）。
         *
         * 变式题记录它由哪道题生成，详情页据此展示「来源题」关联。
         * 纯加列、可空、无默认值约束：老数据该列全为 NULL，一行 SQL 完成。
         */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `questions` ADD COLUMN `relatedQuestionId` INTEGER")
            }
        }

        /**
         * 5 → 6：新增周报表（F2 AI 错因诊断周报）。
         *
         * 建表 SQL 与 [WeeklyReport] 实体逐字对应（含唯一索引名），
         * 规则与 MIGRATION_2_3 的注释一致：错一个字符 schema 校验就失败。
         */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `weekly_reports` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `weekStartEpochDay` INTEGER NOT NULL,
                        `weekEndEpochDay` INTEGER NOT NULL,
                        `markdown` TEXT NOT NULL,
                        `statsJson` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_weekly_reports_weekStartEpochDay` " +
                        "ON `weekly_reports` (`weekStartEpochDay`)"
                )
            }
        }

        fun build(context: Context): MistakeBookDatabase =
            Room.databaseBuilder(context, MistakeBookDatabase::class.java, DB_NAME)
                // 两个都**必须**注册：漏一个，Room 就找不到那条升级路径，
                // 直接抛 IllegalStateException——老用户一升级就闪退，
                // 而且是在启动瞬间崩，闪退日志里只有一句「A migration from 2 to 3 was required
                // but not found」，指向「数据库迁移」四个字，跟本文件无关，极难排查。
                //
                // 不能靠 `fallbackToDestructiveMigration` 兜底：见 MIGRATION_1_2 的注释，
                // 那会静默清空用户攒下的错题。宁可崩也不能丢数据。
                //
                // 加新版本时记得两处同步：改上面的 `version`，并在此追加 MIGRATION_n_n+1。
                // 「定义了但忘了注册」是 Room 最常见也最致命的疏漏。
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}
