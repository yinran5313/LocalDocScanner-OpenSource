package com.localdoc.scanner.office

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** Readable upstream attribution and corresponding source links, available offline. */
class OfficeLicenseActivity : Activity() {
    private var web: WebView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Office许可与源码"
        web = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.scheme in setOf("https", "http")) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                        return true
                    }
                    return false
                }
            }
            loadUrl("file:///android_asset/office-source.html")
        }
        setContentView(web)
    }
    override fun onDestroy() { web?.destroy(); web = null; super.onDestroy() }
}
