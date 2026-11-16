package com.belleui.games

import android.Manifest
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.Screen

class GamesActivity : BelleActivity() {
    override val appAccent: Int? get() = 0xFF58B04A.toInt()

    val prefs by lazy { Prefs(this) }
    val emus by lazy { Emulators(this, prefs) }
    val art by lazy { Art(this, prefs) }

    @Volatile
    var games: List<Game> = emptyList()
        private set

    /** Titles grouped by system, in the usual system order; rebuilt once per scan. */
    @Volatile
    var bySystem: List<Pair<GameSystem, List<Game>>> = emptyList()
        private set

    /** Bumped whenever the library or any stat changes, so cached shelves rebuild. */
    var version = 0
    var scanning = false
        private set

    var onResumeHook: (() -> Unit)? = null
    private var launched: Game? = null
    private var launchedAt = 0L
    private var folderCb: ((String?) -> Unit)? = null

    override fun createRoot(shell: BelleShell): Screen = RootScreen(this)

    // ---- search box: a real text field at the top, so the keyboard (and Belle keyboard) works ----
    private lateinit var searchField: EditText
    private var searchCb: ((String) -> Unit)? = null

    override fun buildContent(shell: BelleShell): View {
        val root = FrameLayout(this)
        root.addView(shell, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        searchField = EditText(this)
        searchField.setBackgroundColor(0xFFF2F2F2.toInt())
        searchField.setTextColor(0xFF1B1B1B.toInt())
        searchField.setHintTextColor(0xFF8A8A8A.toInt())
        searchField.hint = "Search games"
        searchField.setSingleLine()
        searchField.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchField.setPadding(24, 0, 24, 0)
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchCb?.invoke(s?.toString() ?: "")
            }
        })
        searchField.setOnEditorActionListener { _, _, _ ->
            closeSearch()
            true
        }
        root.addView(searchField, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 96))
        searchField.visibility = View.GONE
        return root
    }

    override fun shellHandlesKeys() = searchField.visibility != View.VISIBLE

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (searchField.visibility == View.VISIBLE && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) closeSearch()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Shows the search box; [onChange] hears every change to the text. */
    fun askSearch(initial: String, onChange: (String) -> Unit) {
        searchCb = null
        searchField.setText(initial)
        searchField.setSelection(searchField.text.length)
        searchCb = onChange
        searchField.visibility = View.VISIBLE
        searchField.requestFocus()
        searchField.post { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchField, 0) }
    }

    fun closeSearch() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchField.windowToken, 0)
        searchField.visibility = View.GONE
        searchField.clearFocus()
        searchCb = null
        shell.requestFocus()
        shell.invalidate()
    }

    fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Android 11+ needs "All files access" to read ROM folders; older versions use the storage permission. */
    fun askAccess(done: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPerms(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)) { done() }
        }
    }

    fun rescan(done: () -> Unit) {
        if (scanning) return
        scanning = true
        Thread {
            val list = if (hasAccess()) Scanner.scan(prefs.roots) else emptyList()
            bySystem = Systems.all.mapNotNull { sys -> list.filter { it.system === sys }.takeIf { it.isNotEmpty() }?.let { sys to it } }
            games = list
            scanning = false
            version++
            shellPost(done)
        }.start()
    }

    private fun shellPost(r: () -> Unit) {
        shell.post(r)
    }

    /** Starts [g]; returns an error message for the user, or null. */
    fun play(g: Game): String? {
        val err = emus.launch(g)
        if (err == null) {
            val s = prefs.stat(g.rep.path)
            s.launches++
            s.lastPlayed = System.currentTimeMillis()
            prefs.save()
            version++
            launched = g
            launchedAt = SystemClock.elapsedRealtime()
        }
        return err
    }

    fun toggleFavourite(g: Game) {
        val s = prefs.stat(g.rep.path)
        s.fav = !s.fav
        prefs.save()
        version++
    }

    fun pickFolder(cb: (String?) -> Unit) {
        folderCb = cb
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 77)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77) {
            val path = data?.data?.let { treeToPath(it) }
            folderCb?.invoke(path)
            folderCb = null
        }
    }

    override fun onResume() {
        super.onResume()
        // back from an emulator: count the time spent in it as play time
        launched?.let { g ->
            val dt = SystemClock.elapsedRealtime() - launchedAt
            if (dt in 5_000..43_200_000) {
                prefs.stat(g.rep.path).playMs += dt
                prefs.save()
                version++
            }
            launched = null
        }
        onResumeHook?.invoke()
    }
}
