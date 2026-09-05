package com.linxi.diary.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import com.linxi.diary.data.NeteaseAccountStore

/**
 * 网易云官方网页登录页。密码由网易云页面直接处理，林曦只读取登录完成后的 Cookie，
 * 与 NeriPlayer 的 Web 登录方式一致；非网易云域名的主框架跳转会被拦截。
 */
class NeteaseWebLoginActivity : ComponentActivity() {
    companion object {
        private val ALLOWED_DOMAINS = setOf("music.163.com", "y.music.163.com", "163.com", "126.net", "163yun.com")
        private const val LOGIN_URL = "https://music.163.com/#/login"

        fun clearWebLoginCookies() {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var returned = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return true
                    return request.isForMainFrame && !isAllowed(uri.host)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    scheduleCookieCheck()
                }
            }
        }
        setContentView(webView)
        // 每次重新绑定都从干净的官方网页登录态开始，避免已保存 Cookie 让页面直接回旧账号。
        clearWebLoginCookies()
        webView.loadUrl(LOGIN_URL)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun scheduleCookieCheck() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(::completeIfLoggedIn, 800)
    }

    private fun completeIfLoggedIn() {
        if (returned) return
        CookieManager.getInstance().flush()
        val cookies = readCookies()
        if (cookies["MUSIC_U"].isNullOrBlank()) return
        returned = true
        // Store immediately so离开登录页/进程回收不会丢掉刚完成的登录。
        if (NeteaseAccountStore.saveCookies(cookies)) {
            // The parent only needs success; never put third-party cookies in an
            // Activity result extra, where they could be retained by tooling or
            // accidentally forwarded by a future caller.
            setResult(Activity.RESULT_OK)
            finish()
        } else {
            returned = false
        }
    }

    private fun readCookies(): Map<String, String> {
        val manager = CookieManager.getInstance()
        val raw = listOf(
            manager.getCookie("https://music.163.com"),
            manager.getCookie("https://y.music.163.com"),
            manager.getCookie("https://interface.music.163.com"),
            manager.getCookie("https://interface3.music.163.com"),
        ).filterNotNull().filter(String::isNotBlank).joinToString("; ")
        return raw.split(';')
            .map(String::trim)
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }
            .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
            .toMap()
    }

    private fun isAllowed(host: String?): Boolean {
        val normalized = host?.lowercase()?.trimEnd('.') ?: return false
        return ALLOWED_DOMAINS.any { normalized == it || normalized.endsWith(".$it") }
    }
}
