package com.gmdparser.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralised persistent app preferences (PIN lock + theme).
 * Safe to call from any thread; all reads/writes are immediate (no apply() lag).
 */
object AppPreferences {

    private const val PREFS_NAME = "gmdparser_app_prefs"
    private const val KEY_PIN_HASH   = "pin_hash"
    private const val KEY_THEME      = "selected_theme"
    private const val KEY_PIN_ENABLED = "pin_enabled"
    private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    private const val KEY_AUTH_TYPE  = "auth_type"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    // ── PIN & Biometrics ──────────────────────────────────────────────────

    /** Returns true when PIN/Password lock has been set and is enabled. */
    val isPinEnabled: Boolean
        get() = prefs?.getBoolean(KEY_PIN_ENABLED, false) ?: false

    /** Returns true when Biometric authentication is enabled. */
    var isBiometricEnabled: Boolean
        get() = prefs?.getBoolean(KEY_BIOMETRIC_ENABLED, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_BIOMETRIC_ENABLED, value)?.apply()
        }

    /** Returns 'PIN' or 'PASSWORD'. */
    var authType: String
        get() = prefs?.getString(KEY_AUTH_TYPE, "PIN") ?: "PIN"
        set(value) {
            prefs?.edit()?.putString(KEY_AUTH_TYPE, value)?.apply()
        }

    /** Returns the stored SHA-256 hash of the PIN/Password, or null if not set. */
    val storedPinHash: String?
        get() = prefs?.getString(KEY_PIN_HASH, null)

    /** Saves the SHA-256 hash of a new PIN/Password and enables the lock. */
    fun savePin(pinHash: String, type: String = "PIN") {
        prefs?.edit()
            ?.putString(KEY_PIN_HASH, pinHash)
            ?.putString(KEY_AUTH_TYPE, type)
            ?.putBoolean(KEY_PIN_ENABLED, true)
            ?.commit()
    }

    /** Disables PIN protection and clears the stored hash. */
    fun clearPin() {
        prefs?.edit()
            ?.remove(KEY_PIN_HASH)
            ?.putBoolean(KEY_PIN_ENABLED, false)
            ?.commit()
    }

    // ── Theme ─────────────────────────────────────────────────────────────

    /** The persisted theme choice; defaults to DARK. */
    var selectedTheme: AppTheme
        get() {
            val name = prefs?.getString(KEY_THEME, AppTheme.DARK.name) ?: AppTheme.DARK.name
            return runCatching { AppTheme.valueOf(name) }.getOrDefault(AppTheme.DARK)
        }
        set(value) {
            prefs?.edit()?.putString(KEY_THEME, value.name)?.apply()
        }
}

enum class AppTheme(val label: String) {
    DARK("Dark (Default)"),
    MIDNIGHT("Midnight Blue"),
    FOREST("Forest Green"),
    LIGHT("Light Mode")
}
