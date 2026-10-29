package io.github.derundevu.yaxc.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateVersionTest {
    @Test
    fun numericVersionsCompareComponentsInsteadOfLexically() {
        listOf(
            "1.10.0" to "1.9.9",
            "2" to "1.999.999",
            "1.2.0.1" to "1.2",
            " v2.0.1 " to "v2.0.0",
            "1.2.4-beta1" to "1.2.3",
            "1.2.4+build7" to "1.2.3",
            "1.0003" to "1.2",
            "1.2147483648" to "1.2147483647",
            "1.999999999999999999999999999999" to "1.999999999999999999999999999998",
        ).forEach { (candidate, current) ->
            assertTrue("$candidate must be newer than $current", isVersionNewer(candidate, current))
            assertFalse("$current must not be newer than $candidate", isVersionNewer(current, candidate))
        }
    }

    @Test
    fun absentTrailingComponentsAndBuildSuffixDoNotInventNewVersions() {
        listOf(
            "1" to "1.0.0",
            "v1.2.3" to "1.2.3.0",
            "1.2.3-beta1" to "1.2.3",
            "1.2.3+build999" to "1.2.3",
            "01.002.0003" to "1.2.3",
            "0.0.0" to "0",
        ).forEach { (candidate, current) ->
            assertFalse(isVersionNewer(candidate, current))
            assertFalse(isVersionNewer(current, candidate))
        }
    }

    @Test
    fun malformedOrEmptyCoreCannotDropAComponentAndBecomeANewerRelease() {
        listOf("", "v", "release", "2..1", "2.bad.1", "2.1.", ".2", "-2.1", "2.1x", "2.+1")
            .forEach { candidate ->
                assertFalse("Malformed version must not be newer: $candidate", isVersionNewer(candidate, "1.0"))
            }
        assertFalse(isVersionNewer("2.0", "not-a-version"))
    }
}
