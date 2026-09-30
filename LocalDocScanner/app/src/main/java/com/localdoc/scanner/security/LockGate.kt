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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun LockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var unlocked by remember { mutableStateOf(!AppLock.enabled(context)) }
    var backgroundAt by remember { mutableLongStateOf(0L) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> backgroundAt = System.currentTimeMillis()
                Lifecycle.Event.ON_START -> {
                    if (AppLock.enabled(context) && backgroundAt > 0L && System.currentTimeMillis() - backgroundAt >= 30_000L) {
                        unlocked = false
                    }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    if (unlocked || !AppLock.enabled(context)) content() else LockScreen { pin ->
        AppLock.verify(context, pin).also { if (it) unlocked = true }
    }
}

@Composable
private fun LockScreen(onUnlock: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
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
            onClick = { if (!onUnlock(pin)) error = true },
            enabled = pin.length >= 4,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) { Text("解锁") }
    }
}
