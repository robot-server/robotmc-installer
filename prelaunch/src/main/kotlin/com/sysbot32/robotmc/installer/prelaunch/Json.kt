package com.sysbot32.robotmc.installer.prelaunch

/**
 * launcher_profiles.json 과 version JSON 에서 필요한 필드만 읽는다.
 * 에이전트 JAR 에 Jackson 을 넣지 않는다.
 */
internal class Json private constructor(
    private val text: String,
) {
    private var index = 0
    private var depth = 0

    private fun parseValue(): JsonValue {
        if (this.depth > MAX_DEPTH) {
            throw IllegalArgumentException("JSON is too deep")
        }
        if (this.index >= this.text.length) {
            throw IllegalArgumentException("JSON ended early")
        }
        return when (this.text[this.index]) {
            '{' -> JsonValue(this.parseObject())
            '[' -> JsonValue(this.parseArray())
            '"' -> JsonValue(this.parseString())
            't' -> {
                this.consume("true")
                JsonValue(true)
            }
            'f' -> {
                this.consume("false")
                JsonValue(false)
            }
            'n' -> {
                this.consume("null")
                JsonValue(NULL)
            }
            else -> JsonValue(this.parseNumber())
        }
    }

    private fun parseObject(): Map<String, JsonValue> {
        this.expect('{')
        val obj = linkedMapOf<String, JsonValue>()
        this.skipWhitespace()
        if (this.peek('}')) {
            this.index++
            return obj
        }
        while (true) {
            this.skipWhitespace()
            val key = this.parseString()
            this.skipWhitespace()
            this.expect(':')
            this.skipWhitespace()
            this.depth++
            val value = this.parseValue()
            this.depth--
            obj[key] = value
            this.skipWhitespace()
            if (this.peek('}')) {
                this.index++
                return obj
            }
            this.expect(',')
        }
    }

    private fun parseArray(): List<JsonValue> {
        this.expect('[')
        val array = mutableListOf<JsonValue>()
        this.skipWhitespace()
        if (this.peek(']')) {
            this.index++
            return array
        }
        while (true) {
            this.skipWhitespace()
            this.depth++
            array += this.parseValue()
            this.depth--
            this.skipWhitespace()
            if (this.peek(']')) {
                this.index++
                return array
            }
            this.expect(',')
        }
    }

    private fun parseString(): String {
        this.expect('"')
        val value = StringBuilder()
        while (this.index < this.text.length) {
            val current = this.text[this.index++]
            if (current == '"') {
                return value.toString()
            }
            if (current != '\\') {
                value.append(current)
                continue
            }
            if (this.index >= this.text.length) {
                throw IllegalArgumentException("JSON string escape ended early")
            }
            when (val escaped = this.text[this.index++]) {
                '"', '\\', '/' -> value.append(escaped)
                'b' -> value.append('\b')
                'f' -> value.append('\u000c')
                'n' -> value.append('\n')
                'r' -> value.append('\r')
                't' -> value.append('\t')
                'u' -> {
                    if (this.index + 4 > this.text.length) {
                        throw IllegalArgumentException("JSON unicode escape ended early")
                    }
                    val code = this.text.substring(this.index, this.index + 4).toInt(16)
                    value.append(code.toChar())
                    this.index += 4
                }
                else -> throw IllegalArgumentException("JSON string has an unknown escape")
            }
        }
        throw IllegalArgumentException("JSON string ended early")
    }

    private fun parseNumber(): String {
        val start = this.index
        if (this.peek('-')) {
            this.index++
        }
        while (this.index < this.text.length) {
            val current = this.text[this.index]
            if (current.isDigit() || current == '.' || current == 'e' || current == 'E' || current == '+' || current == '-') {
                this.index++
                continue
            }
            break
        }
        if (this.index == start) {
            throw IllegalArgumentException("JSON value is invalid")
        }
        return this.text.substring(start, this.index)
    }

    private fun consume(literal: String) {
        if (!this.text.startsWith(literal, this.index)) {
            throw IllegalArgumentException("JSON value is invalid")
        }
        this.index += literal.length
    }

    private fun expect(expected: Char) {
        if (this.index >= this.text.length || this.text[this.index] != expected) {
            throw IllegalArgumentException("JSON is missing $expected")
        }
        this.index++
    }

    private fun peek(expected: Char): Boolean {
        return this.index < this.text.length && this.text[this.index] == expected
    }

    private fun skipWhitespace() {
        while (this.index < this.text.length && this.text[this.index].isWhitespace()) {
            this.index++
        }
    }

    internal class JsonValue(
        private val value: Any?,
    ) {
        fun isNull(): Boolean = this.value == NULL

        fun stringOrNull(): String? = this.value as? String

        fun obj(): Map<String, JsonValue> {
            val map = this.value as? Map<*, *> ?: throw IllegalArgumentException("JSON value is not an object")
            @Suppress("UNCHECKED_CAST")
            return map as Map<String, JsonValue>
        }
    }

    companion object {
        private const val MAX_DEPTH = 64
        private val NULL = Any()

        fun parse(text: String): JsonValue {
            val source = if (text.startsWith("\uFEFF")) text.substring(1) else text
            val parser = Json(source)
            parser.skipWhitespace()
            val value = parser.parseValue()
            parser.skipWhitespace()
            if (parser.index != parser.text.length) {
                throw IllegalArgumentException("JSON has trailing text")
            }
            return value
        }
    }
}
