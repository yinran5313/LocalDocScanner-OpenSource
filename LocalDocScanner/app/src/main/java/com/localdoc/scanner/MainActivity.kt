package com.localdoc.scanner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.localdoc.scanner.office.HostLockSession
import android.os.Bundle
import android.content.Intent
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.localdoc.scanner.ui.nav.AppNav
import com.localdoc.scanner.ui.theme.LocalDocScannerTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.localdoc.scanner.external.ExternalOpenBus
import com.localdoc.scanner.security.LockGate

class MainActivity : FragmentActivity() {
    private var unlockOnly by androidx.compose.runtime.mutableStateOf(false)
    private var unlockSerial by androidx.compose.runtime.mutableIntStateOf(0)
    private fun unlockReturned() { setResult(RESULT_OK); finish() }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HostLockSession.configure(this, com.localdoc.scanner.security.AppLock.enabled(this))
        unlockOnly = intent.getBooleanExtra(HostLockSession.UNLOCK_ONLY, false)
        PDFBoxResourceLoader.init(applicationContext)
        if (!unlockOnly) ExternalOpenBus.offer(intent, contentResolver)
        enableEdgeToEdge()
        setContent {
            LocalDocScannerTheme {
                androidx.compose.runtime.key(unlockSerial) {
                    LockGate(onUnlocked = { if (unlockOnly) unlockReturned() }) {
                        if (unlockOnly) androidx.compose.runtime.LaunchedEffect(Unit) { unlockReturned() } else AppNav()
                    }
                }
            }
        }
    }

    override fun onStop() { HostLockSession.leave(this); super.onStop() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        unlockOnly = intent.getBooleanExtra(HostLockSession.UNLOCK_ONLY, false)
        if (unlockOnly) unlockSerial++
        if (!unlockOnly) ExternalOpenBus.offer(intent, contentResolver)
    }
}
