package com.sysbot32.robotmc.installer.replace

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * 설치기 프로세스가 끝난 뒤에 스테이지한 바이트를 설치기 파일로 옮기고 그 파일을 실행한다.
 * 이 모듈의 JAR로 띄운다. 실행 중인 설치기 파일은 클래스패스로 쓰지 않는다.
 */
class InstallerReplace private constructor() {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            var target: String? = null
            var staged: String? = null
            var javaBin: String? = null
            var waitPid: String? = null
            var index = 0
            while (index < args.size) {
                val key = args[index]
                if (index + 1 >= args.size) {
                    throw IllegalArgumentException("Missing value for $key")
                }
                val value = args[index + 1]
                index += 2
                when (key) {
                    "--target" -> target = value
                    "--staged" -> staged = value
                    "--java" -> javaBin = value
                    "--wait-pid" -> waitPid = value
                    else -> throw IllegalArgumentException("Unknown argument $key")
                }
            }
            if (target == null || staged == null) {
                throw IllegalArgumentException("target and staged are required")
            }
            if (!waitPid.isNullOrBlank()) {
                val pid = waitPid.trim().toLong()
                ProcessHandle.of(pid).ifPresent { handle -> handle.onExit().join() }
            }
            val targetPath = Path.of(target)
            move(Path.of(staged), targetPath)
            val javaCommand = if (javaBin.isNullOrBlank()) defaultJava() else javaBin
            val command = listOf(
                javaCommand,
                "-jar",
                targetPath.toAbsolutePath().normalize().toString(),
            )
            println(command.joinToString("\u0000"))
            System.out.flush()
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }

        private fun move(staged: Path, target: Path) {
            if (!Files.isRegularFile(staged)) {
                throw IOException("staged installer is missing")
            }
            target.parent?.let { Files.createDirectories(it) }
            try {
                Files.move(
                    staged,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun defaultJava(): String {
            val windows = System.getProperty("os.name", "").lowercase(Locale.ROOT).startsWith("windows")
            val name = if (windows) "java.exe" else "java"
            return Path.of(System.getProperty("java.home"), "bin", name).toString()
        }
    }
}
