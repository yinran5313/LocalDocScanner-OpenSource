package com.localdoc.scanner

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        ExternalOpenBus.offer(intent, contentResolver)
        enableEdgeToEdge()
        setContent {
            LocalDocScannerTheme {
                LockGate { AppNav() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ExternalOpenBus.offer(intent, contentResolver)
    }
}
