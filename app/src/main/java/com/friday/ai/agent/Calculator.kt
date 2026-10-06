package com.friday.ai.agent

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Arithmetic the model hands off instead of doing in its head, where it
 * gets 17 × 23 wrong often enough to matter.
 *
 * Numbers, + − × ÷, powers (^ or **), parentheses and sqrt(). No %: it
 * reads as both "percent of" and "remainder", so the model is told to
 * write percentages as multiplication instead. Nothing is evaluated as
 * code: this is a small parser, not a scripting engine.
 */
object Calculator {

    class CalculationError(message: String) : IllegalArgumentException(message)

    private const val SIGNIFICANT_DIGITS = 12

    fun evaluate(expression: String): Double {
        val parser = Parser(normalise(expression))
        val value = parser.expression()
        if (!parser.atEnd) fail("unexpected '${parser.rest}'")
        if (value.isNaN() || value.isInfinite()) fail("no finite result")
        return value
    }

    /** "391", "2.5", "0.333333333333" — never "391.0" or 1.0E10. */
    fun format(value: Double): String {
        val rounded = BigDecimal(value).round(MathContext(SIGNIFICANT_DIGITS)).stripTrailingZeros()
        return if (rounded.signum() == 0) "0" else rounded.toPlainString()
    }

    private fun fail(message: String): Nothing = throw CalculationError(message)

    private fun divide(a: Double, b: Double) = if (b == 0.0) fail("division by zero") else a / b

    private fun normalise(s: String) = s
        .replace("**", "^")
        .replace('×', '*').replace('·', '*').replace('÷', '/').replace(':', '/')
        .replace('−', '-').replace('–', '-')
        .replace(Regex("""(\d),(\d)"""), "$1.$2")
        .replace(" ", "")
        .lowercase()

    private class Parser(private val s: String) {
        private var i = 0

        val atEnd get() = i >= s.length
        val rest: String get() = s.substring(i)

        private fun at(chars: String) = i < s.length && s[i] in chars
        private fun eat(c: Char): Boolean = at(c.toString()).also { if (it) i++ }

        // expression := term (('+' | '-') term)*
        fun expression(): Double {
            var v = term()
            while (at("+-")) v = if (s[i++] == '+') v + term() else v - term()
            return v
        }

        // term := unary (('*' | '/') unary)*
        private fun term(): Double {
            var v = unary()
            while (at("*/")) v = if (s[i++] == '*') v * unary() else divide(v, unary())
            return v
        }

        // unary := ('-' | '+') unary | power   — so -2^2 is -(2^2), as on paper
        private fun unary(): Double = when {
            eat('-') -> -unary()
            eat('+') -> unary()
            else -> power()
        }

        // power := atom ('^' unary)?   — right-associative: 2^3^2 = 2^9
        private fun power(): Double {
            val base = atom()
            return if (eat('^')) base.pow(unary()) else base
        }

        private fun atom(): Double = when {
            s.startsWith(SQRT, i) -> {
                i += SQRT.length
                parenthesised().let { if (it < 0) fail("square root of a negative number") else sqrt(it) }
            }
            at("(") -> parenthesised()
            else -> number()
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            val text = s.substring(start, i)
            if (text.isEmpty()) fail(if (atEnd) "expression ends too early" else "unexpected '$rest'")
            return text.toDoubleOrNull() ?: fail("bad number '$text'")
        }

        private fun parenthesised(): Double {
            if (!eat('(')) fail("expected '('")
            val v = expression()
            if (!eat(')')) fail("missing ')'")
            return v
        }

        private companion object {
            const val SQRT = "sqrt"
        }
    }
}
