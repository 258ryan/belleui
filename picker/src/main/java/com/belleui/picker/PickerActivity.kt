package com.belleui.picker

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.Screen
import java.io.File

enum class Mode { NONE, TREE, OPEN, CREATE }

/** The Belle picker. Opened from the launcher it shows its status; opened by another app it picks. */
open class PickerActivity : BelleActivity() {
    private lateinit var field: EditText
    private var textCb: ((String) -> Unit)? = null

    val mode: Mode
        get() = when (intent?.action) {
            Intent.ACTION_OPEN_DOCUMENT_TREE -> Mode.TREE
            Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT -> Mode.OPEN
            Intent.ACTION_CREATE_DOCUMENT -> Mode.CREATE
            else -> Mode.NONE
        }

    /** The file types the calling app asked for (images only, audio only, anything...). */
    val filters: List<String> by lazy {
        val i = intent
        val list = ArrayList<String>()
        i?.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.let { list.addAll(it) }
        i?.type?.let { if (it !in list) list.add(it) }
        list
    }

    val multi: Boolean get() = intent?.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) == true
    val suggestedName: String get() = intent?.getStringExtra(Intent.EXTRA_TITLE) ?: "New file"
    val marked = LinkedHashSet<File>()
    var showHidden = false

    override fun createRoot(shell: BelleShell): Screen = if (mode == Mode.NONE || !hasAccess()) StatusScreen(this) else RootsScreen(this)

    fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Android 11 keeps shared storage closed to apps until "All files access" is switched on for them. */
    fun askAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            else requestPerms(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) { }
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    var onResumeHook: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        onResumeHook?.invoke()
    }

    // ---- results ---------------------------------------------------------------------------------

    private val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    fun pickTree(dir: File) {
        val uri = DocumentsContract.buildTreeDocumentUri(FilesProvider.AUTHORITY, dir.path)
        setResult(RESULT_OK, Intent().setData(uri).addFlags(grantFlags))
        finish()
    }

    fun pickFiles(files: List<File>) {
        if (files.isEmpty()) return
        val uris = files.map { DocumentsContract.buildDocumentUri(FilesProvider.AUTHORITY, it.path) }
        val r = Intent().setData(uris[0]).addFlags(grantFlags)
        if (uris.size > 1) {
            val clip = ClipData.newRawUri(null, uris[0])
            for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
            r.clipData = clip
        } else {
            r.clipData = ClipData.newRawUri(null, uris[0])
        }
        setResult(RESULT_OK, r)
        finish()
    }

    /** Creates [name] in [dir] and returns it, for an app that asked to save a file. */
    fun createHere(dir: File, name: String): Boolean {
        return try {
            val id = contentResolver.let {
                val parent = DocumentsContract.buildDocumentUri(FilesProvider.AUTHORITY, dir.path)
                DocumentsContract.createDocument(it, parent, intent?.type ?: "application/octet-stream", name)
            } ?: return false
            setResult(RESULT_OK, Intent().setData(id).addFlags(grantFlags))
            finish()
            true
        } catch (_: Exception) {
            false
        }
    }

    // ---- text box ----------------------------------------------------------------------------------

    override fun buildContent(shell: BelleShell): View {
        val root = FrameLayout(this)
        root.addView(shell, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        field = EditText(this)
        field.setBackgroundColor(0xFFF2F2F2.toInt())
        field.setTextColor(0xFF1B1B1B.toInt())
        field.setHintTextColor(0xFF8A8A8A.toInt())
        field.setSingleLine()
        field.imeOptions = EditorInfo.IME_ACTION_DONE
        field.setPadding(24, 0, 24, 0)
        field.setOnEditorActionListener { _, _, _ ->
            val t = field.text.toString().trim()
            closeText()
            if (t.isNotEmpty()) textCb?.invoke(t)
            true
        }
        root.addView(field, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 96))
        field.visibility = View.GONE
        return root
    }

    override fun shellHandlesKeys() = field.visibility != View.VISIBLE

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (field.visibility == View.VISIBLE && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) closeText()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun askText(hint: String, initial: String, cb: (String) -> Unit) {
        textCb = cb
        field.hint = hint
        field.setText(initial)
        field.setSelection(0, field.text.length)
        field.visibility = View.VISIBLE
        field.requestFocus()
        field.post { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, 0) }
    }

    private fun closeText() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
        field.visibility = View.GONE
        field.clearFocus()
        shell.requestFocus()
        shell.invalidate()
    }
}

/** The activity other apps open: the same code, kept under its own name so the launcher entry stays separate. */
class PickActivity : PickerActivity()
