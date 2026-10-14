package com.gmdparser.data.repository

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransactionStorageMigratorTest {

    private lateinit var plainPrefs: FakeSharedPreferences
    private lateinit var encPrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        plainPrefs = FakeSharedPreferences()
        encPrefs = FakeSharedPreferences()
    }

    @Test
    fun testSuccessfulMigrationCopiesDataVerifiesAndClearsPlain() {
        plainPrefs.putString(TransactionStorageMigrator.KEY_PENDING, "[{\"code\":\"TX1\"}]")
        plainPrefs.putString(TransactionStorageMigrator.KEY_CONFIRMED, "[{\"code\":\"TX2\"}]")
        plainPrefs.putString(TransactionStorageMigrator.KEY_MONTHLY_DASHBOARD, "{\"month\":\"September\"}")
        plainPrefs.putString(TransactionStorageMigrator.KEY_FULL_DASHBOARD, "{\"total\":5000}")

        val result = TransactionStorageMigrator.migrate(plainPrefs, encPrefs)

        assertTrue("Migration must succeed", result)
        assertTrue("KEY_MIGRATED must be true", encPrefs.getBoolean(TransactionStorageMigrator.KEY_MIGRATED, false))
        assertEquals("[{\"code\":\"TX1\"}]", encPrefs.getString(TransactionStorageMigrator.KEY_PENDING, null))
        assertEquals("[{\"code\":\"TX2\"}]", encPrefs.getString(TransactionStorageMigrator.KEY_CONFIRMED, null))
        assertEquals("{\"month\":\"September\"}", encPrefs.getString(TransactionStorageMigrator.KEY_MONTHLY_DASHBOARD, null))
        assertEquals("{\"total\":5000}", encPrefs.getString(TransactionStorageMigrator.KEY_FULL_DASHBOARD, null))

        // Legacy store must be cleanly wiped after verified encrypted write
        assertNull(plainPrefs.getString(TransactionStorageMigrator.KEY_PENDING, null))
        assertNull(plainPrefs.getString(TransactionStorageMigrator.KEY_CONFIRMED, null))
        assertNull(plainPrefs.getString(TransactionStorageMigrator.KEY_MONTHLY_DASHBOARD, null))
        assertNull(plainPrefs.getString(TransactionStorageMigrator.KEY_FULL_DASHBOARD, null))
    }

    @Test
    fun testCommitFailurePreservesLegacyPlaintextData() {
        plainPrefs.putString(TransactionStorageMigrator.KEY_PENDING, "[{\"code\":\"CRITICAL_TX\"}]")
        encPrefs.simulateCommitFailure = true

        val result = TransactionStorageMigrator.migrate(plainPrefs, encPrefs)

        assertFalse("Migration must fail if commit fails", result)
        assertFalse(encPrefs.getBoolean(TransactionStorageMigrator.KEY_MIGRATED, false))

        // CRITICAL SAFEGUARD: Legacy plain data MUST be completely preserved
        assertEquals(
            "[{\"code\":\"CRITICAL_TX\"}]",
            plainPrefs.getString(TransactionStorageMigrator.KEY_PENDING, null)
        )
    }

    @Test
    fun testVerificationMismatchPreservesLegacyPlaintextData() {
        plainPrefs.putString(TransactionStorageMigrator.KEY_PENDING, "[{\"code\":\"TX1\"}]")
        encPrefs.simulateCorruptedWrite = true

        val result = TransactionStorageMigrator.migrate(plainPrefs, encPrefs)

        assertFalse("Migration must fail if read-back verification fails", result)

        // Legacy data MUST NOT be cleared
        assertEquals(
            "[{\"code\":\"TX1\"}]",
            plainPrefs.getString(TransactionStorageMigrator.KEY_PENDING, null)
        )
    }

    @Test
    fun testRepeatLaunchIsIdempotent() {
        encPrefs.putBoolean(TransactionStorageMigrator.KEY_MIGRATED, true)
        encPrefs.putString(TransactionStorageMigrator.KEY_PENDING, "[{\"code\":\"EXISTING_ENCRYPTED\"}]")
        plainPrefs.putString(TransactionStorageMigrator.KEY_PENDING, "[{\"code\":\"OLD_LEFTOVER\"}]")

        val result = TransactionStorageMigrator.migrate(plainPrefs, encPrefs)

        assertTrue("Already migrated must immediately return true", result)
        assertEquals(
            "[{\"code\":\"EXISTING_ENCRYPTED\"}]",
            encPrefs.getString(TransactionStorageMigrator.KEY_PENDING, null)
        )
        // Does not touch plainPrefs on repeat launches
        assertEquals(
            "[{\"code\":\"OLD_LEFTOVER\"}]",
            plainPrefs.getString(TransactionStorageMigrator.KEY_PENDING, null)
        )
    }

    @Test
    fun testEmptyLegacyStorageMigratesCleanly() {
        val result = TransactionStorageMigrator.migrate(plainPrefs, encPrefs)

        assertTrue("Empty legacy storage must migrate cleanly", result)
        assertTrue(encPrefs.getBoolean(TransactionStorageMigrator.KEY_MIGRATED, false))
    }
}

/**
 * Deterministic in-memory implementation of [SharedPreferences] for unit tests.
 */
class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any>()
    var simulateCommitFailure = false
    var simulateCorruptedWrite = false

    override fun getAll(): Map<String, *> = HashMap(data)

    override fun getString(key: String, defValue: String?): String? {
        return data[key] as? String ?: defValue
    }

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        @Suppress("UNCHECKED_CAST")
        return data[key] as? Set<String> ?: defValues
    }

    override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        return data[key] as? Boolean ?: defValue
    }

    override fun contains(key: String): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    fun putString(key: String, value: String) {
        data[key] = value
    }

    fun putBoolean(key: String, value: Boolean) {
        data[key] = value
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class FakeEditor : SharedPreferences.Editor {
        private val staging = mutableMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            staging[key] = value
            return this
        }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            staging[key] = values
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            staging[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            staging[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            staging[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            staging[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            staging[key] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean {
            if (simulateCommitFailure) return false

            if (clearRequested) {
                data.clear()
            }
            for ((key, value) in staging) {
                if (value == null) {
                    data.remove(key)
                } else if (simulateCorruptedWrite && value is String) {
                    data[key] = "CORRUPTED_VALUE"
                } else {
                    data[key] = value
                }
            }
            staging.clear()
            clearRequested = false
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
