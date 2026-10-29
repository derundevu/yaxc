package io.github.derundevu.yaxc

import android.graphics.Path
import android.graphics.PointF
import android.system.Os
import android.system.OsConstants
import androidx.graphics.path.PathIterator
import androidx.graphics.path.PathSegment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePageSizeIntegrationTest {
    @Test
    fun packagedNativeLibrariesLoadAndGraphicsTraversalRetainsExactGeometry() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "io.github.derundevu.yaxc.regression")
        val expected = requireNotNull(InstrumentationRegistry.getArguments().getString("expectedPageSize")) {
            "Pass the actual matrix page size as expectedPageSize"
        }.toLong()
        assertEquals(expected, Os.sysconf(OsConstants._SC_PAGESIZE))
        System.loadLibrary("hev-socks5-tunnel")
        System.loadLibrary("androidx.graphics.path")
        val path = Path().apply {
            moveTo(1f, 2f)
            lineTo(5f, 7f)
            quadTo(11f, 13f, 17f, 19f)
            close()
        }
        val segments = PathIterator(path).asSequence().toList()
        assertEquals(listOf(PathSegment.Type.Move, PathSegment.Type.Line, PathSegment.Type.Quadratic, PathSegment.Type.Close),
            segments.map { it.type })
        assertEquals(listOf(PointF(1f, 2f)), segments[0].points.toList())
        assertEquals(listOf(PointF(1f, 2f), PointF(5f, 7f)), segments[1].points.toList())
        assertEquals(listOf(PointF(5f, 7f), PointF(11f, 13f), PointF(17f, 19f)), segments[2].points.toList())
    }
}
