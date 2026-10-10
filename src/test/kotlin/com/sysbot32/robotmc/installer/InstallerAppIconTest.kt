package com.sysbot32.robotmc.installer

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.sysbot32.robotmc.installer.config.DEFAULT_PROFILE_ICON
import com.sysbot32.robotmc.installer.gui.installInstallerTaskbarIcon
import com.sysbot32.robotmc.installer.gui.installerTaskbarIconSupported
import com.sysbot32.robotmc.installer.gui.installerWindowIconImage
import com.sysbot32.robotmc.installer.gui.installerWindowIconPainter
import java.awt.Graphics2D
import java.awt.Taskbar
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InstallerAppIconTest {
    @Test
    fun jpackageSelectsIcnsIcoAndPng() {
        val root = projectRoot()
        assertEquals("icns", InstallerAppIcon.fileFor("mac", root).extension)
        assertEquals("ico", InstallerAppIcon.fileFor("windows", root).extension)
        assertEquals("png", InstallerAppIcon.fileFor("linux", root).extension)
        assertTrue(InstallerAppIcon.fileFor("mac", root).isFile)
        assertTrue(InstallerAppIcon.fileFor("windows", root).isFile)
        assertTrue(InstallerAppIcon.fileFor("linux", root).isFile)
        assertFailsWith<IllegalArgumentException> {
            InstallerAppIcon.fileFor("darwin", root)
        }
    }

    @Test
    fun windowIconMatchesTheCommittedPicture() {
        val root = projectRoot()
        val source = ImageIO.read(InstallerAppIcon.fileFor("linux", root))
        val windowImage = installerWindowIconImage()
        val painted = windowIconRaster()
        assertSamePixels(sample(source, SHARED_SIZE), sample(windowImage, SHARED_SIZE))
        assertSamePixels(sample(source, SHARED_SIZE), sample(painted, SHARED_SIZE))
        assertSamePixels(source, windowImage)
        assertNotBlank(sample(source, SHARED_SIZE))
        assertNotRedstone(source)

        listOf("mac", "windows", "linux").forEach { os ->
            val frames = pngFrames(InstallerAppIcon.fileFor(os, root))
            assertTrue(frames.isNotEmpty(), os)
            val sameSize = frames.filter { it.width == source.width && it.height == source.height }
            assertTrue(sameSize.isNotEmpty(), os)
            sameSize.forEach { frame -> assertSamePixels(source, frame) }
            frames.forEach { frame ->
                assertSamePixels(sample(source, SHARED_SIZE), sample(frame, SHARED_SIZE))
                assertNotBlank(sample(frame, SHARED_SIZE))
            }
        }
    }

    @Test
    fun taskbarIconIsTheWindowPicture() {
        val seen = mutableListOf<BufferedImage>()
        installInstallerTaskbarIcon(supported = true) { seen += it }
        assertEquals(1, seen.size)
        assertSamePixels(installerWindowIconImage(), seen.single())

        var called = false
        installInstallerTaskbarIcon(supported = false) { called = true }
        assertTrue(!called)
    }

    @Test
    fun taskbarKeepsTheWindowPictureWhenTheOsAllowsIt() {
        if (!installerTaskbarIconSupported()) {
            return
        }
        installInstallerTaskbarIcon()
        val icon = Taskbar.getTaskbar().iconImage ?: error("taskbar icon was not set")
        assertSamePixels(installerWindowIconImage(), raster(icon))
    }

    @Test
    fun fourWindowsPassThatIconAndTheProfileIconStaysRedstone() {
        val root = projectRoot()
        val window = File(root, "src/main/kotlin/com/sysbot32/robotmc/installer/gui/InstallerWindow.kt").readText()
        val calls = Regex("""(?m)^    (?:Window|DialogWindow)\(""").findAll(window).toList()
        assertEquals(4, calls.size)
        calls.forEach { call ->
            val slice = window.substring(call.range.first, minOf(window.length, call.range.first + 600))
            assertTrue(slice.contains("icon = icon"), slice)
        }
        assertEquals(2, Regex("""installerWindowIcon\(\)""").findAll(window).count())

        val yml = File(root, "src/main/resources/application.yml").readText()
        assertTrue(Regex("""(?m)^  profile-icon: Redstone_Block\s*$""").containsMatchIn(yml))
        assertEquals("Redstone_Block", DEFAULT_PROFILE_ICON)
        val profileTest = File(root, "src/test/kotlin/com/sysbot32/robotmc/installer/launcher/RobotMcProfileTest.kt").readText()
        assertTrue(!profileTest.contains("installer-app-icon"))
    }

    private fun raster(image: java.awt.Image): BufferedImage {
        val width = image.getWidth(null)
        val height = image.getHeight(null)
        val raster = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics: Graphics2D = raster.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return raster
    }

    private fun windowIconRaster(): BufferedImage {
        val painter = installerWindowIconPainter()
        val awt = painter.toAwtImage(Density(1f), LayoutDirection.Ltr)
        val variants = (awt as MultiResolutionImage).resolutionVariants
        assertEquals(1, variants.size)
        return variants.single() as BufferedImage
    }

    private fun projectRoot(): File = File(System.getProperty("user.dir"))

    private fun sample(image: BufferedImage, size: Int): BufferedImage {
        val sampled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val sx = x * image.width / size
                val sy = y * image.height / size
                sampled.setRGB(x, y, image.getRGB(sx, sy))
            }
        }
        return sampled
    }

    private fun assertSamePixels(expected: BufferedImage, actual: BufferedImage) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                assertEquals(expected.getRGB(x, y), actual.getRGB(x, y), "pixel $x,$y")
            }
        }
    }

    private fun assertNotBlank(image: BufferedImage) {
        val colors = HashSet<Int>()
        var opaque = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                colors += pixel
                if (pixel ushr 24 != 0) {
                    opaque++
                }
            }
        }
        assertTrue(colors.size > 1)
        assertTrue(opaque > image.width * image.height / 2)
    }

    private fun assertNotRedstone(image: BufferedImage) {
        var red = 0
        var other = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                if (pixel ushr 24 == 0) {
                    continue
                }
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                if (r > 140 && r > g + 40 && r > b + 40) {
                    red++
                } else {
                    other++
                }
            }
        }
        assertTrue(other > red)
    }

    private fun pngFrames(file: File): List<BufferedImage> {
        val bytes = file.readBytes()
        val frames = mutableListOf<BufferedImage>()
        var index = 0
        while (index + PNG_SIGNATURE.size < bytes.size) {
            if (!bytes.startsWith(PNG_SIGNATURE, index)) {
                index++
                continue
            }
            val end = pngEnd(bytes, index)
            if (end < 0) {
                index++
                continue
            }
            val frame = ImageIO.read(ByteArrayInputStream(bytes, index, end - index))
            if (frame != null) {
                frames += frame
            }
            index = end
        }
        return frames
    }

    private fun pngEnd(bytes: ByteArray, start: Int): Int {
        var index = start + PNG_SIGNATURE.size
        while (index + 8 <= bytes.size) {
            val length = ByteBuffer.wrap(bytes, index, 4).int
            if (length < 0 || index + 12L + length > bytes.size) {
                return -1
            }
            val type = String(bytes, index + 4, 4, StandardCharsets.US_ASCII)
            index += 12 + length
            if (type == "IEND") {
                return index
            }
        }
        return -1
    }

    private fun ByteArray.startsWith(prefix: ByteArray, offset: Int): Boolean {
        if (offset + prefix.size > size) {
            return false
        }
        for (i in prefix.indices) {
            if (this[offset + i] != prefix[i]) {
                return false
            }
        }
        return true
    }

    private companion object {
        const val SHARED_SIZE = 16
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
    }
}
