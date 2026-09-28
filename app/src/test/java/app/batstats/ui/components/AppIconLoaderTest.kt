package app.batstats.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconLoaderTest {
    private object FakeIcon : ImageBitmap {
        override val width = 1
        override val height = 1
        override val config = ImageBitmapConfig.Argb8888
        override val colorSpace: ColorSpace = ColorSpaces.Srgb
        override val hasAlpha = true

        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) = Unit

        override fun prepareToDraw() = Unit
    }

    private val known = object : AppIconLoader {
        override suspend fun load(packageName: String): ImageBitmap? = if (packageName == "a.b") FakeIcon else null

        override fun cached(packageName: String): ImageBitmap? = if (packageName == "a.b") FakeIcon else null
    }

    private val failing = object : AppIconLoader {
        override suspend fun load(packageName: String): ImageBitmap? = error("PackageManager failed")

        override fun cached(packageName: String): ImageBitmap? = throw IllegalStateException("cache failed")
    }

    @Test
    fun loadsAndPeeksKnownIcons() = runTest {
        assertSame(FakeIcon, known.loadOrNull("a.b"))
        assertSame(FakeIcon, known.cachedOrNull("a.b"))
        assertNull(known.loadOrNull("c.d"))
    }

    @Test
    fun aFailingLoaderFallsBackToThePlaceholder() = runTest {
        assertNull(failing.loadOrNull("a.b"))
        assertNull(failing.cachedOrNull("a.b"))
    }

    @Test
    fun theDefaultLoaderHasNothingCached() {
        assertNull(AppIconLoader { FakeIcon }.cached("a.b"))
    }

    @Test
    fun cancellationIsNotSwallowed() = runTest {
        val cancelling = AppIconLoader { throw CancellationException("row left the screen") }
        val thrown = runCatching { cancelling.loadOrNull("a.b") }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }
}
