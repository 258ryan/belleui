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
        Theme("Symbian Blue", 0xFF0A1A30.toInt(), 0xFF01040A.toInt(), 0xFF1F78C8.toInt(), 0xFF6CC3F2.toInt()),
        Theme("Belle Silver", 0xFF22262B.toInt(), 0xFF020304.toInt(), 0xFF59718A.toInt(), 0xFFA9C0D6.toInt()),
        Theme("Anna Green", 0xFF0B2414.toInt(), 0xFF020A05.toInt(), 0xFF2E9A44.toInt(), 0xFF8EDB9B.toInt()),
        Theme("Orange", 0xFF2A1608.toInt(), 0xFF0A0502.toInt(), 0xFFE0701A.toInt(), 0xFFFFB866.toInt()),
    )
}
