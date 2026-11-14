package com.belleui.keyboard

const val K_CHAR = 0
const val K_SHIFT = 1
const val K_PAGE = 2
const val K_BACKSPACE = 3
const val K_ENTER = 4
const val K_SPACE = 5

/** One key. [text] is what it types (or the page number for K_PAGE); [w] is its width in key units. */
class Key(val label: String, val kind: Int, val text: String = label, val w: Float = 1f)

/** The keyboard is always two rows, 18 units wide. Pages: 0 letters, 1 numbers and punctuation, 2 more symbols. */
object Layouts {
    private fun chars(s: String) = s.map { Key(it.toString(), K_CHAR) }
    private fun symbols(s: String) = s.split(" ").filter { it.isNotEmpty() }.map { Key(it, K_CHAR) }

    private val bs = Key("", K_BACKSPACE, w = 2f)
    private val enter = Key("", K_ENTER, w = 2f)

    fun rows(page: Int, qwerty: Boolean): List<List<Key>> = when (page) {
        0 -> if (qwerty) listOf(
            chars("qwertyuiopasdf") + bs + enter,
            chars("ghjklzxcvbnm") + Key("", K_SHIFT, w = 1.5f) + Key("123", K_PAGE, "1", 1.5f) + Key(".", K_CHAR) + Key("", K_SPACE, w = 2f),
        ) else listOf(
            chars("abcdefghijklmn") + bs + enter,
            chars("opqrstuvwxyz") + Key("", K_SHIFT, w = 1.5f) + Key("123", K_PAGE, "1", 1.5f) + Key(".", K_CHAR) + Key("", K_SPACE, w = 2f),
        )
        1 -> listOf(
            symbols("1 2 3 4 5 6 7 8 9 0 - / : ;") + bs + enter,
            symbols("( ) $ & @ \" . , ? ! ' _") + Key("abc", K_PAGE, "0", 1.5f) + Key("#+=", K_PAGE, "2", 1.5f) + Key("", K_SPACE, w = 3f),
        )
        else -> listOf(
            symbols("# % ^ * + = ~ ` | \\ < > [ ]") + bs + enter,
            symbols("{ } € £ ¥ • … © ® ° § ¶") + Key("abc", K_PAGE, "0", 1.5f) + Key("123", K_PAGE, "1", 1.5f) + Key("", K_SPACE, w = 3f),
        )
    }
}
