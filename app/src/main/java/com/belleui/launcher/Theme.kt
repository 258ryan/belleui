package com.belleui.launcher

class Theme(
    val name: String,
    val bgTop: Int,
    val bgBottom: Int,
    val accent: Int,
    val accentLight: Int,
)

object Themes {
    private fun mix(a: Int, b: Int, t: Float) = android.graphics.Color.rgb(
        (android.graphics.Color.red(a) + (android.graphics.Color.red(b) - android.graphics.Color.red(a)) * t).toInt(),
        (android.graphics.Color.green(a) + (android.graphics.Color.green(b) - android.graphics.Color.green(a)) * t).toInt(),
        (android.graphics.Color.blue(a) + (android.graphics.Color.blue(b) - android.graphics.Color.blue(a)) * t).toInt(),
    ) or 0xFF000000.toInt()

    /** A launcher theme built around an accent colour chosen in Styles. */
    fun fromAccent(accent: Int) = Theme("Styles colour", mix(accent, 0xFF000000.toInt(), 0.82f), mix(accent, 0xFF000000.toInt(), 0.97f), accent, mix(accent, 0xFFFFFFFF.toInt(), 0.4f))

    val all = listOf(
        Theme("Belle Blue", 0xFF0C1C2C.toInt(), 0xFF020406.toInt(), 0xFF1080DD.toInt(), 0xFF5CB4F8.toInt()),
        Theme("Belle Teal", 0xFF0C2622.toInt(), 0xFF020706.toInt(), 0xFF27B3A0.toInt(), 0xFF9BEBDD.toInt()),
        Theme("Belle Magenta", 0xFF28101F.toInt(), 0xFF070205.toInt(), 0xFFD0338A.toInt(), 0xFFFFA3D3.toInt()),
        Theme("Belle Orange", 0xFF2A1608.toInt(), 0xFF0A0502.toInt(), 0xFFE8761A.toInt(), 0xFFFFC27A.toInt()),
    )
}
