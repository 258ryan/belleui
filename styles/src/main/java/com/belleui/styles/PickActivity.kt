package com.belleui.styles

import android.app.WallpaperManager
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import com.belleui.kit.BelleShell
import com.belleui.kit.Screen

/**
 * What other apps reach: "Set as wallpaper" for a picture (from Gallery and the like), the wallpaper
 * chooser, and the ringtone picker. Each one opens straight onto the right screen and answers the caller.
 */
class PickActivity : StylesActivity() {
    private var fromPicture = false

    override fun createRoot(shell: BelleShell): Screen {
        val i = intent
        val data: Uri? = i?.data
        return when (i?.action) {
            RingtoneManager.ACTION_RINGTONE_PICKER -> {
                val type = i.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                val existing: Uri? = if (Build_pre33()) @Suppress("DEPRECATION") i.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI) else i.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri::class.java)
                TonesScreen(
                    this, type, i.getStringExtra(RingtoneManager.EXTRA_RINGTONE_TITLE) ?: "Tones",
                    pick = Pick(existing, i.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true), i.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)),
                )
            }
            Intent.ACTION_ATTACH_DATA, WallpaperManager.ACTION_CROP_AND_SET_WALLPAPER -> if (data != null) {
                fromPicture = true
                PreviewScreen(this, data.lastPathSegment ?: "Picture") { loadPhoto(this, data, 2200) }
            } else WallpaperScreen(this)
            else -> WallpaperScreen(this)
        }
    }

    @Suppress("FunctionName")
    private fun Build_pre33() = android.os.Build.VERSION.SDK_INT < 33

    override fun onWallpaperSet() {
        if (fromPicture) {
            setResult(RESULT_OK)
            finish()
        }
    }
}
