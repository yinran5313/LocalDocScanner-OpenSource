package com.localdoc.scanner.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun LockGate(onUnlocked: () -> Unit = {}, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var unlocked by remember { mutableStateOf(!AppLock.enabled(context)) }
    val activity = BiometricUnlock.activity(context)

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> activity?.let { com.localdoc.scanner.office.HostLockSession.leave(it) }
                Lifecycle.Event.ON_START -> {
                    if (AppLock.enabled(context) && com.localdoc.scanner.office.HostLockSession.needsUnlock(context)) unlocked = false
                    else if (unlocked) activity?.let { com.localdoc.scanner.office.HostLockSession.enter(it) }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    if (unlocked || !AppLock.enabled(context)) content() else LockScreen(
        onBiometricUnlock = {
            com.localdoc.scanner.office.HostLockSession.unlock(context); unlocked = true
            activity?.let { com.localdoc.scanner.office.HostLockSession.enter(it) }; onUnlocked()
        },
        onUnlock = { pin -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { AppLock.verify(context, pin) }.also {
            if (it) { com.localdoc.scanner.office.HostLockSession.unlock(context); unlocked = true
                activity?.let { current -> com.localdoc.scanner.office.HostLockSession.enter(current) }; onUnlocked() }
        } })
}

@Composable
private fun LockScreen(onBiometricUnlock: () -> Unit, onUnlock: suspend (String) -> Boolean) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var biometricError by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf<androidx.biometric.BiometricPrompt?>(null) }
    DisposableEffect(Unit) { onDispose { prompt?.cancelAuthentication() } }
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("本地扫描已锁定", style = MaterialTheme.typography.headlineSmall)
        Text("输入应用密码后继续", modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(12); error = false },
            label = { Text("4–12位数字密码") },
            visualTransformation = PasswordVisualTransformation(),
            isError = error,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (error) Text("密码不正确", color = MaterialTheme.colorScheme.error)
        Button(
            onClick = { busy = true; scope.launch { try { if (!onUnlock(pin)) error = true } finally { busy = false } } },
            enabled = !busy && pin.length >= 4,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) { Text("解锁") }
        if (AppLock.biometricEnabled(context) && BiometricUnlock.available(context)) {
            Button(onClick = {
                BiometricUnlock.activity(context)?.let { activity ->
                    prompt = BiometricUnlock.prompt(activity, onBiometricUnlock) { biometricError = it }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("指纹 / 生物识别解锁") }
        }
        if (biometricError.isNotBlank()) Text(biometricError, color = MaterialTheme.colorScheme.error)
    }
}
