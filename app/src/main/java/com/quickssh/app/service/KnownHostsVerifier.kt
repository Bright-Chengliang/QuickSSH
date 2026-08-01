package com.quickssh.app.service

import android.content.Context
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey

class KnownHostsVerifier(context: Context) : HostKeyVerifier {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val settingsPrefs = appContext.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val fingerprint = fingerprint(key)
        val keyName = preferenceKey(hostname, port)
        val acceptedFingerprint = prefs.getString(acceptedKey(keyName), null)
        val strictMode = settingsPrefs.getBoolean(KEY_STRICT_HOST_KEY_VERIFICATION, false)
        return when (hostKeyVerificationDecision(acceptedFingerprint, fingerprint, strictMode)) {
            HostKeyVerificationDecision.ACCEPT_AND_STORE -> {
                prefs.edit()
                    .putString(acceptedKey(keyName), fingerprint)
                    .remove(pendingKey(keyName))
                    .apply()
                true
            }
            HostKeyVerificationDecision.ACCEPT_KNOWN -> {
                prefs.edit().remove(pendingKey(keyName)).apply()
                true
            }
            HostKeyVerificationDecision.REJECT_AND_STORE_PENDING -> {
                prefs.edit()
                    .putString(pendingKey(keyName), fingerprint)
                    .apply()
                false
            }
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        return emptyList()
    }

    companion object {
        private const val PREFS_NAME = "quickssh_known_hosts"
        const val SETTINGS_PREFS_NAME = "quickssh_settings"
        const val KEY_STRICT_HOST_KEY_VERIFICATION = "strict_host_key_verification"
        private const val ACCEPTED_PREFIX = "accepted:"
        private const val PENDING_PREFIX = "pending:"

        fun fingerprint(key: PublicKey): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded ?: byteArrayOf())
            return digest.joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        fun pendingFingerprint(context: Context, hostname: String, port: Int): String? {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(pendingKey(preferenceKey(hostname, port)), null)
        }

        fun acceptPendingHost(context: Context, hostname: String, port: Int): Boolean {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val keyName = preferenceKey(hostname, port)
            val pending = prefs.getString(pendingKey(keyName), null) ?: return false
            prefs.edit()
                .putString(acceptedKey(keyName), pending)
                .remove(pendingKey(keyName))
                .apply()
            return true
        }

        fun acceptedFingerprint(context: Context, hostname: String, port: Int): String? {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(acceptedKey(preferenceKey(hostname, port)), null)
        }

        internal fun preferenceKey(hostname: String, port: Int): String {
            return "${hostname.trim().lowercase()}:$port"
        }

        internal fun acceptedKey(keyName: String): String = ACCEPTED_PREFIX + keyName

        internal fun pendingKey(keyName: String): String = PENDING_PREFIX + keyName
    }
}

internal enum class HostKeyVerificationDecision {
    ACCEPT_AND_STORE,
    ACCEPT_KNOWN,
    REJECT_AND_STORE_PENDING
}

internal fun hostKeyVerificationDecision(
    acceptedFingerprint: String?,
    observedFingerprint: String,
    strictMode: Boolean
): HostKeyVerificationDecision {
    if (!strictMode) return HostKeyVerificationDecision.ACCEPT_AND_STORE
    return if (acceptedFingerprint == observedFingerprint) {
        HostKeyVerificationDecision.ACCEPT_KNOWN
    } else {
        HostKeyVerificationDecision.REJECT_AND_STORE_PENDING
    }
}
