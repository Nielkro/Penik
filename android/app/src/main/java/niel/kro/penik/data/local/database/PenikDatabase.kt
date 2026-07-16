package niel.kro.penik.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import niel.kro.penik.data.local.dao.ChatDao
import niel.kro.penik.data.local.dao.MessageDao
import niel.kro.penik.data.local.entity.ChatEntity
import niel.kro.penik.data.local.entity.MessageEntity

@Database(
    entities = [MessageEntity::class, ChatEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PenikDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun chatDao(): ChatDao
}
