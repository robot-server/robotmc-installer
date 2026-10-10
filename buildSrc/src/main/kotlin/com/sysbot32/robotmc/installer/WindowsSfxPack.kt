package com.sysbot32.robotmc.installer

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Windows 릴리스가 묶는 7-Zip SFX.
 *
 * 공식 7zSD.sfx는 임시 폴더에 풀고 프로그램을 끝낸 뒤 그 폴더를 지운다.
 * 여기에 붙이는 스텁은 Oleg Scherbakov의 수정 모듈이다. InstallPath가 있으면 그 폴더를 남긴다.
 *
 * 스텁은 GNU LGPL 2.1 이상이다. 저작권은 Igor Pavlov와 Oleg Scherbakov에게 있다.
 * 설치기 코드와 링크하지 않고, 스텁 뒤에 이 설정과 7z 페이로드를 이어 붙인다.
 * 모듈 소스: https://github.com/OlegScherbakov/7zSFX
 */
object WindowsSfxPack {
    const val CONFIG_RELATIVE_PATH = "packaging/windows-sfx/config.txt"
    const val LAUNCHER_FILE_NAME = "RobotMC Installer.exe"
    const val RUNTIME_DIRECTORY = "runtime"
    const val OUTPUT_DIR = "windows-sfx"

    /** 수정 모듈 바이너리의 저작권 표기. 공식 7zSD.sfx에는 없다. */
    const val MODIFIED_MODULE_MARKER = "Scherbakov"

    fun configFile(projectDir: File): File = File(projectDir, CONFIG_RELATIVE_PATH)

    fun shippedConfigText(projectDir: File): String {
        val file = configFile(projectDir)
        if (!file.isFile) {
            throw IllegalStateException("SFX 설정이 없습니다: ${file.absolutePath}")
        }
        return file.readText(StandardCharsets.UTF_8)
    }

    fun outputExe(buildDir: File, version: String): File {
        return File(buildDir, "$OUTPUT_DIR/robotmc-installer-$version.exe")
    }

    /**
     * 앱 이미지 폴더 안의 파일 전체.
     * 런처는 아카이브 루트에 있고 runtime은 그 옆이다. 런처만 넣으면 포함 JDK가 빠져 실행되지 않는다.
     */
    fun sfxArchiveEntries(imageDir: File): List<File> {
        if (!imageDir.isDirectory) {
            throw IllegalArgumentException("앱 이미지가 없습니다: ${imageDir.absolutePath}")
        }
        val files = imageDir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(imageDir).invariantSeparatorsPath }
            .toList()
        val relative = files.map { it.relativeTo(imageDir).invariantSeparatorsPath }
        if (LAUNCHER_FILE_NAME !in relative) {
            throw IllegalArgumentException("앱 이미지 루트에 $LAUNCHER_FILE_NAME 이 없습니다.")
        }
        if (relative.none { it == RUNTIME_DIRECTORY || it.startsWith("$RUNTIME_DIRECTORY/") }) {
            throw IllegalArgumentException("앱 이미지에 $RUNTIME_DIRECTORY 가 없습니다.")
        }
        return files
    }

    fun requireModifiedSfxModule(module: ByteArray) {
        if (module.size < 64 || module[0] != 'M'.code.toByte() || module[1] != 'Z'.code.toByte()) {
            throw IllegalArgumentException("SFX 모듈이 PE가 아닙니다.")
        }
        if (indexOf(module, MODIFIED_MODULE_MARKER.toByteArray(StandardCharsets.US_ASCII)) < 0) {
            throw IllegalArgumentException("공식 7zSD.sfx는 InstallPath를 유지하지 않습니다. 수정 모듈이 필요합니다.")
        }
        if (indexOf(module, "InstallPath".toByteArray(StandardCharsets.UTF_16LE)) < 0) {
            throw IllegalArgumentException("SFX 모듈이 InstallPath를 다루지 않습니다.")
        }
    }

    fun concatenate(module: ByteArray, config: ByteArray, payload: ByteArray): ByteArray {
        requireModifiedSfxModule(module)
        val text = config.toString(StandardCharsets.UTF_8)
        if (!text.contains(";!@Install@!UTF-8!") || !text.contains(";!@InstallEnd@!")) {
            throw IllegalArgumentException("SFX 설정 마커가 없습니다.")
        }
        if (payload.size < 6 || payload[0] != '7'.code.toByte() || payload[1] != 'z'.code.toByte()) {
            throw IllegalArgumentException("페이로드가 7z가 아닙니다.")
        }
        return module + config + payload
    }

    fun pack(
        imageDir: File,
        configFile: File,
        moduleFile: File,
        sevenZip: File,
        destination: File,
    ) {
        val entries = sfxArchiveEntries(imageDir)
        val module = moduleFile.readBytes()
        val config = configFile.readBytes()
        val work = File(destination.parentFile, "work")
        if (work.exists() && !work.deleteRecursively()) {
            throw IllegalStateException("SFX 작업 폴더를 지우지 못했습니다: ${work.absolutePath}")
        }
        if (!work.mkdirs()) {
            throw IllegalStateException("SFX 작업 폴더를 만들지 못했습니다: ${work.absolutePath}")
        }
        try {
            val payload = File(work, "payload.7z")
            val listFile = File(work, "files.txt")
            listFile.writeText(
                entries.joinToString("\n") { it.relativeTo(imageDir).invariantSeparatorsPath } + "\n",
                StandardCharsets.UTF_8,
            )
            val command = listOf(
                sevenZip.absolutePath,
                "a",
                "-t7z",
                "-m0=LZMA2",
                "-mx=5",
                "-scsUTF-8",
                "-y",
                "-bso0",
                "-bsp0",
                payload.absolutePath,
                "@${listFile.absolutePath}",
            )
            val process = ProcessBuilder(command)
                .directory(imageDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            val code = process.waitFor()
            if (code != 0) {
                throw IllegalStateException("7z가 실패했습니다 ($code): $output")
            }
            if (!destination.parentFile.isDirectory && !destination.parentFile.mkdirs()) {
                throw IllegalStateException("SFX 출력 폴더를 만들지 못했습니다: ${destination.parent}")
            }
            destination.writeBytes(concatenate(module, config, payload.readBytes()))
        } finally {
            work.deleteRecursively()
        }
    }

    fun findSevenZip(): File {
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        for (dir in path) {
            for (name in listOf("7z.exe", "7z")) {
                val candidate = File(dir, name)
                if (candidate.isFile) {
                    return candidate
                }
            }
        }
        val standard = listOf(
            File("C:/Program Files/7-Zip/7z.exe"),
            File("C:/Program Files (x86)/7-Zip/7z.exe"),
        )
        return standard.firstOrNull { it.isFile }
            ?: throw IllegalStateException("7z.exe가 없습니다.")
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) {
            return -1
        }
        for (start in 0..haystack.size - needle.size) {
            var matched = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                return start
            }
        }
        return -1
    }
}
