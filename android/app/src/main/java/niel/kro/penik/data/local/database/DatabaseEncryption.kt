package niel.kro.penik.data.local.database

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

import java.io.FileInputStream

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
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun migratePlaintextIfNeeded(context: Context, databaseName: String, passphrase: ByteArray) {
        try {
            System.loadLibrary("sqlcipher")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load sqlcipher native library", e)
            return
        }

        val dbFile = context.getDatabasePath(databaseName)
        if (!dbFile.exists() || dbFile.length() == 0L) {
            return
        }

        // Check if database file starts with standard unencrypted SQLite header.
        val header = ByteArray(16)
        val bytesRead = try {
            FileInputStream(dbFile).use { it.read(header) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read database header from ${dbFile.path}", e)
            return
        }

        if (bytesRead < 16 || !header.contentEquals(SQLITE_HEADER)) {
            // Already encrypted with SQLCipher or not an unencrypted SQLite database.
            return
        }

        Log.i(TAG, "Legacy plaintext database detected at ${dbFile.path}. Starting SQLCipher migration...")
        val parentDir = dbFile.parentFile ?: return
        val encryptedFile = File(parentDir, "$databaseName.encrypted")
        if (encryptedFile.exists()) {
            encryptedFile.delete()
        }

        var unencryptedDb: SQLiteDatabase? = null
        try {
            // Open the plaintext database with an empty passphrase using SQLCipher.
            unencryptedDb = SQLiteDatabase.openOrCreateDatabase(dbFile, "", null, null)

            // Flush any uncommitted WAL journals into main DB file before export.
            try {
                unencryptedDb.rawExecSQL("PRAGMA wal_checkpoint(FULL);")
            } catch (e: Exception) {
                Log.w(TAG, "WAL checkpoint warning prior to export: ${e.message}")
            }

            val escapedPath = encryptedFile.absolutePath.replace("'", "''")
            val escapedPassphrase = String(passphrase, Charsets.UTF_8).replace("'", "''")

            unencryptedDb.rawExecSQL("ATTACH DATABASE '$escapedPath' AS encrypted KEY '$escapedPassphrase';")
            unencryptedDb.rawExecSQL("SELECT sqlcipher_export('encrypted');")
            unencryptedDb.rawExecSQL("DETACH DATABASE encrypted;")
            unencryptedDb.close()
            unencryptedDb = null

            // Verify the newly created encrypted database opens cleanly with the passphrase.
            val testDb = SQLiteDatabase.openOrCreateDatabase(encryptedFile, passphrase, null, null)
            testDb.rawExecSQL("SELECT count(*) FROM sqlite_master;")
            testDb.close()

            // Delete legacy plaintext files (main db, wal, shm)
            val walFile = File(parentDir, "$databaseName-wal")
            val shmFile = File(parentDir, "$databaseName-shm")
            walFile.delete()
            shmFile.delete()

            if (dbFile.delete()) {
                if (!encryptedFile.renameTo(dbFile)) {
                    throw IllegalStateException("Failed to rename $encryptedFile to $dbFile after encryption")
                }
                Log.i(TAG, "Successfully migrated legacy database to SQLCipher.")
            } else {
                throw IllegalStateException("Failed to delete legacy plaintext database file: ${dbFile.path}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Migration to SQLCipher failed, preserving original plaintext database", e)
            if (encryptedFile.exists()) {
                encryptedFile.delete()
            }
            throw e
        } finally {
            try {
                unencryptedDb?.close()
            } catch (_: Exception) {}
        }
    }
}
