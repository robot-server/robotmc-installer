package com.sysbot32.robotmc.installer

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Windows 릴리스 exe는 우리 스텁 뒤에 앱 이미지 7z를 붙인 파일이다.
 *
 * 스텁은 퍼블릭 도메인 LZMA SDK로 7z를 풀어 `%LOCALAPPDATA%\RobotMC Installer`에 두고
 * `RobotMC Installer.exe`를 실행한다. 푼 파일은 지우지 않는다.
 * 7-Zip SFX 모듈이나 그 수정본을 받거나 앞에 붙이지 않는다.
 */
object WindowsSfxPack {
    const val LAUNCHER_FILE_NAME = "RobotMC Installer.exe"
    const val INSTALL_DIR_NAME = "RobotMC Installer"
    const val LOCAL_APP_DATA = "LOCALAPPDATA"
    const val RUNTIME_DIRECTORY = "runtime"
    const val OUTPUT_DIR = "windows-sfx"
    const val STUB_RELATIVE_PATH = "packaging/windows-sfx/stub/sfxstub.c"
    const val LZMA_SDK_RELATIVE_DIR = "packaging/windows-sfx/lzma-sdk/C"
    const val LZMA_SDK_LICENSE_RELATIVE_PATH = "packaging/windows-sfx/lzma-sdk/DOC/lzma-sdk.txt"
    const val NOTICE_RELATIVE_PATH = "packaging/windows-sfx/NOTICE.txt"
    const val ICON_RELATIVE_PATH = "src/main/icon/installer-app-icon.ico"
    const val LZMA_SDK_VERSION = "26.04"

    /** 링크하는 LZMA SDK 디코더. 인코더와 SFX 모듈 소스는 넣지 않는다. */
    val DECODER_SOURCE_NAMES = listOf(
        "7zAlloc.c",
        "7zArcIn.c",
        "7zBuf.c",
        "7zBuf2.c",
        "7zCrc.c",
        "7zCrcOpt.c",
        "7zDec.c",
        "7zStream.c",
        "Bcj2.c",
        "Bra.c",
        "Bra86.c",
        "BraIA64.c",
        "CpuArch.c",
        "Delta.c",
        "Lzma2Dec.c",
        "LzmaDec.c",
    )

    fun stubSource(projectDir: File): File = File(projectDir, STUB_RELATIVE_PATH)

    fun lzmaSdkDir(projectDir: File): File = File(projectDir, LZMA_SDK_RELATIVE_DIR)

    fun lzmaSdkLicense(projectDir: File): File = File(projectDir, LZMA_SDK_LICENSE_RELATIVE_PATH)

    fun noticeFile(projectDir: File): File = File(projectDir, NOTICE_RELATIVE_PATH)

    fun iconFile(projectDir: File): File = File(projectDir, ICON_RELATIVE_PATH)

