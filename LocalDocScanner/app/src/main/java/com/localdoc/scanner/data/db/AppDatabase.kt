package com.localdoc.scanner.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DocEntity::class, PageEntity::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun docDao(): DocDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE docs ADD COLUMN coverPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pages ADD COLUMN sourcePath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pages ADD COLUMN quarterTurns INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pages ADD COLUMN cropPoints TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pages ADD COLUMN filter TEXT NOT NULL DEFAULT 'AUTO'")
                db.execSQL("ALTER TABLE pages ADD COLUMN brightness REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pages ADD COLUMN contrast REAL NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE pages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pages ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE docs SET coverPath = COALESCE((SELECT filePath FROM pages WHERE pages.docId = docs.id ORDER BY pageIndex LIMIT 1), '')")
                db.execSQL("UPDATE pages SET sourcePath = filePath WHERE sourcePath = ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN ocrText TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pages ADD COLUMN ocrMode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pages ADD COLUMN ocrUpdatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN fineRotation REAL NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "localdoc.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, object : Migration(4, 5) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE pages ADD COLUMN ocrLayout TEXT NOT NULL DEFAULT ''")
                    }
                }).build().also { instance = it }
            }
    }
}
