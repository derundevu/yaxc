package io.github.derundevu.yaxc.presentation.root

import org.junit.Assert.assertEquals
import org.junit.Test

class ChangelogParsingTest {
    @Test
    fun releaseTitleIsNotRepeatedAndWrappedBulletTextStaysInItsOwnItem() {
        assertEquals(
            listOf(
                ChangelogBlock(ChangelogBlockKind.Heading, "Connections"),
                ChangelogBlock(ChangelogBlockKind.Bullet, "First change continues on the next line."),
                ChangelogBlock(ChangelogBlockKind.Bullet, "Second change."),
                ChangelogBlock(ChangelogBlockKind.Paragraph, "A note continued here."),
            ),
            parseChangelog("""
                ## What's new in 1.5.6

                ### Connections
                - First change
                  continues on the next line.
                - Second change.

                A note
                continued here.
            """.trimIndent()),
        )
    }

    @Test
    fun sectionWithoutReleaseTitleIsPreservedAndNumberSignsInsideTextAreNotHeadings() {
        assertEquals(
            listOf(
                ChangelogBlock(ChangelogBlockKind.Heading, "Исправления"),
                ChangelogBlock(ChangelogBlockKind.Bullet, "Исправление #42."),
                ChangelogBlock(ChangelogBlockKind.Paragraph, "#not-a-heading"),
            ),
            parseChangelog("### Исправления\r\n- Исправление #42.\r\n\r\n#not-a-heading"),
        )
    }
}
