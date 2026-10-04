package com.localdoc.scanner.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** AndroidX system prompt; only unlocks the app gate, not an encryption key. */
object BiometricUnlock {
    fun available(context: Context): Boolean = BiometricManager.from(context)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    fun activity(context: Context): FragmentActivity? = when (context) {
        is FragmentActivity -> context
        is ContextWrapper -> activity(context.baseContext)
        else -> null
    }
    fun prompt(activity: FragmentActivity, onSuccess: () -> Unit, onError: (String) -> Unit): BiometricPrompt {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_CANCELED) onError(errString.toString())
                }
            })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("解锁本地扫描")
            .setSubtitle("使用手机已登记的强生物识别")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("使用应用密码").build())
        return prompt
    }
}
