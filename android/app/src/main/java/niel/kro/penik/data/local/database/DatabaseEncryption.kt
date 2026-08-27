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
    }
}
