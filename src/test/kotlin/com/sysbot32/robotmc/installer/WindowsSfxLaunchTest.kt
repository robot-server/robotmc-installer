package com.sysbot32.robotmc.installer

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 컴파일된 스텁으로 픽스처 이미지를 풀어 본다.
 * `test`의 기본 실행에는 넣지 않는다. ROBOTMC_SFX_LAUNCH=1 일 때만 돈다.
 */
@EnabledIfEnvironmentVariable(named = "ROBOTMC_SFX_LAUNCH", matches = "1")
class WindowsSfxLaunchTest {

    @Test
    fun fixtureExeExtractsToLocalAppDataLeavesFilesAndLaunches() {
        val root = repoRoot()
        val scratch = File(System.getenv("ROBOTMC_SFX_SCRATCH") ?: error("ROBOTMC_SFX_SCRATCH 가 없습니다."))
        val log = File(scratch, "sfx-launch.log")
        log.writeText("")
        fun note(line: String) {
            log.appendText(line + "\n")
            println(line)
        }
        val work = File(scratch, "launch-work")
        work.deleteRecursively()
        work.mkdirs()
        val icon = WindowsSfxPack.iconFile(root)
        val stub = WindowsSfxPack.compileStub(
            projectDir = root,
            iconFile = icon,
            workDir = File(work, "stub-compile"),
            destination = File(work, "stub.exe"),
        )
        note("stub: ${stub.absolutePath} (${stub.length()} bytes)")
        assertIconMatches(stub.readBytes(), icon.readBytes())
        note("stub icon resource matches ${WindowsSfxPack.ICON_RELATIVE_PATH}")

        val fixtureSource = File(work, "fixture.c")
        fixtureSource.writeText(FIXTURE, StandardCharsets.US_ASCII)
        val launcher = WindowsSfxPack.compileWindowsProgram(
            sources = listOf(fixtureSource),
            iconFile = null,
            includeDir = null,
            workDir = File(work, "fixture-compile"),
            destination = File(work, WindowsSfxPack.LAUNCHER_FILE_NAME),
        )
        val runtimeBytes = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1)
        val image = File(work, "image")
        File(image, "runtime").mkdirs()
        launcher.copyTo(File(image, WindowsSfxPack.LAUNCHER_FILE_NAME), overwrite = true)
        File(image, "runtime/keep.bin").writeBytes(runtimeBytes)
        val packed = File(work, "out/robotmc-installer-fixture.exe")
        WindowsSfxPack.pack(
            imageDir = image,
            stubFile = stub,
            sevenZip = WindowsSfxPack.findSevenZip(),
            destination = packed,
        )
        val packedBytes = packed.readBytes()
        val stubBytes = stub.readBytes()
        assertTrue(packedBytes.copyOfRange(0, stubBytes.size).contentEquals(stubBytes))
        assertTrue(
            packedBytes.copyOfRange(stubBytes.size, stubBytes.size + 6).contentEquals(
                byteArrayOf('7'.code.toByte(), 'z'.code.toByte(), 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C),
            ),
        )
        assertIconMatches(packedBytes, icon.readBytes())
        note("packed: ${packed.absolutePath} (${packed.length()} bytes)")

        val localAppData = File(scratch, "localappdata")
        localAppData.mkdirs()
        val installDir = File(localAppData, WindowsSfxPack.INSTALL_DIR_NAME)
        val runs = (1..2).map { attempt ->
            val runLog = File(work, "run-$attempt.log")
            if (runLog.exists()) {
                runLog.delete()
            }
            val result = runPacked(packed, localAppData, runLog)
            note("run $attempt exit=${result.code} policy=${result.policyBlocked}")
            note(result.output.trim())
            if (runLog.isFile) {
                note("stub log: ${runLog.readText().trim()}")
            }
            result
        }
        val installedLauncher = File(installDir, WindowsSfxPack.LAUNCHER_FILE_NAME)
        val installedRuntime = File(installDir, "runtime/keep.bin")
        val marker = File(installDir, "launcher-started.txt")
        for (result in runs) {
            assertEquals(0, result.code, result.output)
            assertTrue(installedLauncher.isFile)
            assertTrue(installedRuntime.isFile)
            assertTrue(installedLauncher.readBytes().contentEquals(launcher.readBytes()))
            assertTrue(installedRuntime.readBytes().contentEquals(runtimeBytes))
            assertEquals("launcher-started", marker.readText())
        }
        note("install dir: ${installDir.absolutePath}")
        installDir.walkTopDown().filter { it.isFile }.forEach { file ->
            note("left ${file.relativeTo(installDir).invariantSeparatorsPath} ${file.length()}")
        }
    }
}

private data class RunResult(val code: Int, val output: String, val policyBlocked: Boolean)

