package com.example.deaddrop

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class DecoyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        webView.webViewClient = WebViewClient()
        webView.settings.javaScriptEnabled = true
        webView.loadUrl("https://news.google.com")
        setContentView(webView)
    }

    override fun onBackPressed() {
        // Prevent accidental exit back to login
        val webView = findViewById<WebView>(android.R.id.content)
        if (webView != null && webView.canGoBack()) webView.goBack()
    }
}