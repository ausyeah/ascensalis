package com.mistakebook.wordbook.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WordProgress::class,
        WordStudyLog::class
    ],
    version = 2,
    exportSchema = false
)
abstract class WordbookDatabase : RoomDatabase() {

    abstract fun wordbookDao(): WordbookDao

    companion object {
        const val DB_NAME = "wordbook_local.db"

        /**
         * v1 → v2：`word_progress` 增加 [WordProgress.isFavorite]（生词本手动收藏）。
         *
         * **必须写真正的 Migration，不能只靠 fallbackToDestructiveMigration**：
         * 后者会把整个库删掉重建——用户积累的学习进度、错词本、打卡流水
         * 全部清零。加列迁移只是 `ALTER TABLE ... ADD COLUMN ... DEFAULT 0`，
         * 一秒完成且无损。
         *
         * 注意**不加索引**：收藏词通常远少于全表，`observeFavorite()` 一次
         * 全表扫（几千行）完全够用；少一个索引就少一处 schema 差异，
         * 迁移面越小越不容易在国产 ROM 上翻车。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE word_progress ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun build(context: Context): WordbookDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                WordbookDatabase::class.java,
                DB_NAME
            )
                // 有匹配的 Migration 就走迁移（1→2 的加列）；
                // 万一将来出现没有覆盖到的版本号，再退化为重建，
                // 好过启动即崩。保留这行是保底，不是主路径。
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
    }
}
