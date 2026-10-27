package io.github.derundevu.yaxc.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionResponseMetadataTest {
    @Test
    fun numericRefreshHeaderIsConvertedFromHoursToMinutes() {
        val metadata = HttpHelper.extractSubscriptionMetadata(
            mapOf("Profile-Update-Interval" to listOf("0480")),
        )

        assertEquals(28_800, metadata?.updateIntervalMinutes)
    }

    @Test
    fun refreshIntervalMustFitInPositiveMinutes() {
        val largestSupported = HttpHelper.extractSubscriptionMetadata(
            mapOf("profile-update-interval" to listOf("35791394")),
        )
        val overflowing = HttpHelper.extractSubscriptionMetadata(
            mapOf("profile-update-interval" to listOf("35791395")),
        )

        assertEquals(2_147_483_640, largestSupported?.updateIntervalMinutes)
        assertNull(overflowing)
    }

    @Test
    fun plainTitleIsNotReplacedWithInvalidUtf8FromBase64Guessing() {
        val title = HttpHelper.extractSubscriptionTitle(
            mapOf("profile-title" to listOf("Standard")),
        )

        assertEquals("Standard", title)
    }

    @Test
    fun numericTitlePreservesLeadingZerosWhileIntervalUsesMinutes() {
        val metadata = requireNotNull(
            HttpHelper.extractSubscriptionMetadata(
                mapOf(
                    "profile-title" to listOf("0480"),
                    "profile-update-interval" to listOf("12"),
                ),
            ),
        )

        assertEquals("0480", metadata.profileTitle)
        assertEquals(720, metadata.updateIntervalMinutes)
    }

    @Test
    fun explicitlyEncodedNumericTitlePreservesLeadingZeros() {
        val title = HttpHelper.extractSubscriptionTitle(
            mapOf("profile-title" to listOf("base64:MDAxMjM=")),
        )

        assertEquals("00123", title)
    }

    @Test
    fun explicitUtf8TitleTakesPriorityOverAliasAndFilename() {
        val title = HttpHelper.extractSubscriptionTitle(
            mapOf(
                "PROFILE-TITLE" to listOf(" ", "BASE64:0KLQtdGB0YI="),
                "x-profile-title" to listOf("Secondary title"),
                "content-disposition" to listOf("attachment; filename=backup.json"),
            ),
        )

        assertEquals("Тест", title)
    }

    @Test
    fun unprefixedBase64TitleRemainsSupported() {
        val title = HttpHelper.extractSubscriptionTitle(
            mapOf("x-profile-title" to listOf("VGVzdCBwbGFu")),
        )

        assertEquals("Test plan", title)
    }

    @Test
    fun mimeEncodedUtf8TitleIsDecoded() {
        val title = HttpHelper.extractSubscriptionTitle(
            mapOf("profile-title" to listOf("=?UTF-8?B?0KLQtdGB0YI=?=")),
        )

        assertEquals("Тест", title)
    }

    @Test
    fun encodedFilenameTakesPriorityOverAsciiFallback() {
        val title = HttpHelper.extractSubscriptionTitle(
            mapOf(
                "Content-Disposition" to listOf(
                    "attachment; filename=backup.json; filename*=UTF-8''%D0%A2%D0%B5%D1%81%D1%82.json",
                ),
            ),
        )

        assertEquals("Тест.json", title)
    }

    @Test
    fun trafficAndExpiryKeepTheirUnitsAnd64BitPrecision() {
        val metadata = requireNotNull(
            HttpHelper.extractSubscriptionMetadata(
                mapOf(
                    "Subscription-Userinfo" to listOf(
                        " upload=2147483648; DOWNLOAD=4294967296; total=1099511627776; expire=1893456000 ",
                    ),
                ),
            ),
        )

        assertEquals(6_442_450_944L, metadata.usedBytes)
        assertEquals(1_099_511_627_776L, metadata.totalBytes)
        assertEquals(1_893_456_000L, metadata.expireAtEpochSeconds)
    }

    @Test
    fun invalidCountersDoNotDiscardValidPartialTraffic() {
        val metadata = requireNotNull(
            HttpHelper.extractSubscriptionMetadata(
                mapOf(
                    "subscription-userinfo" to listOf(
                        "upload=-8; download=64; total=9223372036854775808; expire=-1; unknown=42; broken",
                    ),
                ),
            ),
        )

        assertNull(metadata.uploadBytes)
        assertNull(metadata.totalBytes)
        assertNull(metadata.expireAtEpochSeconds)
        assertEquals(64L, metadata.usedBytes)
    }

    @Test
    fun explicitlyReportedZeroIsNotTreatedAsMissingMetadata() {
        val metadata = requireNotNull(
            HttpHelper.extractSubscriptionMetadata(
                mapOf(
                    "subscription-userinfo" to listOf("upload=0; download=0; total=0; expire=0"),
                ),
            ),
        )

        assertEquals(0L, metadata.usedBytes)
        assertEquals(0L, metadata.totalBytes)
        assertEquals(0L, metadata.expireAtEpochSeconds)
    }

    @Test
    fun malformedMetadataDoesNotInventAnIntervalOrTraffic() {
        val metadata = HttpHelper.extractSubscriptionMetadata(
            mapOf(
                "profile-update-interval" to listOf("not-a-number"),
                "subscription-userinfo" to listOf("upload=bad; total=9223372036854775808; expire="),
            ),
        )

        assertNull(metadata)
    }
}
