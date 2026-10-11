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
        // Linux는 확인하지 않았다. 세 운영체제에서 쓸 수 있다고 쓰지 않는다.
        assertFalse(text.contains("Windows, macOS, Linux에서 쓸 수 있어요."))
        assertFalse(Regex("""\d+\.\d+\.\d+""").containsMatchIn(text))
        assertFalse(text.contains("releases/download/"))

        val windows = downloadAnchor(text, "Windows")
        val universal = downloadAnchor(text, "Universal")
        // 버튼 이름에 확장자를 붙이거나, 버전을 "연결"하거나, 파일 이름을 사용자에게 붙이라고 하지 않는다.
        assertFalse(text.contains("(.exe)"))
        assertFalse(text.contains("(.jar)"))
        assertFalse(text.contains("파일을 연결했어요"))
        assertFalse(text.contains("뒤에 받은 파일 이름"))
        assertTrue(text.contains("최신 버전: "))
        assertFalse(text.contains("최신 버전은 "))
        assertTrue(text.contains("최신 버전을 확인하는 중이에요."))
        assertEquals(".exe", windows.suffix)
        assertEquals(".jar", universal.suffix)
        assertEquals(LATEST, windows.href)
        assertEquals(LATEST, universal.href)
        assertTrue(text.contains(LATEST_API))
        assertTrue(text.contains("browser_download_url"))
        assertTrue(text.contains("스마트 앱 컨트롤(SAC)"))
        assertTrue(text.contains("앱 &amp; 브라우저 컨트롤에서 스마트 앱 컨트롤을 꺼 주세요"))
        assertTrue(text.contains("서명이 없어서 스마트 앱 컨트롤(SAC)이 켜져 있으면 실행이 막힐 수 있어요."))
        assertFalse(text.contains("서명이 없으면"))
        assertFalse(text.contains("이 파일은 서명이 없어요."))
        assertTrue(text.contains("실행이 막힐 수 있어요"))
        assertTrue(text.contains("추가 정보를 누른 뒤 실행해 주세요"))
        assertFalse(text.contains("Smart App Control"))
        assertTrue(text.contains("Java $javaMajor JRE"))
        assertTrue(text.contains("version=$javaMajor&amp;package=jre"))
        assertTrue(text.contains("받은 폴더에서 이 명령으로 실행해요."))
        assertTrue(text.contains("\"java -jar \" + name"))
        assertTrue(text.contains("fallbackJarName(tag)"))
        assertTrue(text.contains("AbortController"))
        assertTrue(text.contains("실행 명령을 불러오지 못했어요."))

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
