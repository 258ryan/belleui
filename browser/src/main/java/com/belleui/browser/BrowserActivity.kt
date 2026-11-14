package com.belleui.browser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.Screen
import java.net.URLEncoder

/** A Belle-styled browser on top of Android WebView (the Chromium engine). */
class BrowserActivity : BelleActivity() {
    lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var urlField: EditText
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var homeUi: BelleShell

    var loadProgress = 0
    var loading = false
    var pageTitle = ""
    var pageUrl = ""
    var desktop = false
    val prefs by lazy { getSharedPreferences("browser", Context.MODE_PRIVATE) }

    var homePage: String
        get() = prefs.getString("home", "https://duckduckgo.com/") ?: "https://duckduckgo.com/"
        set(v) = prefs.edit().putString("home", v).apply()

    val addressOpen: Boolean get() = urlField.visibility == View.VISIBLE
    val fullscreenVideo: Boolean get() = customView != null

    override fun buildContent(shell: BelleShell): View {
        homeUi = shell
        root = FrameLayout(this)
        web = WebView(this)
        configure(web)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(shell, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        urlField = EditText(this)
        styleUrlField(shell)
        root.addView(urlField, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100))
        urlField.visibility = View.GONE
        shell.onContentChanged { layoutWeb() }
        return root
    }

    override fun createRoot(shell: BelleShell): Screen {
        val start = intent?.data?.toString() ?: homePage
        web.loadUrl(start)
        return BrowserScreen(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { web.loadUrl(it.toString()) }
    }

    private fun layoutWeb() {
        val lp = web.layoutParams as FrameLayout.LayoutParams
        val top = homeUi.contentTop.toInt()
        val bottom = (homeUi.height - homeUi.contentBottom).toInt()
        if (lp.topMargin != top || lp.bottomMargin != bottom) {
            lp.topMargin = top
            lp.bottomMargin = bottom
            web.layoutParams = lp
        }
        val fl = urlField.layoutParams as FrameLayout.LayoutParams
        fl.topMargin = top + (4f * homeUi.ui.sc).toInt()
        fl.height = (46f * homeUi.ui.sc).toInt()
        fl.leftMargin = (8f * homeUi.ui.sc).toInt()
        fl.rightMargin = (8f * homeUi.ui.sc).toInt()
        urlField.layoutParams = fl
    }

    // ---- web view ---------------------------------------------------------------------------

    private fun configure(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.setDownloadListener(DownloadListener { url, ua, cd, mime, _ -> download(url, ua, cd, mime) })
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                loading = true
                pageUrl = url
                homeUi.invalidate()
            }

            override fun onPageFinished(view: WebView, url: String) {
                loading = false
                pageUrl = url
                homeUi.invalidate()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return false
                if (scheme in listOf("http", "https", "about", "file", "data", "javascript", "blob")) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    homeUi.toast("Can't open this link")
                }
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val html = "<html><body style='background:#000;color:#fff;font-family:sans-serif;text-align:center;padding-top:15%'>" +
                        "<h2>Page can't be opened</h2><p style='color:#999'>${error.description}</p>" +
                        "<p style='color:#999'>${request.url}</p></body></html>"
                    view.loadDataWithBaseURL(request.url.toString(), html, "text/html", "utf-8", request.url.toString())
                }
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loadProgress = newProgress
                homeUi.invalidate()
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                pageTitle = title ?: ""
                homeUi.invalidate()
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }

            override fun onHideCustomView() = hideCustomView()
        }
    }

    fun hideCustomView() {
        customView?.let { root.removeView(it) }
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
    }

    private fun download(url: String, ua: String?, cd: String?, mime: String?) {
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .addRequestHeader("User-Agent", ua ?: "")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            getSystemService(DownloadManager::class.java).enqueue(req)
            homeUi.toast("Downloading $name")
        } catch (_: Exception) {
            homeUi.toast("Can't download this file")
        }
    }

    fun applyDesktop(on: Boolean) {
        desktop = on
        val s = web.settings
        s.userAgentString = if (on) {
            WebSettings.getDefaultUserAgent(this).replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile", "")
        } else {
            null
        }
        web.reload()
    }

    fun clearData() {
        web.clearCache(true)
        web.clearHistory()
        web.clearFormData()
        CookieManager.getInstance().removeAllCookies(null)
        homeUi.toast("Browsing data cleared")
    }

    // ---- address entry -----------------------------------------------------------------------

    /** Belle TextField look: the theme's pale rounded frame behind a real EditText (for the IME). */
    private fun styleUrlField(shell: BelleShell) {
        val gfx = shell.ui.gfx
        urlField.background = object : Drawable() {
            override fun draw(c: Canvas) {
                gfx.nine(c, "qtg_fr_textfield_highlighted", RectF(bounds), 40, 20f * shell.ui.sc)
            }

            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}

            @Suppress("OVERRIDE_DEPRECATION")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
        urlField.setTextColor(0xFF1B1B1B.toInt())
        urlField.setHintTextColor(0xFF8A8A8A.toInt())
        urlField.hint = "Search or type address"
        urlField.setSingleLine()
        urlField.imeOptions = EditorInfo.IME_ACTION_GO
        urlField.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        urlField.setPadding(24, 0, 24, 0)
        urlField.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) {
                go(urlField.text.toString())
                true
            } else {
                false
            }
        }
    }

    fun showAddress() {
        layoutWeb()
        urlField.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * homeUi.ui.sc)
        urlField.setText(pageUrl)
        urlField.visibility = View.VISIBLE
        urlField.requestFocus()
        urlField.selectAll()
        getSystemService(InputMethodManager::class.java).showSoftInput(urlField, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideAddress() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(urlField.windowToken, 0)
        urlField.visibility = View.GONE
        homeUi.requestFocus()
        homeUi.invalidate()
    }

    fun go(input: String) {
        hideAddress()
        val t = input.trim()
        if (t.isEmpty()) return
        web.loadUrl(
            when {
                t.contains("://") -> t
                t.contains('.') && !t.contains(' ') -> "https://$t"
                else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(t, "UTF-8")
            },
        )
    }

    // ---- keys --------------------------------------------------------------------------------

    override fun shellHandlesKeys(): Boolean = !addressOpen

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (addressOpen && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_ESCAPE)
        ) {
            hideAddress()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
