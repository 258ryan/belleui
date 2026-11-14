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
        Theme("Belle Blue", 0xFF0B1B33.toInt(), 0xFF02060D.toInt(), 0xFF2E9BFF.toInt(), 0xFF9AD3FF.toInt()),
        Theme("Asha Lime", 0xFF11261A.toInt(), 0xFF030A06.toInt(), 0xFF8BC53F.toInt(), 0xFFD2F29A.toInt()),
        Theme("Symbian Orange", 0xFF2A1608.toInt(), 0xFF0A0502.toInt(), 0xFFFF8A1E.toInt(), 0xFFFFD099.toInt()),
        Theme("Teal", 0xFF082524.toInt(), 0xFF020A0A.toInt(), 0xFF1ED7C7.toInt(), 0xFFA6F5EE.toInt()),
    )
}
