package com.gmdparser.data.repository

import android.content.SharedPreferences
import android.util.Log

object TransactionStorageMigrator {
    private const val TAG = "TxStorageMigrator"

    const val KEY_PENDING = "pending_transactions"
    const val KEY_CONFIRMED = "confirmed_transactions"
    const val KEY_MONTHLY_DASHBOARD = "cached_monthly_dashboard"
    const val KEY_FULL_DASHBOARD = "cached_full_dashboard"
    const val KEY_MIGRATED = "migrated_to_encrypted"

    val KEYS_TO_MIGRATE = listOf(
        KEY_PENDING,
        KEY_CONFIRMED,
        KEY_MONTHLY_DASHBOARD,
        KEY_FULL_DASHBOARD
    )

    /**
     * Performs a verified, failure-safe migration from unencrypted SharedPreferences to EncryptedSharedPreferences.
     *
     * Safeguards:
     * 1. If already migrated, returns true immediately (idempotent).
     * 2. Reads existing data from [plainPrefs].
     * 3. Writes all non-null values to [encPrefs] with KEY_MIGRATED = true and performs synchronous commit().
     * 4. Verifies that commit() succeeded AND all migrated keys read back matching values.
     * 5. Only if 100% verified, performs synchronous clear() on [plainPrefs].
     * 6. If cleanup commit fails, logs an explicit warning without corrupting or losing data.
     * 7. If encrypted write or verification fails, [plainPrefs] is left untouched so old data is preserved.
     */
    private fun logE(msg: String, e: Throwable? = null) {
        runCatching { Log.e(TAG, msg, e) }
    }

    private fun logW(msg: String) {
        runCatching { Log.w(TAG, msg) }
    }

    private fun logI(msg: String) {
        runCatching { Log.i(TAG, msg) }
    }

    fun migrate(plainPrefs: SharedPreferences, encPrefs: SharedPreferences): Boolean {
        // 1. Check idempotency
        try {
            if (encPrefs.getBoolean(KEY_MIGRATED, false)) {
                return true
            }
        } catch (e: Exception) {
            logE("Failed to read migration status from encrypted preferences", e)
            return false
        }

        // 2. Read values from legacy plaintext store
        val snapshot = mutableMapOf<String, String>()
        for (key in KEYS_TO_MIGRATE) {
            try {
                val value = plainPrefs.getString(key, null)
                if (!value.isNullOrBlank()) {
                    snapshot[key] = value
                }
            } catch (e: Exception) {
                logE("Failed to read key '$key' from plain preferences", e)
                return false
            }
        }

        // 3. Write snapshot and migration flag to encrypted store
        try {
            val editor = encPrefs.edit()
            for ((key, value) in snapshot) {
                editor.putString(key, value)
            }
            editor.putBoolean(KEY_MIGRATED, true)

            val commitSuccess = editor.commit()
            if (!commitSuccess) {
                logE("Failed to commit encrypted preferences during migration. Preserving legacy data.")
                return false
            }

            // 4. Verify all migrated values match in encrypted preferences
            if (!encPrefs.getBoolean(KEY_MIGRATED, false)) {
                logE("Verification failed: KEY_MIGRATED is not true after commit. Preserving legacy data.")
                return false
            }

            for ((key, expectedValue) in snapshot) {
                val readBack = encPrefs.getString(key, null)
                if (readBack != expectedValue) {
                    logE("Verification failed: Value mismatch for key '$key'. Preserving legacy data.")
                    return false
                }
            }

            // 5. Only now clear legacy plaintext preferences with synchronous commit
            val cleanupSuccess = plainPrefs.edit().clear().commit()
            if (!cleanupSuccess) {
                logW("Plain preferences cleanup commit() returned false. Encrypted data is safe, but legacy file cleanup failed.")
            } else {
                logI("Successfully migrated and cleared legacy plaintext transaction repository storage.")
            }

            return true
        } catch (e: Exception) {
            logE("Unexpected error during storage migration. Preserving legacy data.", e)
            return false
        }
    }
}
