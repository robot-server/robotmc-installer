package com.sysbot32.robotmc.installer

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 커밋된 docs/ 페이지가 최신 릴리스의 exe·jar를 나누어 가리키고, 그 바이너리를 담지 않는지 읽는다.
 */
class InstallerPagesDocTest {

    @Test
    fun pagesIntroLinksTheLatestWindowsExeAndUniversalJar() {
        val root = File(System.getProperty("user.dir"))
        check(File(root, "settings.gradle.kts").isFile) {
            "테스트 작업 디렉터리가 저장소 루트가 아닙니다: ${root.absolutePath}"
        }
        val docs = File(root, "docs")
        val page = File(docs, "index.html")
        assertTrue(page.isFile, page.path)
        val text = page.readText()
        val javaMajor = Regex("""val installerJdkMajor = (\d+)""")
            .find(File(root, "build.gradle.kts").readText())
            ?.groupValues
            ?.get(1)
        checkNotNull(javaMajor)

        assertTrue(text.contains("RobotMC 설치기"))
        assertTrue(text.contains("설치하거나 제거하는 프로그램이에요"))
        assertFalse(text.contains("모드 로더"))
        assertFalse(text.contains("NeoForge"))
        assertFalse(text.contains("Fabric"))
        assertFalse(text.contains("Windows, macOS, Linux에서 쓸 수 있어요."))
        assertFalse(Regex("""\d+\.\d+\.\d+""").containsMatchIn(text))
        assertFalse(text.contains("releases/download/"))

        val windows = downloadAnchor(text, "Windows")
        val universal = downloadAnchor(text, "Universal")
        assertFalse(text.contains("(.exe)"))
        assertFalse(text.contains("(.jar)"))
        assertFalse(text.contains("연결"))
        assertTrue(text.contains("최신 버전은 "))
        assertTrue(text.contains("최신 버전을 확인하는 중이에요."))
        assertEquals(".exe", windows.suffix)
        assertEquals(".jar", universal.suffix)
        assertEquals(LATEST, windows.href)
        assertEquals(LATEST, universal.href)
        assertTrue(text.contains(LATEST_API))
        assertTrue(text.contains("browser_download_url"))
        assertTrue(text.contains("스마트 앱 컨트롤(SAC)"))
        assertTrue(text.contains("Smart App Control을 꺼 주세요"))
        assertTrue(text.contains("Java $javaMajor JRE"))
        assertTrue(text.contains("package=jre"))
        assertTrue(text.contains("이 명령으로 실행해요."))
        assertTrue(text.contains(""""java -jar " + asset.name"""))
        assertFalse(text.contains("뒤에 받은 파일 이름"))

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

private const val LATEST = "https://github.com/robot-server/robotmc-installer/releases/latest"

private const val LATEST_API = "https://api.github.com/repos/robot-server/robotmc-installer/releases/latest"

private data class DownloadAnchor(val href: String, val suffix: String)

private fun downloadAnchor(html: String, label: String): DownloadAnchor {
    val matches = ANCHOR.findAll(html).filter { anchor ->
        val visible = anchor.groupValues[2].replace(TAG, " ")
        visible.contains(label)
    }.toList()
    assertEquals(1, matches.size, label)
    val attrs = matches.single().groupValues[1]
    val href = HREF.find(attrs)?.groupValues?.get(1)
    val suffix = SUFFIX.find(attrs)?.groupValues?.get(1)
    return DownloadAnchor(
        checkNotNull(href) { "$label href 가 없습니다" },
        checkNotNull(suffix) { "$label suffix 가 없습니다" },
    )
}

private val ANCHOR = Regex("""<a\b([^>]*)>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

private val HREF = Regex("""\bhref="([^"]+)"""")

private val SUFFIX = Regex("""\bdata-asset-suffix="([^"]+)"""")

private val TAG = Regex("<[^>]+>")
