package com.hackathon.assistant.actions

import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Evaluates a math expression entirely on-device — no network, no scripting engine.
 *
 * Understands digits, decimals, "+ - * / ( )" and natural-language operators
 * ("plus", "minus", "times", "multiplied by", "divided by", "over") plus
 * percentages ("15% of 2000" / "15 percent of 2000" => (15/100)*2000). The spoken
 * result is rounded to at most 4 decimals with trailing zeros dropped.
 */
class CalculatorSkill : Skill {
    override val id = "calculate"
    override val description = "Do math on-device: add, subtract, multiply, divide, percentages, parentheses"
    override val slots = listOf(
        SlotSpec(
            "expression",
            "The math expression to evaluate, e.g. '15% of 2000' or '45 times 12'.",
            required = true,
            question = "What should I calculate?",
        ),
    )
    override val examples = listOf(
        "what's 15% of 2000",
        "calculate 45 times 12",
        "what is 100 divided by 7",
        "2000 minus 349",
    )

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult = try {
        val raw = args["expression"]?.trim().orEmpty()
        if (raw.isEmpty()) {
            ActionResult.Failure("I couldn't work that out")
        } else {
            val result = Parser(tokenize(normalize(raw))).parse()
            ActionResult.Success("That's ${format(result)}")
        }
    } catch (e: Exception) {
        ActionResult.Failure("I couldn't work that out")
    }

    // --- Step 1: normalize natural language into a symbolic expression ---

    private fun normalize(input: String): String {
        var s = input.lowercase()
        // Strip thousands separators / stray commas ("2,000" -> "2000").
        s = s.replace(",", "")
        // "X% of Y" / "X percent of Y"  ->  (X/100)*Y
        s = Regex("([0-9]*\\.?[0-9]+)\\s*(?:%|percent)\\s+of\\b")
            .replace(s) { "(${it.groupValues[1]}/100)*" }
        // Any remaining "X%" / "X percent"  ->  (X/100)
        s = Regex("([0-9]*\\.?[0-9]+)\\s*(?:%|percent)")
            .replace(s) { "(${it.groupValues[1]}/100)" }
        // Word operators (longest phrases first so they win over the short ones).
        s = s.replace("multiplied by", " * ")
            .replace("divided by", " / ")
            .replace("times", " * ")
            .replace("plus", " + ")
            .replace("minus", " - ")
            .replace("over", " / ")
        // Drop anything that isn't part of a math expression (filler words, etc.).
        s = s.replace(Regex("[^0-9.+\\-*/() ]"), " ")
        return s.trim()
    }

    // --- Step 2: tokenize ---

    private sealed interface Token {
        data class Num(val value: Double) : Token
        data class Op(val ch: Char) : Token
    }

    private fun tokenize(s: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '+' || c == '-' || c == '*' || c == '/' || c == '(' || c == ')' -> {
                    tokens.add(Token.Op(c)); i++
                }
                c.isDigit() || c == '.' -> {
                    val start = i
                    var dots = 0
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) {
                        if (s[i] == '.') dots++
                        i++
                    }
                    val numStr = s.substring(start, i)
                    if (dots > 1) throw NumberFormatException("malformed number: $numStr")
                    tokens.add(Token.Num(numStr.toDouble()))
                }
                else -> throw IllegalArgumentException("unexpected character: $c")
            }
        }
        if (tokens.isEmpty()) throw IllegalArgumentException("empty expression")
        return tokens
    }

    // --- Step 3: evaluate (recursive descent) ---
    //   expr   := term (('+' | '-') term)*
    //   term   := factor (('*' | '/') factor)*
    //   factor := ('+' | '-') factor | primary
    //   primary:= number | '(' expr ')'
    private class Parser(private val tokens: List<Token>) {
        private var pos = 0

        fun parse(): Double {
            val value = expr()
            if (pos != tokens.size) throw IllegalStateException("trailing tokens")
            return value
        }

        private fun peek(): Token? = tokens.getOrNull(pos)

        private fun expr(): Double {
            var value = term()
            while (true) {
                val t = peek()
                if (t is Token.Op && (t.ch == '+' || t.ch == '-')) {
                    pos++
                    val rhs = term()
                    value = if (t.ch == '+') value + rhs else value - rhs
                } else break
            }
            return value
        }

        private fun term(): Double {
            var value = factor()
            while (true) {
                val t = peek()
                if (t is Token.Op && (t.ch == '*' || t.ch == '/')) {
                    pos++
                    val rhs = factor()
                    value = if (t.ch == '*') value * rhs else value / rhs
                } else break
            }
            return value
        }

        private fun factor(): Double {
            val t = peek() ?: throw IllegalStateException("unexpected end of expression")
            if (t is Token.Op && (t.ch == '+' || t.ch == '-')) {
                pos++
                val operand = factor()
                return if (t.ch == '-') -operand else operand
            }
            return primary()
        }

        private fun primary(): Double {
            when (val t = peek() ?: throw IllegalStateException("unexpected end of expression")) {
                is Token.Num -> {
                    pos++
                    return t.value
                }
                is Token.Op -> {
                    if (t.ch == '(') {
                        pos++
                        val value = expr()
                        val close = peek()
                        if (close is Token.Op && close.ch == ')') {
                            pos++
                            return value
                        }
                        throw IllegalStateException("missing closing parenthesis")
                    }
                    throw IllegalStateException("unexpected operator: ${t.ch}")
                }
            }
        }
    }

    // --- Step 4: format for speech (<= 4 decimals, drop trailing zeros) ---

    private fun format(value: Double): String {
        if (value.isNaN() || value.isInfinite()) {
            throw ArithmeticException("non-finite result")
        }
        val rounded = BigDecimal(value).setScale(4, RoundingMode.HALF_UP)
        // Whole number -> no decimal point ("300.0000" -> "300").
        if (rounded.remainder(BigDecimal.ONE).signum() == 0) {
            return rounded.toBigInteger().toString()
        }
        // Otherwise drop trailing zeros ("14.2000" -> "14.2", "14.2857" stays).
        return rounded.stripTrailingZeros().toPlainString()
    }
}
