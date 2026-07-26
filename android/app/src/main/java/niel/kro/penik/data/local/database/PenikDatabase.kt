package niel.kro.penik.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import niel.kro.penik.data.local.dao.ChatDao
import niel.kro.penik.data.local.dao.GroupDao
import niel.kro.penik.data.local.dao.MessageDao
import niel.kro.penik.data.local.entity.ChatEntity
import niel.kro.penik.data.local.entity.GroupEntity
import niel.kro.penik.data.local.entity.GroupKeyEntity
import niel.kro.penik.data.local.entity.GroupMemberEntity
import niel.kro.penik.data.local.entity.GroupMessageEntity
import niel.kro.penik.data.local.entity.MessageEntity

@Database(
    entities = [
        MessageEntity::class, ChatEntity::class,
        GroupEntity::class, GroupMemberEntity::class,
        GroupKeyEntity::class, GroupMessageEntity::class,
    ],
    version = 7,
    exportSchema = false
)
abstract class PenikDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun chatDao(): ChatDao
    abstract fun groupDao(): GroupDao

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
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS groups (" +
                        "id INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL, ownerUserId INTEGER NOT NULL, " +
                        "role TEXT, membershipVersion INTEGER NOT NULL DEFAULT 1, " +
                        "currentKeyVersion INTEGER NOT NULL DEFAULT 1, createdAt INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS group_members (" +
                        "groupId INTEGER NOT NULL, userId INTEGER NOT NULL, role TEXT NOT NULL, " +
                        "status TEXT NOT NULL, joinedAt INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(groupId, userId))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS group_keys (" +
                        "groupId INTEGER NOT NULL, keyVersion INTEGER NOT NULL, key BLOB NOT NULL, " +
                        "PRIMARY KEY(groupId, keyVersion))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS group_messages (" +
                        "groupId INTEGER NOT NULL, messageId TEXT NOT NULL, serverId INTEGER NOT NULL DEFAULT 0, " +
                        "senderUserId INTEGER NOT NULL, senderDeviceId INTEGER NOT NULL DEFAULT 0, " +
                        "keyVersion INTEGER NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                        "sentByMe INTEGER NOT NULL DEFAULT 0, delivered INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(groupId, messageId))"
                )
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE group_members ADD COLUMN name TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE group_members ADD COLUMN nickname TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Track our own membership status so pending invitations can be
                // surfaced with accept/decline actions in the group list.
                db.execSQL("ALTER TABLE groups ADD COLUMN status TEXT NOT NULL DEFAULT 'active'")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE group_members ADD COLUMN online INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE group_members ADD COLUMN lastSeen INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
