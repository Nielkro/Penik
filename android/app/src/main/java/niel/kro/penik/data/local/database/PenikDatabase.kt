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
    version = 11,
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
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToMsgId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN replyToMsgId TEXT DEFAULT NULL")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN editedAt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN editedAt INTEGER DEFAULT NULL")
            }
        }
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chats ADD COLUMN isE2EE INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE groups ADD COLUMN isE2EE INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE messages ADD COLUMN isE2EE INTEGER NOT NULL DEFAULT 1")
            }
        }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE groups ADD COLUMN is_archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE groups SET is_archived = 1 WHERE isE2EE = 1")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS chats_new (
                        userId INTEGER NOT NULL,
                        nickname TEXT NOT NULL,
                        name TEXT NOT NULL,
                        avatarUrl TEXT,
                        lastMessage TEXT,
                        lastMessageTimestamp INTEGER,
                        unreadCount INTEGER NOT NULL,
                        isE2EE INTEGER NOT NULL DEFAULT 0,
                        is_archived INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(userId, isE2EE)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT OR IGNORE INTO chats_new(userId, nickname, name, avatarUrl, lastMessage, lastMessageTimestamp, unreadCount, isE2EE, is_archived)
                    SELECT userId, nickname, name, avatarUrl, lastMessage, lastMessageTimestamp, unreadCount, COALESCE(isE2EE, 0), CASE WHEN COALESCE(isE2EE, 0) = 1 THEN 1 ELSE 0 END
                    FROM chats
                """.trimIndent())
                db.execSQL("DROP TABLE chats")
                db.execSQL("ALTER TABLE chats_new RENAME TO chats")
            }
        }
    }
}
