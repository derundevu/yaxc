package io.github.derundevu.yaxc.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class XrayHelperAssetTest {
    @Test
    fun universalUsesDevicePreferenceRatherThanPackagedOrder() {
        assertEquals(
            "arm64-v8a",
            xrayHelperAssetPath(
                arrayOf("arm64-v8a", "armeabi-v7a"),
                arrayOf("armeabi-v7a", "x86", "x86_64", "arm64-v8a"),
            ).substringAfterLast('/'),
        )
    }

    @Test
    fun singleAbiUsesACompatibleSecondaryDeviceAbi() {
        assertEquals(
            "armeabi-v7a",
            xrayHelperAssetPath(arrayOf("arm64-v8a", "armeabi-v7a"), arrayOf("armeabi-v7a")).substringAfterLast('/'),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun x86CannotSelectAnX8664Executable() {
        xrayHelperAssetPath(arrayOf("x86"), arrayOf("x86_64", "arm64-v8a"))
    }

    @Test(expected = IllegalStateException::class)
    fun missingHelperCannotProduceAnExecutablePath() {
        xrayHelperAssetPath(arrayOf("x86_64", "x86"), emptyArray())
    }
}
