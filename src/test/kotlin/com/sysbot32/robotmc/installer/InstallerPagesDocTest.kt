package com.sysbot32.robotmc.installer

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 커밋된 docs/ 페이지가 설치기를 소개하고, 릴리스의 exe·jar만 가리키며, 그 바이너리를 담지 않는지 읽는다.
 */
class InstallerPagesDocTest {

    @Test
    fun pagesIntroPointsAtTheReleaseExeAndJar() {
        val root = File(System.getProperty("user.dir"))
        check(File(root, "settings.gradle.kts").isFile) {
            "테스트 작업 디렉터리가 저장소 루트가 아닙니다: ${root.absolutePath}"
        }
        val docs = File(root, "docs")
        val page = File(docs, "index.html")
        assertTrue(page.isFile, page.path)
        val text = page.readText()

        assertTrue(text.contains("RobotMC 설치기"))
        assertTrue(text.contains("설치하거나 제거하는 프로그램이에요"))
        assertFalse(text.contains("모드 로더"))
        assertFalse(text.contains("NeoForge"))
        assertFalse(text.contains("Fabric"))

        val windows = downloadHref(text, "Windows")
        val macos = downloadHref(text, "macOS")
        val linux = downloadHref(text, "Linux")
        assertEquals(WINDOWS_EXE, windows)
        assertEquals(MACOS_LINUX_JAR, macos)
        assertEquals(MACOS_LINUX_JAR, linux)
        assertTrue(windows.startsWith(RELEASES) && windows.endsWith(".exe"))
        assertTrue(macos.startsWith(RELEASES) && macos.endsWith(".jar"))
        assertTrue(linux.startsWith(RELEASES) && linux.endsWith(".jar"))

        val payloads = docs.walkTopDown()
            .filter { file ->
                file.isFile && (
                    file.name.endsWith(".jar", ignoreCase = true) ||
                        file.name.endsWith(".exe", ignoreCase = true)
                    )
            }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
        assertEquals(emptyList(), payloads)
    }
}

private const val RELEASES = "https://github.com/robot-server/robotmc-installer/releases/"

private const val WINDOWS_EXE =
    "https://github.com/robot-server/robotmc-installer/releases/download/v1.0.3/robotmc-installer-1.0.3.exe"

private const val MACOS_LINUX_JAR =
    "https://github.com/robot-server/robotmc-installer/releases/download/v1.0.3/robotmc-installer-1.0.3.jar"

private fun downloadHref(html: String, label: String): String {
    val matches = ANCHOR.findAll(html).filter { anchor ->
        val visible = anchor.groupValues[2].replace(TAG, " ")
        visible.contains(label)
    }.toList()
    assertEquals(1, matches.size, label)
    val href = HREF.find(matches.single().groupValues[1])?.groupValues?.get(1)
    return checkNotNull(href) { "$label href 가 없습니다" }
}

private val ANCHOR = Regex("""<a\b([^>]*)>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

private val HREF = Regex("""\bhref="([^"]+)"""")

private val TAG = Regex("<[^>]+>")
