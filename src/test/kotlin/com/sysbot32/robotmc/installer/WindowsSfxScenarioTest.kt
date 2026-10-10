package com.sysbot32.robotmc.installer

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Windows 팩이 그대로 붙이는 SFX 설정을 읽는다.
 * 이 호스트는 macOS라 PE를 실행하지 않고, 설정과 아카이브에 넣는 파일 목록만 확인한다.
 */
class WindowsSfxScenarioTest {

    @Test
    fun shippedConfigKeepsAStableExtractAndStartsTheLauncher() {
        val root = repoRoot()
        val config = WindowsSfxPack.shippedConfigText(root)
        val directives = parseSfxDirectives(config)
        val installPath = directives.getValue("InstallPath").single()
        assertTrue(
            installPath == "%%S" || installPath == "%LOCALAPPDATA%\\RobotMC Installer",
            "푸는 위치가 받은 exe 옆이나 %LOCALAPPDATA%\\RobotMC Installer 가 아닙니다: $installPath",
        )
        assertFalse(installPath.contains("%%T"), installPath)
        assertFalse(installPath.lowercase().contains("temp"), installPath)
        assertFalse(directives.containsKey("Delete"), "Delete는 푼 파일을 지운다.")
        assertFalse(directives.containsKey("SelfDelete"), "SelfDelete는 받은 exe를 지운다.")
        assertTrue(
            directives["GUIFlags"].orEmpty().none { enablesExtractPathDialog(it) },
            "경로를 지울 수 있는 대화 상자가 있으면 임시 폴더로 풀린다.",
        )
        assertEquals("RobotMC Installer.exe", launchedExecutable(directives.getValue("RunProgram").single()))
        assertEquals(1, directives.getValue("RunProgram").size)
        assertFalse(directives.containsKey("ExecuteFile"))
        assertFalse(directives.containsKey("Directory"))

        val gradle = File(root, "build.gradle.kts").readText()
        assertTrue(gradle.contains("tasks.register(\"packageWindowsSfx\")"))
        assertTrue(gradle.contains("WindowsSfxPack.configFile("))
        assertTrue(gradle.contains("WindowsSfxPack.downloadModule("))
        assertTrue(gradle.contains("WindowsSfxPack.licenseNoticeFile("))
        assertTrue(gradle.contains("WindowsSfxPack.pack("))
        val packSource = File(
            root,
            "buildSrc/src/main/kotlin/com/sysbot32/robotmc/installer/WindowsSfxPack.kt",
        ).readText()
        val packBody = packSource.substringAfter("fun pack(").substringBefore("\n    fun findSevenZip")
        assertTrue(packBody.contains("sfxArchiveEntries(imageDir)"))
        assertTrue(packBody.contains("configFile.readBytes()"))
        assertTrue(packBody.contains("licenseNotice.name"))
        assertTrue(packBody.contains("concatenate("))
    }

