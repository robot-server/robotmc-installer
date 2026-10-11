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
 * Windows SFX가 우리 스텁과 앱 이미지 전체의 7z로 묶이는지 읽는다.
 * 이 테스트는 스텁을 컴파일하거나 실행하지 않는다.
 */
class WindowsSfxScenarioTest {

    @Test
    fun stubExtractsToLocalAppDataLeavesTheFilesAndLaunchesTheInstaller() {
        val root = repoRoot()
        val stub = WindowsSfxPack.stubSource(root).readText()
        assertTrue(stub.contains("\"$LOCAL_APP_DATA\""), stub)
        assertTrue(stub.contains("L\"${WindowsSfxPack.INSTALL_DIR_NAME}\""))
        assertTrue(stub.contains("L\"${WindowsSfxPack.LAUNCHER_FILE_NAME}\""))
        assertTrue(stub.contains("SzArEx_Open"))
        assertTrue(stub.contains("SzArEx_Extract"))
        assertTrue(stub.contains("CreateProcessW"))
        assertTrue(stub.contains("SFX_EXTRACT_TITLE"))
        assertTrue(stub.contains("\\xC555\\xCD95"))
        assertTrue(stub.contains("\\xCDE8\\xC18C"))
        assertTrue(stub.contains("PROGRESS_CLASSW"))
        assertTrue(stub.contains("FW_NORMAL"))
        assertFalse(stub.contains("PBS_MARQUEE"))
        assertTrue(stub.contains("ShowExtractWindow("))
        assertTrue(stub.contains("RememberError("))
        assertTrue(stub.contains("MB_SETFOREGROUND"))
        assertFalse(stub.contains("MB_TOPMOST"))
        assertFalse(stub.contains("GetTempPath"), stub)
        assertFalse(stub.contains("GetTempFileName"), stub)
        assertFalse(stub.contains("DeleteFile"), stub)
        assertFalse(stub.contains("RemoveDirectory"), stub)
        assertFalse(stub.contains("%%T"), stub)
        assertFalse(stub.contains("SfxSetup"), stub)

        val gradle = File(root, "build.gradle.kts").readText()
        val task = gradle.substringAfter("tasks.register(\"packageWindowsSfx\")")
            .substringBefore("tasks.register(\"checkInstallerAppImage\")")
        assertTrue(task.contains("InstallerAppIcon.fileFor(\"windows\""))
        assertTrue(task.contains("WindowsSfxPack.compileStub("))
        assertTrue(task.contains("WindowsSfxPack.pack("))
        assertTrue(task.contains("installerHostOs() != \"windows\""))
        assertFalse(task.contains("downloadModule"))
        assertFalse(task.contains("sfxModule"))
        assertFalse(task.contains("configFile"))
        assertFalse(task.contains("licenseNotice"))
        assertFalse(task.contains("UpdateResource"))
        assertFalse(task.contains("BeginUpdateResource"))
        assertFalse(gradle.contains("7zsd_"))
        assertFalse(gradle.contains("OlegScherbakov"))

        val packSource = packSource(root)
        val packBody = packSource.substringAfter("fun pack(").substringBefore("\n    fun findSevenZip")
        assertTrue(packBody.contains("sfxArchiveEntries(imageDir)"))
        assertTrue(packBody.contains("payloadArguments("))
        assertTrue(packBody.contains("writeAssembledExe("))
        assertTrue(packBody.contains("requireOurStub("))
        assertFalse(packBody.contains("notice"))
        assertFalse(packBody.contains("license"))
        assertFalse(packBody.contains("config"))
        val compileBody = packSource.substringAfter("fun compileStub(").substringBefore("fun compileWindowsProgram")
        assertTrue(compileBody.contains("stubSource(projectDir)"))
        assertTrue(compileBody.contains("decoderSources(projectDir)"))
        assertTrue(compileBody.contains("iconFile"))
        assertFalse(packSource.contains("UpdateResource"))
        assertFalse(packSource.contains("BeginUpdateResource"))
        assertFalse(File(root, "packaging/windows-sfx/config.txt").exists())
    }

    @Test
    fun decoderIsThePublicDomainLzmaSdkAndTheOldModuleIsNotDownloaded() {
        val root = repoRoot()
        val sources = WindowsSfxPack.decoderSources(root)
        assertEquals(WindowsSfxPack.DECODER_SOURCE_NAMES, sources.map { it.name })
        assertTrue(sources.any { it.name == "LzmaDec.c" })
        assertTrue(sources.any { it.name == "Lzma2Dec.c" })
        assertTrue(sources.any { it.name == "7zDec.c" })
        assertTrue(sources.any { it.name == "7zArcIn.c" })
        assertFalse(sources.any { it.name.contains("Sfx") || it.name.contains("Enc") })
        for (source in sources) {
            val head = source.readText().take(500)
            assertTrue(head.contains("Public domain"), source.name)
            assertTrue(head.contains("Igor Pavlov"), source.name)
        }
        val license = WindowsSfxPack.lzmaSdkLicense(root).readText()
        assertTrue(license.startsWith("LZMA SDK ${WindowsSfxPack.LZMA_SDK_VERSION}"))
        assertTrue(license.contains("placed in the public domain by Igor Pavlov"))
        val notice = WindowsSfxPack.noticeFile(root).readText()
        assertTrue(notice.contains("퍼블릭 도메인"))
        assertTrue(notice.contains("LZMA SDK"))
        assertTrue(notice.contains("%LOCALAPPDATA%\\RobotMC Installer"))
        assertTrue(notice.contains(WindowsSfxPack.LAUNCHER_FILE_NAME))
        assertFalse(notice.contains("Oleg Scherbakov"))
        assertFalse(notice.contains("OlegScherbakov"))
        assertFalse(notice.contains("7zsd_"))
        assertFalse(notice.contains("GNU Lesser"))
        val packSource = packSource(root)
        assertFalse(packSource.contains("downloadModule"))
        assertFalse(packSource.contains("MODULE_ARCHIVE"))
        assertFalse(packSource.contains("OlegScherbakov"))
        assertFalse(packSource.contains("https://github.com/OlegScherbakov"))
        assertTrue(packSource.contains("7zsd_All_x64"))
        assertTrue(packSource.contains("수정 SFX 모듈은 붙이지 않습니다."))
        assertTrue(WindowsSfxPack.MINGW_URL.contains("skeeto/w64devkit/releases/download/v2.10.0/w64devkit-x64-2.10.0.7z.exe"))
        assertEquals(64, WindowsSfxPack.MINGW_SHA256.length)
        assertFalse(WindowsSfxPack.MINGW_URL.contains("OlegScherbakov"))
        assertFalse(WindowsSfxPack.MINGW_URL.contains("7zsd"))
        assertTrue(packSource.contains("downloadMingw("))
    }

