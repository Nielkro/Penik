package niel.kro.penik.data.local.database

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * One-time migration of the legacy plaintext Room database to a SQLCipher
 * encrypted database.
 *
 * Earlier builds shipped [PenikDatabase] as an unencrypted SQLite file, so the
 * message cache and — critically — the per-epoch group keys sat in plaintext on
 * disk. This routine detects such a file and converts it in place using
 * SQLCipher's `sqlcipher_export`, preserving all existing rows. It is a no-op
 * once the database is already encrypted (or does not exist yet), so it is safe
 * to call on every startup before Room opens the database.
 */
object DatabaseEncryption {
    private const val TAG = "DatabaseEncryption"

    fun migratePlaintextIfNeeded(context: Context, databaseName: String, passphrase: ByteArray) {
        System.loadLibrary("sqlcipher")
        val dbFile: File = context.getDatabasePath(databaseName)
        if (!dbFile.exists()) return

        // A key-less open succeeds only on a plaintext database; if it throws,
        // the file is already encrypted and there is nothing to migrate.
        val isPlaintext = try {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                "",
                null,
                SQLiteDatabase.OPEN_READONLY,
                null,
            ).use { db ->
                db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            }
            true
        } catch (e: Exception) {
            false
        }

        if (!isPlaintext) return

        Log.i(TAG, "Migrating plaintext database to encrypted storage")
        val encryptedFile = File(dbFile.parent, "$databaseName.enc")
        if (encryptedFile.exists()) encryptedFile.delete()

        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            "",
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
        )
        db.use {
            // Base64 passphrase is ASCII with no single quotes, so it is safe to
            // interpolate into the ATTACH string literal.
            val key = String(passphrase, Charsets.UTF_8)
            it.rawExecSQL("ATTACH DATABASE '${encryptedFile.absolutePath}' AS encrypted KEY '$key';")
            it.rawExecSQL("SELECT sqlcipher_export('encrypted');")
            it.rawExecSQL("DETACH DATABASE encrypted;")
        }

        // Replace the plaintext file with the encrypted copy and clean up WAL/SHM.
        listOf("-wal", "-shm").forEach { suffix ->
            File(dbFile.absolutePath + suffix).takeIf { it.exists() }?.delete()
        }
        if (!dbFile.delete() || !encryptedFile.renameTo(dbFile)) {
            throw IllegalStateException("Failed to replace plaintext database with encrypted copy")
        }
        Log.i(TAG, "Database migration complete")
    }
}
