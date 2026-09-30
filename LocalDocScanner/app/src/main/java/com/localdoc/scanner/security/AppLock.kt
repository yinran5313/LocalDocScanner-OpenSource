package com.localdoc.scanner.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object AppLock {
    private const val PREFS = "app_lock"
    private const val SALT = "salt"
    private const val HASH = "hash"
    private const val ITERATIONS = 120_000

    fun enabled(context: Context): Boolean = prefs(context).contains(HASH)

    fun setPin(context: Context, pin: String) {
        require(pin.matches(Regex("\\d{4,12}"))) { "密码需为4到12位数字" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt)
        prefs(context).edit()
            .putString(SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    fun verify(context: Context, pin: String): Boolean {
        val salt = prefs(context).getString(SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val expected = prefs(context).getString(HASH, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val actual = derive(pin, salt)
        if (actual.size != expected.size) return false
        var different = 0
        actual.indices.forEach { different = different or (actual[it].toInt() xor expected[it].toInt()) }
        return different == 0
    }

    fun disable(context: Context, pin: String): Boolean {
        if (!verify(context, pin)) return false
        prefs(context).edit().clear().apply()
        return true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.also { spec.clearPassword() }
    }
}
