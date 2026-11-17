package com.belleui.kit

import android.content.Context
import android.graphics.Typeface

/**
 * The firmware's UI typeface: Nokia Sans S60 (resource/fonts/nosnr60.ttf Regular, nssb60.ttf SemiBold), the faces the
 * Symbian text layout uses. The ROM has no Light weight, so Light text is Regular. If the files are missing the
 * system fonts stand in, so text always draws.
 */
object NokiaFonts {
    @Volatile private var regular: Typeface? = null
    @Volatile private var semibold: Typeface? = null

    private fun load(ctx: Context, file: String, fallback: Typeface): Typeface =
        try { Typeface.createFromAsset(ctx.applicationContext.assets, "fonts/$file") } catch (_: Throwable) { fallback }

    fun regular(ctx: Context): Typeface = regular ?: load(ctx, "nosnr60.ttf", Typeface.create("sans-serif", Typeface.NORMAL)).also { regular = it }

    fun semibold(ctx: Context): Typeface = semibold ?: load(ctx, "nssb60.ttf", Typeface.create("sans-serif-medium", Typeface.NORMAL)).also { semibold = it }
}
