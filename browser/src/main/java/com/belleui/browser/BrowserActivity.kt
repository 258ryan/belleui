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
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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
import kotlin.math.abs

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
                view.evaluateJavascript("window.__belleNavReset && window.__belleNavReset()", null)
                pageUrl = url
                homeUi.invalidate()
            }

            override fun onPageFinished(view: WebView, url: String) {
                loading = false
                pageUrl = url
                view.evaluateJavascript(NAV_JS, null)
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

    /** Right stick scrolls the page; the left stick still becomes D-pad keys through the framework. */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && ev.action == MotionEvent.ACTION_MOVE && !addressOpen && !fullscreenVideo) {
            val rx = ev.getAxisValue(MotionEvent.AXIS_Z)
            val ry = ev.getAxisValue(MotionEvent.AXIS_RZ)
            val sc = homeUi.ui.sc
            val dx = if (abs(rx) > 0.2f) (rx * 22f * sc).toInt() else 0
            val dy = if (abs(ry) > 0.2f) (ry * 30f * sc).toInt() else 0
            if (dx != 0 || dy != 0) web.scrollBy(dx, dy)
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    fun showWebKeyboard() {
        web.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
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

/**
 * Spatial D-pad navigation injected into every page: moves a focus ring between links, buttons and
 * fields in the pressed direction, scrolls them into view, and clicks the focused one.
 * __belleNav(dir) returns true when focus moved, false when nothing lies that way (the app then
 * scrolls the page); __belleNav('click') returns 'input' for text fields so the keyboard can open.
 */
private const val NAV_JS = """
(function () {
  if (window.__belleNav) return;
  var cur = null, ring = null;
  var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,[onclick],[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=option],[tabindex]:not([tabindex="-1"]),label[for]';
  function ensureRing() {
    if (!ring) {
      ring = document.createElement('div');
      ring.style.cssText = 'position:absolute;z-index:2147483647;pointer-events:none;border:3px solid #5CB4F8;border-radius:5px;box-shadow:0 0 0 2px rgba(0,0,0,.6),0 0 12px #1080DD;box-sizing:border-box;display:none';
      (document.documentElement || document.body).appendChild(ring);
    }
    return ring;
  }
  function usable(e) {
    var r = e.getBoundingClientRect();
    if (r.width < 8 || r.height < 8) return null;
    var s = getComputedStyle(e);
    if (s.visibility === 'hidden' || s.display === 'none' || s.pointerEvents === 'none' || parseFloat(s.opacity) === 0) return null;
    return r;
  }
  function list() {
    var out = [], els = document.querySelectorAll(SEL);
    for (var i = 0; i < els.length && out.length < 1500; i++) { var r = usable(els[i]); if (r) out.push({ e: els[i], r: r }); }
    return out;
  }
  function reveal(r) {
    var m = 70, dy = 0, dx = 0;
    if (r.top < m) dy = r.top - m; else if (r.bottom > innerHeight - m) dy = r.bottom - (innerHeight - m);
    if (r.left < m) dx = r.left - m; else if (r.right > innerWidth - m) dx = r.right - (innerWidth - m);
    if (dx || dy) window.scrollBy(dx, dy);
  }
  function mark(e) {
    cur = e;
    var r = e.getBoundingClientRect(), g = ensureRing();
    g.style.display = 'block';
    g.style.left = (r.left + window.pageXOffset - 3) + 'px';
    g.style.top = (r.top + window.pageYOffset - 3) + 'px';
    g.style.width = (r.width + 6) + 'px';
    g.style.height = (r.height + 6) + 'px';
    reveal(r);
  }
  function nearestToCenter(cs) {
    var cx = innerWidth / 2, cy = innerHeight / 2, best = null, bd = 1e18;
    cs.forEach(function (c) {
      if (c.r.bottom < 0 || c.r.top > innerHeight || c.r.right < 0 || c.r.left > innerWidth) return;
      var x = c.r.left + c.r.width / 2, y = c.r.top + c.r.height / 2, d = (x - cx) * (x - cx) + (y - cy) * (y - cy);
      if (d < bd) { bd = d; best = c; }
    });
    return best;
  }
  function overlap(a0, a1, b0, b1) { return Math.min(a1, b1) - Math.max(a0, b0); }
  window.__belleNavReset = function () { cur = null; if (ring) ring.style.display = 'none'; };
  window.__belleNav = function (dir) {
    if (dir === 'click') {
      if (!cur || !document.contains(cur)) return false;
      var tag = cur.tagName, t = (cur.type || '').toLowerCase();
      if ((tag === 'INPUT' || tag === 'TEXTAREA' || cur.isContentEditable) &&
          ['checkbox', 'radio', 'button', 'submit', 'reset', 'image', 'file', 'color'].indexOf(t) < 0) { cur.focus(); return 'input'; }
      try { cur.focus && cur.focus(); } catch (x) {}
      cur.click();
      return 'clicked';
    }
    if (cur && !document.contains(cur)) cur = null;
    var cs = list();
    if (!cs.length) return false;
    if (!cur) { var f = nearestToCenter(cs); if (!f) return false; mark(f.e); return true; }
    var fr = cur.getBoundingClientRect(), fx = fr.left + fr.width / 2, fy = fr.top + fr.height / 2, best = null, bs = 1e18;
    for (var i = 0; i < cs.length; i++) {
      var c = cs[i];
      if (c.e === cur || cur.contains(c.e) || c.e.contains(cur)) continue;
      var r = c.r, dx = r.left + r.width / 2 - fx, dy = r.top + r.height / 2 - fy, prim, sec;
      if (dir === 'down') { if (dy <= 4) continue; prim = dy; sec = Math.abs(dx); if (overlap(r.left, r.right, fr.left, fr.right) > 0) sec *= 0.3; }
      else if (dir === 'up') { if (dy >= -4) continue; prim = -dy; sec = Math.abs(dx); if (overlap(r.left, r.right, fr.left, fr.right) > 0) sec *= 0.3; }
      else if (dir === 'right') { if (dx <= 4) continue; prim = dx; sec = Math.abs(dy) * 2; if (overlap(r.top, r.bottom, fr.top, fr.bottom) > 0) sec *= 0.3; }
      else { if (dx >= -4) continue; prim = -dx; sec = Math.abs(dy) * 2; if (overlap(r.top, r.bottom, fr.top, fr.bottom) > 0) sec *= 0.3; }
      var score = prim + sec * 2;
      if (score < bs) { bs = score; best = c; }
    }
    if (!best) return false;
    mark(best.e);
    return true;
  };
})();
"""
