package com.belleui.launcher

class Theme(
    val name: String,
    val bgTop: Int,
    val bgBottom: Int,
    val accent: Int,
    val accentLight: Int,
)

object Themes {
    val all = listOf(
        Theme("Belle Blue", 0xFF0C1C2C.toInt(), 0xFF020406.toInt(), 0xFF1080DD.toInt(), 0xFF5CB4F8.toInt()),
        Theme("Belle Teal", 0xFF0C2622.toInt(), 0xFF020706.toInt(), 0xFF27B3A0.toInt(), 0xFF9BEBDD.toInt()),
        Theme("Belle Magenta", 0xFF28101F.toInt(), 0xFF070205.toInt(), 0xFFD0338A.toInt(), 0xFFFFA3D3.toInt()),
        Theme("Belle Orange", 0xFF2A1608.toInt(), 0xFF0A0502.toInt(), 0xFFE8761A.toInt(), 0xFFFFC27A.toInt()),
    )
}