    @Test
    fun payloadIsLzma2OfTheWholeAppImage() {
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

        val arguments = WindowsSfxPack.payloadArguments(File("payload.7z"), File("files.txt"))
        assertTrue(arguments.contains("-t7z"))
        assertTrue(arguments.contains("-m0=LZMA2"))
        assertTrue(arguments.contains("-ms=64m"))
        assertFalse(arguments.any { it.contains("BCJ") })
        assertFalse(arguments.any { it.startsWith("-m1=") })
    }

    @Test
    fun assembleAppendsTheArchiveAndRejectsTheLgplModule() {
        val work = Files.createTempDirectory("windows-sfx-assemble").toFile()
        try {
            val payload = byteArrayOf(
                '7'.code.toByte(),
                'z'.code.toByte(),
                0xBC.toByte(),
                0xAF.toByte(),
                0x27,
                0x1C,
                9,
                8,
            )
            val payloadFile = File(work, "payload.7z")
            payloadFile.writeBytes(payload)
            val stub = pe64Stub()
            val destination = File(work, "packed.exe")
            WindowsSfxPack.writeAssembledExe(stub, payloadFile, destination)
            assertTrue(destination.readBytes().contentEquals(stub + payload))
            val pe32 = pe64Stub()
            pe32[0x58] = 0x0B
            pe32[0x59] = 0x01
            assertFailsWith<IllegalArgumentException> {
                WindowsSfxPack.writeAssembledExe(pe32, payloadFile, File(work, "pe32.exe"))
            }

            val module = stub.copyOf()
            "Scherbakov".toByteArray(StandardCharsets.US_ASCII).copyInto(module, destinationOffset = 32)
            assertFailsWith<IllegalArgumentException> {
                WindowsSfxPack.writeAssembledExe(module, payloadFile, File(work, "rejected.exe"))
            }
            val named = stub.copyOf()
            "7zsd_All_x64".toByteArray(StandardCharsets.US_ASCII).copyInto(named, destinationOffset = 16)
            assertFailsWith<IllegalArgumentException> {
                WindowsSfxPack.writeAssembledExe(named, payloadFile, File(work, "named.exe"))
            }
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun iconResourceScriptPointsAtTheInstallerIcon() {
        val root = repoRoot()
        val icon = WindowsSfxPack.iconFile(root)
        assertEquals(WindowsSfxPack.ICON_RELATIVE_PATH, icon.relativeTo(root).invariantSeparatorsPath)
        assertTrue(icon.isFile)
        assertEquals(icon.canonicalFile, InstallerAppIcon.fileFor("windows", root).canonicalFile)
        val script = WindowsSfxPack.iconResourceScript(icon, File(root, "stub.manifest"))
        assertTrue(script.contains("ICON"))
        assertTrue(script.contains(icon.absolutePath.replace('\\', '/')))
        assertFalse(script.contains("UpdateResource"))
        assertFalse(script.contains("BeginUpdateResource"))
        val encoded = File(root, "build/sfx-tool-text-probe.txt")
        encoded.parentFile.mkdirs()
        WindowsSfxPack.writeToolText(encoded, "ICON \"C:/홍길동/a.ico\"\r\n", utf16 = true)
        val decoded = String(encoded.readBytes().copyOfRange(2, encoded.length().toInt()), Charsets.UTF_16LE)
        assertTrue(decoded.contains("홍길동"))
        encoded.delete()
    }
}

private fun pe64Stub(): ByteArray {
    val stub = ByteArray(0x80)
    stub[0] = 'M'.code.toByte()
    stub[1] = 'Z'.code.toByte()
    stub[0x3C] = 0x40
    stub[0x40] = 'P'.code.toByte()
    stub[0x41] = 'E'.code.toByte()
    stub[0x58] = 0x0B
    stub[0x59] = 0x02
    return stub
}

private const val LOCAL_APP_DATA = "LOCALAPPDATA"

private fun repoRoot(): File {
    val dir = File(System.getProperty("user.dir"))
    check(File(dir, "settings.gradle.kts").isFile) {
        "테스트 작업 디렉터리가 저장소 루트가 아닙니다: ${dir.absolutePath}"
    }
    return dir
}

private fun packSource(root: File): String {
    return File(root, "buildSrc/src/main/kotlin/com/sysbot32/robotmc/installer/WindowsSfxPack.kt").readText()
}