private fun runPacked(exe: File, localAppData: File, stubLog: File): RunResult {
    return try {
        val process = ProcessBuilder(exe.absolutePath)
            .directory(exe.parentFile)
            .redirectErrorStream(true)
            .apply {
                environment()["LOCALAPPDATA"] = localAppData.absolutePath
                environment()["ROBOTMC_SFX_LOG"] = stubLog.absolutePath
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        RunResult(code, output, output.contains("Device Guard") || output.contains("Application Control"))
    } catch (error: java.io.IOException) {
        val output = error.message.orEmpty()
        RunResult(
            code = -1,
            output = output,
            policyBlocked = output.contains("Device Guard") || output.contains("Application Control") || output.contains("정책"),
        )
    }
}

private fun assertIconMatches(pe: ByteArray, ico: ByteArray) {
    val images = icoImages(ico)
    val icons = resourceBlobs(pe, 3)
    val groups = resourceBlobs(pe, 14)
    assertTrue(groups.isNotEmpty(), "RT_GROUP_ICON 이 없습니다.")
    assertEquals(images.size, icons.size)
    for (image in images) {
        assertTrue(icons.any { it.contentEquals(image) }, "ICO 이미지가 스텁 리소스에 없습니다.")
    }
}

private fun icoImages(ico: ByteArray): List<ByteArray> {
    require(ico.size >= 6)
    val count = u16(ico, 4)
    require(u16(ico, 0) == 0 && u16(ico, 2) == 1)
    return (0 until count).map { index ->
        val entry = 6 + index * 16
        val size = u32(ico, entry + 8)
        val offset = u32(ico, entry + 12)
        ico.copyOfRange(offset, offset + size)
    }
}

private fun resourceBlobs(pe: ByteArray, typeId: Int): List<ByteArray> {
    val lfanew = u32(pe, 0x3C)
    val sectionCount = u16(pe, lfanew + 6)
    val optionalSize = u16(pe, lfanew + 20)
    val magic = u16(pe, lfanew + 24)
    val dataDirectory = lfanew + 24 + if (magic == 0x20B) 112 else 96
    val resourceRva = u32(pe, dataDirectory + 16)
    val sectionOffset = lfanew + 24 + optionalSize
    fun rvaToOffset(rva: Int): Int {
        for (index in 0 until sectionCount) {
            val offset = sectionOffset + index * 40
            val virtual = u32(pe, offset + 12)
            val virtualSize = u32(pe, offset + 8)
            val rawSize = u32(pe, offset + 16)
            val raw = u32(pe, offset + 20)
            val span = maxOf(virtualSize, rawSize)
            if (rva >= virtual && rva < virtual + span) {
                return raw + (rva - virtual)
            }
        }
        throw IllegalArgumentException("RVA를 파일 오프셋으로 바꾸지 못했습니다: $rva")
    }
    val root = rvaToOffset(resourceRva)
    fun entries(offset: Int): List<Pair<Int, Int>> {
        val named = u16(pe, root + offset + 12)
        val ids = u16(pe, root + offset + 14)
        return (0 until named + ids).map { index ->
            val entry = root + offset + 16 + index * 8
            u32(pe, entry) to u32(pe, entry + 4)
        }
    }
    val type = entries(0).first { it.first == typeId }
    check(type.second and 0x80000000.toInt() != 0)
    return entries(type.second and 0x7FFFFFFF).flatMap { name ->
        check(name.second and 0x80000000.toInt() != 0)
        entries(name.second and 0x7FFFFFFF).map { language ->
            val leaf = language.second and 0x7FFFFFFF
            val dataRva = u32(pe, root + leaf)
            val size = u32(pe, root + leaf + 4)
            val fileOffset = rvaToOffset(dataRva)
            pe.copyOfRange(fileOffset, fileOffset + size)
        }
    }
}

private fun u16(bytes: ByteArray, offset: Int): Int {
    return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}

private fun u32(bytes: ByteArray, offset: Int): Int {
    return u16(bytes, offset) or (u16(bytes, offset + 2) shl 16)
}

private fun repoRoot(): File {
    val dir = File(System.getProperty("user.dir"))
    check(File(dir, "settings.gradle.kts").isFile) {
        "테스트 작업 디렉터리가 저장소 루트가 아닙니다: ${dir.absolutePath}"
    }
    return dir
}

private const val FIXTURE = """#include <windows.h>
int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR command, int show) {
  wchar_t path[32768];
  DWORD n;
  HANDLE file;
  DWORD written = 0;
  const char text[] = "launcher-started";
  (void)instance; (void)previous; (void)command; (void)show;
  n = GetModuleFileNameW(NULL, path, 32768);
  if (n == 0 || n >= 32768) return 2;
  while (n > 0 && path[n - 1] != L'\\') n--;
  lstrcpyW(path + n, L"launcher-started.txt");
  file = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
  if (file == INVALID_HANDLE_VALUE) return 3;
  if (!WriteFile(file, text, sizeof text - 1, &written, NULL)) {
    CloseHandle(file);
    return 4;
  }
  CloseHandle(file);
  return 0;
}
"""