    fun decoderSources(projectDir: File): List<File> {
        return DECODER_SOURCE_NAMES.map { name -> File(lzmaSdkDir(projectDir), name) }
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

    /** 7z가 BCJ/BCJ2 필터를 붙이지 않도록 메서드를 LZMA2 하나로 고정한다. */
    fun payloadArguments(payload: File, listFile: File): List<String> {
        return listOf(
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
    }

    fun iconResourceScript(iconFile: File, manifestFile: File): String {
        val icon = iconFile.absolutePath.replace('\\', '/')
        val manifest = manifestFile.absolutePath.replace('\\', '/')
        return "1 ICON \"$icon\"\r\n1 24 \"$manifest\"\r\n"
    }

    fun requireOurStub(stub: ByteArray) {
        if (stub.size < 64 || stub[0] != 'M'.code.toByte() || stub[1] != 'Z'.code.toByte()) {
            throw IllegalArgumentException("SFX 스텁이 PE가 아닙니다.")
        }
        if (indexOf(stub, "Scherbakov".toByteArray(StandardCharsets.US_ASCII)) >= 0) {
            throw IllegalArgumentException("수정 SFX 모듈은 붙이지 않습니다.")
        }
        if (indexOf(stub, "7zsd_All_x64".toByteArray(StandardCharsets.US_ASCII)) >= 0) {
            throw IllegalArgumentException("수정 SFX 모듈은 붙이지 않습니다.")
        }
    }

    fun requirePayload(payload: ByteArray) {
        if (payload.size < 6 || payload[0] != '7'.code.toByte() || payload[1] != 'z'.code.toByte()
            || payload[2] != 0xBC.toByte() || payload[3] != 0xAF.toByte()
            || payload[4] != 0x27.toByte() || payload[5] != 0x1C.toByte()
        ) {
            throw IllegalArgumentException("페이로드가 7z가 아닙니다.")
        }
    }

    fun writeAssembledExe(stub: ByteArray, payload: File, destination: File) {
        requireOurStub(stub)
        val header = ByteArray(6)
        payload.inputStream().use { input ->
            val read = input.read(header)
            if (read < header.size) {
                throw IllegalArgumentException("페이로드가 7z가 아닙니다.")
            }
        }
        requirePayload(header)
        val parent = destination.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IllegalStateException("SFX 출력 폴더를 만들지 못했습니다: ${parent.absolutePath}")
        }
        destination.outputStream().use { out ->
            out.write(stub)
            payload.inputStream().use { input -> input.copyTo(out) }
        }
    }

    fun compileStub(projectDir: File, iconFile: File, workDir: File, destination: File): File {
        val sources = listOf(stubSource(projectDir)) + decoderSources(projectDir)
        sources.filter { !it.isFile }.takeIf { it.isNotEmpty() }?.let { missing ->
            throw IllegalStateException("SFX 소스가 없습니다: ${missing.joinToString { it.path }}")
        }
        if (!iconFile.isFile) {
            throw IllegalStateException("SFX 아이콘이 없습니다: ${iconFile.absolutePath}")
        }
        return compileWindowsProgram(
            sources = sources,
            iconFile = iconFile,
            includeDir = lzmaSdkDir(projectDir),
            workDir = workDir,
            destination = destination,
        )
    }

    fun compileWindowsProgram(
        sources: List<File>,
        iconFile: File?,
        includeDir: File?,
        workDir: File,
        destination: File,
    ): File {
        if (sources.isEmpty() || sources.any { !it.isFile }) {
            throw IllegalArgumentException("컴파일할 C 소스가 없습니다.")
        }
        if (!workDir.isDirectory && !workDir.mkdirs()) {
            throw IllegalStateException("SFX 컴파일 폴더를 만들지 못했습니다: ${workDir.absolutePath}")
        }
        val manifest = File(workDir, "stub.manifest")
        manifest.writeText(LONG_PATH_MANIFEST, StandardCharsets.US_ASCII)
        val resource = if (iconFile != null) {
            if (!iconFile.isFile) {
                throw IllegalStateException("SFX 아이콘이 없습니다: ${iconFile.absolutePath}")
            }
            val rc = File(workDir, "stub.rc")
            rc.writeText(iconResourceScript(iconFile, manifest), StandardCharsets.US_ASCII)
            rc
        } else {
            null
        }
        val msvc = findVcVars64()
        if (msvc != null) {
            compileWithMsvc(msvc, sources, resource, includeDir, workDir, destination)
        } else {
            compileWithMingw(sources, resource, includeDir, workDir, destination)
        }
        if (!destination.isFile) {
            throw IllegalStateException("SFX 스텁이 만들어지지 않았습니다: ${destination.absolutePath}")
        }
        return destination
    }

    fun pack(
        imageDir: File,
        stubFile: File,
        sevenZip: File,
        destination: File,
    ) {
        val entries = sfxArchiveEntries(imageDir)
        if (!stubFile.isFile) {
            throw IllegalStateException("SFX 스텁이 없습니다: ${stubFile.absolutePath}")
        }
        val stub = stubFile.readBytes()
        requireOurStub(stub)
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
            runProcess(
                listOf(sevenZip.absolutePath) + payloadArguments(payload, listFile),
                imageDir,
                "7z가 실패했습니다",
            )
            writeAssembledExe(stub, payload, destination)
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
            ?: throw IllegalStateException("7z.exe가 없습니다. 7-Zip을 설치한 뒤 다시 실행합니다.")
    }

    private fun compileWithMsvc(
        vcvars: File,
        sources: List<File>,
        resourceScript: File?,
        includeDir: File?,
        workDir: File,
        destination: File,
    ) {
        val resource = if (resourceScript != null) {
            val compiled = File(workDir, "stub.res")
            val script = File(workDir, "compile-resource.cmd")
            script.writeText(
                "@echo off\r\n" +
                    "call \"${vcvars.absolutePath}\" > \"${File(workDir, "vcvars.log").absolutePath}\" 2>&1\r\n" +
                    "if errorlevel 1 exit /b 1\r\n" +
                    "rc /nologo /fo \"${compiled.absolutePath}\" \"${resourceScript.absolutePath}\"\r\n" +
                    "exit /b %ERRORLEVEL%\r\n",
                StandardCharsets.US_ASCII,
            )
            runProcess(listOf("cmd.exe", "/c", script.absolutePath), workDir, "리소스 컴파일이 실패했습니다")
            compiled
        } else {
            null
        }
        val response = File(workDir, "compile.rsp")
        val lines = mutableListOf(
            "/nologo",
            "/O1",
            "/W3",
            "/MT",
            "/D_7ZIP_ST",
        )
        if (includeDir != null) {
            lines += "/I\"${includeDir.absolutePath}\""
        }
        lines += "/Fe\"${destination.absolutePath}\""
        lines += sources.map { "\"${it.absolutePath}\"" }
        lines += "/link"
        lines += "/SUBSYSTEM:WINDOWS"
        lines += "/ENTRY:wWinMainCRTStartup"
        if (resource != null) {
            lines += "\"${resource.absolutePath}\""
        }
        response.writeText(lines.joinToString("\r\n") + "\r\n", StandardCharsets.US_ASCII)
        val script = File(workDir, "compile-stub.cmd")
        script.writeText(
            "@echo off\r\n" +
                "call \"${vcvars.absolutePath}\" > \"${File(workDir, "vcvars.log").absolutePath}\" 2>&1\r\n" +
                "if errorlevel 1 exit /b 1\r\n" +
                "cl @\"${response.absolutePath}\"\r\n" +
                "exit /b %ERRORLEVEL%\r\n",
            StandardCharsets.US_ASCII,
        )
        runProcess(listOf("cmd.exe", "/c", script.absolutePath), workDir, "SFX 스텁을 컴파일하지 못했습니다")
    }

    private fun compileWithMingw(
        sources: List<File>,
        resourceScript: File?,
        includeDir: File?,
        workDir: File,
        destination: File,
    ) {
        val gcc = findOnPath("gcc.exe") ?: findOnPath("gcc")
        val windres = findOnPath("windres.exe") ?: findOnPath("windres")
        if (gcc == null || windres == null) {
            throw IllegalStateException(
                "SFX 스텁을 컴파일할 cl 또는 gcc가 없습니다. Visual Studio의 vcvars64.bat 또는 gcc와 windres를 PATH에 둡니다.",
            )
        }
        val resource = if (resourceScript != null) {
            val compiled = File(workDir, "stub.res")
            runProcess(
                listOf(windres.absolutePath, "-O", "coff", "-o", compiled.absolutePath, resourceScript.absolutePath),
                workDir,
                "리소스 컴파일이 실패했습니다",
            )
            compiled
        } else {
            null
        }
        val command = mutableListOf(
            gcc.absolutePath,
            "-O2",
            "-s",
            "-static",
            "-mwindows",
            "-municode",
            "-D_7ZIP_ST",
        )
        if (includeDir != null) {
            command += "-I${includeDir.absolutePath}"
        }
        command += listOf("-o", destination.absolutePath)
        command += sources.map { it.absolutePath }
        if (resource != null) {
            command += resource.absolutePath
        }
        runProcess(command, workDir, "SFX 스텁을 컴파일하지 못했습니다")
    }

    private fun findVcVars64(): File? {
        val vswhere = listOf(
            File("C:/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe"),
            File("C:/Program Files/Microsoft Visual Studio/Installer/vswhere.exe"),
        ).firstOrNull { it.isFile } ?: return null
        val process = ProcessBuilder(
            listOf(
                vswhere.absolutePath,
                "-latest",
                "-products",
                "*",
                "-requires",
                "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                "-property",
                "installationPath",
            ),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
        if (process.waitFor() != 0 || output.isEmpty()) {
            return null
        }
        val install = File(output.lineSequence().first { it.isNotBlank() })
        val vcvars = File(install, "VC/Auxiliary/Build/vcvars64.bat")
        return vcvars.takeIf { it.isFile }
    }

    private fun findOnPath(name: String): File? {
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        for (dir in path) {
            val candidate = File(dir, name)
            if (candidate.isFile) {
                return candidate
            }
        }
        return null
    }

    private fun runProcess(command: List<String>, directory: File, failure: String) {
        val process = ProcessBuilder(command)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val code = process.waitFor()
        if (code != 0) {
            throw IllegalStateException("$failure ($code): $output")
        }
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

    private const val LONG_PATH_MANIFEST = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">
  <application xmlns="urn:schemas-microsoft-com:asm.v3">
    <windowsSettings>
      <longPathAware xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">true</longPathAware>
    </windowsSettings>
  </application>
</assembly>
"""
}