    @Test
    fun moduleIsDownloadedFromThePinnedLgplSourceAndTheNoticeShipsWithIt() {
        val root = repoRoot()
        assertTrue(WindowsSfxPack.MODULE_ARCHIVE_URL.contains("OlegScherbakov/7zSFX/raw/01ed0bf2003ae80cdc5e37e893053d151478c812/files/7zsd_extra_170_3900.7z"))
        assertEquals("223ebd7b6146fc2ae6f2ffd7879aedff7901cab7dcfa859109790df17847797b", WindowsSfxPack.MODULE_ARCHIVE_SHA256)
        assertEquals("7zsd_All_x64.sfx", WindowsSfxPack.MODULE_ENTRY_NAME)
        val notice = WindowsSfxPack.licenseNoticeFile(root).readText()
        assertTrue(notice.contains("GNU Lesser General Public License 2.1"))
        assertTrue(notice.contains("Igor Pavlov"))
        assertTrue(notice.contains("Oleg Scherbakov"))
        assertTrue(notice.contains("3606ffa5f4b3ab4bae02bef11f0467b4131b1ae8/files/7zsd_src_170_3900.7z"))
        assertTrue(notice.contains(WindowsSfxPack.MODULE_ARCHIVE_URL))

        val cache = Files.createTempDirectory("windows-sfx-module").toFile()
        try {
            val archive = File(cache, "archive.7z")
            val extracted = File(cache, "module.sfx")
            archive.writeBytes(byteArrayOf(1, 2, 3, 4))
            extracted.writeBytes(byteArrayOf(5, 6))
            val hash = WindowsSfxPack.sha256(archive)
            assertEquals(extracted, WindowsSfxPack.reusableModule(archive, extracted, hash))
            assertEquals(null, WindowsSfxPack.reusableModule(archive, extracted, "0".repeat(64)))
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun payloadIsTheWholeAppImageNotTheLauncherAlone() {
        val image = Files.createTempDirectory("windows-sfx-image").toFile()
        try {
            File(image, "RobotMC Installer.exe").writeBytes(byteArrayOf(0))
            File(image, "runtime/bin").mkdirs()
            File(image, "runtime/bin/java.exe").writeBytes(byteArrayOf(0))
            File(image, "app").mkdirs()
            File(image, "app/robotmc-installer.jar").writeBytes(byteArrayOf(0))
            val entries = WindowsSfxPack.sfxArchiveEntries(image)
                .map { it.relativeTo(image).invariantSeparatorsPath }
            assertTrue(entries.contains("RobotMC Installer.exe"))
            assertTrue(entries.any { it.startsWith("runtime/") })
            assertTrue(entries.any { it.startsWith("app/") })
            assertEquals(listOf("RobotMC Installer.exe", "app/robotmc-installer.jar", "runtime/bin/java.exe"), entries)
        } finally {
            image.deleteRecursively()
        }

        val launcherOnly = Files.createTempDirectory("windows-sfx-launcher-only").toFile()
        try {
            File(launcherOnly, "RobotMC Installer.exe").writeBytes(byteArrayOf(0))
            assertFailsWith<IllegalArgumentException> {
                WindowsSfxPack.sfxArchiveEntries(launcherOnly)
            }
        } finally {
            launcherOnly.deleteRecursively()
        }
    }

    @Test
    fun concatenateEmbedsTheShippedConfigAfterTheModifiedModule() {
        val config = WindowsSfxPack.configFile(repoRoot()).readBytes()
        val module = modifiedModuleStub()
        val payload = byteArrayOf(
            '7'.code.toByte(),
            'z'.code.toByte(),
            0xBC.toByte(),
            0xAF.toByte(),
            0x27,
            0x1C,
        )
        val packed = WindowsSfxPack.concatenate(module, config, payload)
        assertTrue(packed.contentEquals(module + config + payload))
        val stock = ByteArray(64)
        stock[0] = 'M'.code.toByte()
        stock[1] = 'Z'.code.toByte()
        assertFailsWith<IllegalArgumentException> {
            WindowsSfxPack.concatenate(stock, config, payload)
        }
    }
}

private fun repoRoot(): File {
    val dir = File(System.getProperty("user.dir"))
    check(File(dir, "settings.gradle.kts").isFile) {
        "테스트 작업 디렉터리가 저장소 루트가 아닙니다: ${dir.absolutePath}"
    }
    return dir
}

private fun parseSfxDirectives(config: String): Map<String, List<String>> {
    val body = config.substringAfter(";!@Install@!UTF-8!", missingDelimiterValue = "")
        .substringBefore(";!@InstallEnd@!", missingDelimiterValue = "")
    check(body.isNotEmpty()) { "SFX 설정 마커가 없습니다." }
    val values = linkedMapOf<String, MutableList<String>>()
    val pattern = Regex("([A-Za-z0-9]+)=\"((?:\\\\.|[^\"\\\\])*)\"")
    for (match in pattern.findAll(body)) {
        val decoded = decodeConfigValue(match.groupValues[2])
        values.getOrPut(match.groupValues[1]) { mutableListOf() }.add(decoded)
    }
    check(values.isNotEmpty()) { "SFX 설정을 읽지 못했습니다." }
    return values
}

private fun decodeConfigValue(raw: String): String {
    val decoded = StringBuilder()
    var index = 0
    while (index < raw.length) {
        if (raw[index] == '\\' && index + 1 < raw.length) {
            decoded.append(raw[index + 1])
            index += 2
        } else {
            decoded.append(raw[index])
            index += 1
        }
    }
    return decoded.toString()
}

private fun enablesExtractPathDialog(flags: String): Boolean {
    val sum = if ('+' in flags) {
        flags.split('+').sumOf { it.trim().toInt() }
    } else {
        flags.toInt()
    }
    return (sum and 64) != 0 || (sum and 128) != 0
}

private fun launchedExecutable(command: String): String {
    var rest = command.trim()
    while (true) {
        val prefix = Regex("""^(?:hidcon|nowait|fm\d+):""").find(rest) ?: break
        rest = rest.substring(prefix.range.last + 1).trim()
    }
    val quoted = Regex("\"([^\"]+)\"").find(rest)
    val path = when {
        quoted != null -> quoted.groupValues[1]
        rest.endsWith(".exe") -> rest
        else -> rest.substringBefore(' ')
    }
    return File(path.replace('\\', '/')).name
}

private fun modifiedModuleStub(): ByteArray {
    val marker = WindowsSfxPack.MODIFIED_MODULE_MARKER.toByteArray(StandardCharsets.US_ASCII)
    val installPath = "InstallPath".toByteArray(StandardCharsets.UTF_16LE)
    val bytes = ByteArray(64 + marker.size + installPath.size)
    bytes[0] = 'M'.code.toByte()
    bytes[1] = 'Z'.code.toByte()
    marker.copyInto(bytes, destinationOffset = 64)
    installPath.copyInto(bytes, destinationOffset = 64 + marker.size)
    return bytes
}
