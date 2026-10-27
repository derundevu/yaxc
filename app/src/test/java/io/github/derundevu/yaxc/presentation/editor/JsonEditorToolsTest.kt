package io.github.derundevu.yaxc.presentation.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class JsonEditorToolsTest {
    @Test
    fun formattingPreservesMemberOrderDuplicateKeysNumbersAndStringEscapes() {
        val input = """ { "z":900719925474099312345678901234567890, "\u0061":"quote:\" slash:\/ line:\n unicode:\uD83D\uDE00", "z": -0.000E+99999, "unknown":[ true,null, { "keep":1e-0007}]} """
        val expected = """
            {
                "z": 900719925474099312345678901234567890,
                "\u0061": "quote:\" slash:\/ line:\n unicode:\uD83D\uDE00",
                "z": -0.000E+99999,
                "unknown": [
                    true,
                    null,
                    {
                        "keep": 1e-0007
                    }
                ]
            }
        """.trimIndent()

        val formatted = JsonEditorTools.format(input, JsonRoot.Object)

        assertEquals(expected, formatted)
        assertNull(JsonEditorTools.validate(formatted, JsonRoot.Object))
        assertEquals(formatted, JsonEditorTools.format(formatted, JsonRoot.Object))
    }

    @Test
    fun acceptsJsonWhitespaceEscapesAndNumberGrammar() {
        val arrays = listOf(
            " \r\n\t[ ]\r\n",
            "[0,-0,1,-15,1.25,1e9,2E-4,9.0e+007]",
            """["\"\\\/\b\f\n\r\t\u0000\uD800", "Привет 😀"]""",
            "[true,false,null,{},[],[{}]]",
        )

        for (input in arrays) {
            assertNull(input, JsonEditorTools.validate(input, JsonRoot.Array))
        }
        assertNull(
            JsonEditorTools.validate(
                """{"nested":[{},[],{"boolean":false,"nothing":null}]}""",
                JsonRoot.Object,
            ),
        )
    }

    @Test
    fun rejectsNonJsonSyntaxAtTheOffendingCodeUnit() {
        val cases = listOf(
            InvalidInput("[1,]", JsonRoot.Array, 3),
            InvalidInput("""{"a":1,}""", JsonRoot.Object, 7),
            InvalidInput("{a:1}", JsonRoot.Object, 1),
            InvalidInput("""{"a" 1}""", JsonRoot.Object, 5),
            InvalidInput("""{"a":}""", JsonRoot.Object, 5),
            InvalidInput("[01]", JsonRoot.Array, 2),
            InvalidInput("[-01]", JsonRoot.Array, 3),
            InvalidInput("[+1]", JsonRoot.Array, 1),
            InvalidInput("[.1]", JsonRoot.Array, 1),
            InvalidInput("[1.]", JsonRoot.Array, 3),
            InvalidInput("[1e]", JsonRoot.Array, 3),
            InvalidInput("[1e+]", JsonRoot.Array, 4),
            InvalidInput("[NaN]", JsonRoot.Array, 1),
            InvalidInput("[Infinity]", JsonRoot.Array, 1),
            InvalidInput("[True]", JsonRoot.Array, 1),
            InvalidInput("[truX]", JsonRoot.Array, 4),
            InvalidInput("[true false]", JsonRoot.Array, 6),
            InvalidInput("['text']", JsonRoot.Array, 1),
            InvalidInput("""["\q"]""", JsonRoot.Array, 3),
            InvalidInput("""["\u12X4"]""", JsonRoot.Array, 6),
            InvalidInput("[\"unescaped\tcontrol\"]", JsonRoot.Array, 11),
            InvalidInput("[/* comment */1]", JsonRoot.Array, 1),
            InvalidInput("[1\u00a0]", JsonRoot.Array, 2),
            InvalidInput("\ufeff{}", JsonRoot.Object, 0),
            InvalidInput("{} false", JsonRoot.Object, 3),
            InvalidInput("{}[]", JsonRoot.Object, 2),
            InvalidInput("[]", JsonRoot.Object, 0),
            InvalidInput("{}", JsonRoot.Array, 0),
            InvalidInput("null", JsonRoot.Object, 0),
            InvalidInput("{\u0000}", JsonRoot.Object, 1),
        )

        for ((text, root, offset) in cases) {
            val diagnostic = JsonEditorTools.validate(text, root)
            assertNotNull(text, diagnostic)
            diagnostic!!
            assertEquals(text, offset, diagnostic.offset)
            assertEquals(text, 1, diagnostic.line)
            assertEquals(text, offset + 1, diagnostic.column)
        }
    }

    @Test
    fun incompleteDraftsPointToEndOfInput() {
        val drafts = listOf("", " ", "{", "{\"name\"", "{\"name\":", "{\"name\":\"value", "{\"name\":\"\\", "{\"name\":\"\\u12")

        for (draft in drafts) {
            val diagnostic = JsonEditorTools.validate(draft, JsonRoot.Object)
            assertNotNull(draft, diagnostic)
            assertEquals(draft, draft.length, diagnostic!!.offset)
            assertEquals(draft, 1, diagnostic.line)
            assertEquals(draft, draft.length + 1, diagnostic.column)
        }
    }

    @Test
    fun diagnosticsLocateCrLfCrAndLfLinesAndUseEditorOffsets() {
        val input = "{\r\n  \"😀\": 1,\r  \"next\": [true,\n    ]\r\n}"
        val diagnostic = requireNotNull(JsonEditorTools.validate(input, JsonRoot.Object))

        assertEquals(input.indexOf(']'), diagnostic.offset)
        assertEquals(4, diagnostic.line)
        assertEquals(5, diagnostic.column)

        val sameLine = "{\"😀\":}"
        val sameLineDiagnostic = requireNotNull(JsonEditorTools.validate(sameLine, JsonRoot.Object))
        assertEquals(sameLine.indexOf('}'), sameLineDiagnostic.offset)
        assertEquals(sameLine.indexOf('}') + 1, sameLineDiagnostic.column)
    }

    @Test
    fun formattingInvalidDraftThrowsTheSameNavigableDiagnosticAsValidation() {
        val input = "{\n  \"bad\": [1,]\n}"
        val expected = requireNotNull(JsonEditorTools.validate(input, JsonRoot.Object))

        try {
            JsonEditorTools.format(input, JsonRoot.Object)
            fail("Formatting invalid JSON must not replace the draft.")
        } catch (exception: JsonSyntaxException) {
            assertEquals(expected, exception.diagnostic)
        }
    }

    @Test
    fun validationHandlesDeeplyNestedContainersWithoutJvmRecursion() {
        val depth = 20_000
        val input = "[".repeat(depth) + "{}" + "]".repeat(depth)

        assertNull(JsonEditorTools.validate(input, JsonRoot.Array))
        val missingClose = input.dropLast(1)
        assertEquals(
            missingClose.length,
            requireNotNull(JsonEditorTools.validate(missingClose, JsonRoot.Array)).offset,
        )
    }

    @Test
    fun formattingNestedArraysAndObjectsKeepsEmptyContainersCompact() {
        val input = """[{"a":[{},[],{"b":[true,false,null]}]}]"""
        val expected = """
            [
                {
                    "a": [
                        {},
                        [],
                        {
                            "b": [
                                true,
                                false,
                                null
                            ]
                        }
                    ]
                }
            ]
        """.trimIndent()

        assertEquals(expected, JsonEditorTools.format(input, JsonRoot.Array))
    }

    private data class InvalidInput(val text: String, val root: JsonRoot, val offset: Int)
}
