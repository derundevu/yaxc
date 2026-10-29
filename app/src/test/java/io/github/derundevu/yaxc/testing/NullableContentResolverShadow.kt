package io.github.derundevu.yaxc.testing

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowContentResolver

/** Android may return null after provider death; Robolectric's supplier instead falls back to a sentinel stream. */
@Implements(ContentResolver::class)
class NullableContentResolverShadow : ShadowContentResolver() {
    @Implementation
    override fun openInputStream(uri: Uri): InputStream? =
        if (uri == nullStreamUri) null else super.openInputStream(uri)

    companion object {
        var nullStreamUri: Uri? = null
        @JvmStatic @Resetter fun resetNullableStream() { nullStreamUri = null }
    }
}
