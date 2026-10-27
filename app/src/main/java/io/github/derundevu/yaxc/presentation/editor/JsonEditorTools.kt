package io.github.derundevu.yaxc.presentation.editor

/** Offset and column use UTF-16 code units, matching the editor's selection API. */
data class JsonDiagnostic(
    val message: String,
    val offset: Int,
    val line: Int,
    val column: Int,
)

class JsonSyntaxException(val diagnostic: JsonDiagnostic) : IllegalArgumentException(diagnostic.message)

object JsonEditorTools {
    fun validate(text: String, root: JsonRoot): JsonDiagnostic? = try {
        JsonWalker(text, root).walk()
        null
    } catch (exception: JsonSyntaxException) {
        exception.diagnostic
    }

    /** Rewrites only JSON whitespace; strings, number spellings and member order stay untouched. */
    fun format(text: String, root: JsonRoot): String {
        val output = StringBuilder(text.length)
        JsonWalker(text, root, output).walk()
        return output.toString()
    }
}

/** An explicit container stack avoids consuming the JVM stack for deeply nested drafts. */
private class JsonWalker(
    private val source: String,
    private val root: JsonRoot,
    private val output: StringBuilder? = null,
) {
    private var offset = 0
    private var depth = 0
    private var containers = IntArray(16)

    fun walk() {
        skipWhitespace()
        val opening = when (root) {
            JsonRoot.Object -> '{'
            JsonRoot.Array -> '['
        }
        if (current() != opening) {
            fail(
                when (root) {
                    JsonRoot.Object -> "Expected a JSON object."
                    JsonRoot.Array -> "Expected a JSON array."
                },
            )
        }
        readValue()

        while (depth > 0) {
            skipWhitespace()
            when (containers[depth - 1]) {
                OBJECT_FIRST_KEY -> {
                    if (current() == '}') {
                        closeContainer('}', empty = true)
                    } else {
                        readKey()
                    }
                }
                OBJECT_KEY -> readKey()
                OBJECT_COLON -> {
                    if (current() != ':') fail("Expected ':' after the object member name.")
                    offset += 1
                    output?.append(": ")
                    containers[depth - 1] = OBJECT_VALUE
                }
                OBJECT_VALUE -> {
                    containers[depth - 1] = OBJECT_SEPARATOR
                    readValue()
                }
                OBJECT_SEPARATOR -> when (current()) {
                    ',' -> {
                        offset += 1
                        output?.append(',')
                        containers[depth - 1] = OBJECT_KEY
                    }
                    '}' -> closeContainer('}', empty = false)
                    else -> fail("Expected ',' or '}' after the object member value.")
                }
                ARRAY_FIRST_VALUE -> {
                    if (current() == ']') {
                        closeContainer(']', empty = true)
                    } else {
                        readArrayValue()
                    }
                }
                ARRAY_VALUE -> readArrayValue()
                ARRAY_SEPARATOR -> when (current()) {
                    ',' -> {
                        offset += 1
                        output?.append(',')
                        containers[depth - 1] = ARRAY_VALUE
                    }
                    ']' -> closeContainer(']', empty = false)
                    else -> fail("Expected ',' or ']' after the array item.")
                }
            }
        }

        skipWhitespace()
        if (offset != source.length) fail("Unexpected input after the JSON document.")
    }

    private fun readKey() {
        if (current() != '"') fail("Expected a double-quoted object member name.")
        newLine(depth)
        readString()
        containers[depth - 1] = OBJECT_COLON
    }

    private fun readArrayValue() {
        newLine(depth)
        containers[depth - 1] = ARRAY_SEPARATOR
        readValue()
    }

    private fun readValue() {
        when (current()) {
            '{' -> openContainer(OBJECT_FIRST_KEY)
            '[' -> openContainer(ARRAY_FIRST_VALUE)
            '"' -> readString()
            't' -> readLiteral("true")
            'f' -> readLiteral("false")
            'n' -> readLiteral("null")
            '-', in '0'..'9' -> readNumber()
            else -> fail("Expected a JSON value.")
        }
    }

    private fun openContainer(state: Int) {
        output?.append(source[offset])
        offset += 1
        if (depth == containers.size) containers = containers.copyOf(containers.size * 2)
        containers[depth] = state
        depth += 1
    }

    private fun closeContainer(closing: Char, empty: Boolean) {
        depth -= 1
        if (!empty) newLine(depth)
        output?.append(closing)
        offset += 1
    }

    private fun readString() {
        val start = offset
        offset += 1
        while (offset < source.length) {
            when (source[offset]) {
                '"' -> {
                    offset += 1
                    output?.append(source, start, offset)
                    return
                }
                '\\' -> {
                    offset += 1
                    when (current()) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> offset += 1
                        'u' -> {
                            offset += 1
                            repeat(4) {
                                when (current()) {
                                    in '0'..'9', in 'a'..'f', in 'A'..'F' -> offset += 1
                                    else -> fail("Expected four hexadecimal digits after '\\u'.")
                                }
                            }
                        }
                        else -> fail("Invalid or incomplete JSON escape sequence.")
                    }
                }
                in '\u0000'..'\u001f' -> fail("Unescaped control character in a JSON string.")
                else -> offset += 1
            }
        }
        fail("Unterminated JSON string.")
    }

    private fun readLiteral(literal: String) {
        val start = offset
        for (character in literal) {
            if (current() != character) fail("Expected '$literal'.")
            offset += 1
        }
        output?.append(source, start, offset)
    }

    private fun readNumber() {
        val start = offset
        if (current() == '-') offset += 1
        when (current()) {
            '0' -> {
                offset += 1
                if (current() in '0'..'9') fail("Leading zeros are not allowed in JSON numbers.")
            }
            in '1'..'9' -> readDigits()
            else -> fail("Expected a digit in the JSON number.")
        }
        if (current() == '.') {
            offset += 1
            if (current() !in '0'..'9') fail("Expected a digit after the decimal point.")
            readDigits()
        }
        if (current() == 'e' || current() == 'E') {
            offset += 1
            if (current() == '+' || current() == '-') offset += 1
            if (current() !in '0'..'9') fail("Expected a digit in the JSON number exponent.")
            readDigits()
        }
        output?.append(source, start, offset)
    }

    private fun readDigits() {
        while (current() in '0'..'9') offset += 1
    }

    private fun skipWhitespace() {
        while (true) {
            when (current()) {
                ' ', '\t', '\r', '\n' -> offset += 1
                else -> return
            }
        }
    }

    private fun current(): Char = if (offset < source.length) source[offset] else '\u0000'

    private fun newLine(indent: Int) {
        val destination = output ?: return
        destination.append('\n')
        repeat(indent) { destination.append("    ") }
    }

    private fun fail(message: String): Nothing {
        var line = 1
        var column = 1
        var cursor = 0
        while (cursor < offset) {
            when (source[cursor]) {
                '\r' -> {
                    line += 1
                    column = 1
                    if (cursor + 1 < offset && source[cursor + 1] == '\n') cursor += 1
                }
                '\n' -> {
                    line += 1
                    column = 1
                }
                else -> column += 1
            }
            cursor += 1
        }
        throw JsonSyntaxException(JsonDiagnostic(message, offset, line, column))
    }

    private companion object {
        const val OBJECT_FIRST_KEY = 0
        const val OBJECT_KEY = 1
        const val OBJECT_COLON = 2
        const val OBJECT_VALUE = 3
        const val OBJECT_SEPARATOR = 4
        const val ARRAY_FIRST_VALUE = 5
        const val ARRAY_VALUE = 6
        const val ARRAY_SEPARATOR = 7
    }
}
