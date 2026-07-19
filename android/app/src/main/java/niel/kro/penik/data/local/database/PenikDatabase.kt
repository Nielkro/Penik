package niel.kro.penik.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import niel.kro.penik.data.local.dao.ChatDao
import niel.kro.penik.data.local.dao.MessageDao
import niel.kro.penik.data.local.entity.ChatEntity
import niel.kro.penik.data.local.entity.MessageEntity

@Database(
    entities = [MessageEntity::class, ChatEntity::class],
    version = 3,
    exportSchema = false
)
abstract class PenikDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun chatDao(): ChatDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN deliveredAt INTEGER DEFAULT NULL")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN read INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
