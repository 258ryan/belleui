package com.belleui.calc

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** A small expression evaluator: + - × ÷, brackets, percent and unary minus. */
object Eval {
    class Bad : Exception()

    /** The value of [src], or null when it is not a complete expression yet. */
    fun value(src: String): Double? = try {
        val p = Parser(src)
        val v = p.expr()
        if (p.i != p.s.length) null else v
    } catch (_: Exception) {
        null
    }

    private class Parser(text: String) {
        val s = text.replace(" ", "").replace('×', '*').replace('÷', '/').replace('−', '-')
        var i = 0

        private fun peek() = if (i < s.length) s[i] else '\u0000'

        fun expr(): Double {
            var v = term()
            while (peek() == '+' || peek() == '-') {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        private fun term(): Double {
            var v = unary()
            while (peek() == '*' || peek() == '/') {
                val op = s[i++]
                val r = unary()
                if (op == '/' && r == 0.0) throw Bad()
                v = if (op == '*') v * r else v / r
            }
            return v
        }

        private fun unary(): Double {
            if (peek() == '-') {
                i++
                return -unary()
            }
            if (peek() == '+') {
                i++
                return unary()
            }
            return postfix()
        }

        private fun postfix(): Double {
            var v = atom()
            while (peek() == '%') {
                i++
                v /= 100.0
            }
            return v
        }

        private fun atom(): Double {
            if (peek() == '(') {
                i++
                val v = expr()
                if (peek() == ')') i++ else throw Bad()
                return v
            }
            val st = i
            while (peek().isDigit() || peek() == '.') i++
            if (st == i) throw Bad()
            return s.substring(st, i).toDouble()
        }
    }

    /** Ten significant digits, no trailing zeros, no exponent unless huge or tiny. */
    fun format(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "Error"
        if (v == 0.0) return "0"
        val bd = BigDecimal(v).round(MathContext(10, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        val abs = Math.abs(v)
        return if (abs >= 1e12 || abs < 1e-9) bd.toString().replace("E+", "e").replace("E", "e") else bd.toPlainString()
    }
}
