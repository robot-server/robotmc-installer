package com.sysbot32.robotmc.installer.launcher

import com.sysbot32.robotmc.installer.exception.UserException
import com.sysbot32.robotmc.installer.prelaunch.INSTALLER_APP_NAME
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator

const val STABLE_INSTALLER_JAR = "robotmc-installer.jar"

/**
 * 사용자가 받은 설치기를 지워도 다음에 띄울 수 있게 홈에 복사한 명령을 돌려준다.
 * 앱 이미지는 실행 파일과 JAR 과 JRE 가 한 묶음이라 통째로 복사하고, java -jar 는 JAR 만 복사한다.
 * 클래스패스로 띄운 개발 실행은 복사할 파일이 없으므로 그대로 둔다.
 */
fun stableInstallerCommand(command: List<String>, home: Path): List<String> {
    val directory = home.toAbsolutePath().normalize()
    Files.createDirectories(directory)
    installerImage(command)?.let { image ->
        val destination = directory.resolve(image.root.fileName)
        copyInstallerTree(image.root, destination)
        val launcher = destination.resolve(image.root.relativize(image.launcher))
        launcher.toFile().setExecutable(true)
        return listOf(launcher.toString())
    }
    val jarIndex = command.indexOf("-jar")
    if (jarIndex >= 0 && jarIndex + 1 < command.size) {
        val jar = Path.of(command[jarIndex + 1]).toAbsolutePath().normalize()
        if (Files.isRegularFile(jar)) {
            val destination = directory.resolve(STABLE_INSTALLER_JAR)
            copyInstallerFile(jar, destination)
            return command.toMutableList().also { rewritten ->
                rewritten[jarIndex + 1] = destination.toString()
            }
        }
    }
    return command
}

private data class InstallerImage(
    val launcher: Path,
    val root: Path,
)

private fun installerImage(command: List<String>): InstallerImage? {
    if (command.size != 1) {
        return null
    }
    val launcher = Path.of(command[0]).toAbsolutePath().normalize()
    if (!Files.isRegularFile(launcher)) {
        return null
    }
    val name = launcher.fileName.toString()
    val macApp = launcher.parent?.parent?.parent
    if (launcher.parent?.fileName?.toString() == "MacOS" &&
        launcher.parent?.parent?.fileName?.toString() == "Contents" &&
        macApp?.fileName?.toString()?.endsWith(".app") == true &&
        name == INSTALLER_APP_NAME
    ) {
        return InstallerImage(launcher, macApp)
    }
    if (launcher.parent?.fileName?.toString() == "bin" && name == INSTALLER_APP_NAME) {
        val root = launcher.parent?.parent
        if (root != null && Files.isDirectory(root.resolve("lib").resolve("runtime"))) {
            return InstallerImage(launcher, root)
        }
    }
    if (name == "$INSTALLER_APP_NAME.exe") {
        val root = launcher.parent
        if (root != null && Files.isDirectory(root.resolve("runtime"))) {
            return InstallerImage(launcher, root)
        }
    }
    return null
}

private fun copyInstallerFile(source: Path, destination: Path) {
    val src = source.toAbsolutePath().normalize()
    val dest = destination.toAbsolutePath().normalize()
    if (src == dest) {
        return
    }
    dest.parent?.let { Files.createDirectories(it) }
    val staging = dest.resolveSibling("${dest.fileName}.staging")
    try {
        Files.copy(src, staging, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        moveReplacing(staging, dest)
    } catch (exception: Exception) {
        Files.deleteIfExists(staging)
        if (exception is UserException) {
            throw exception
        }
        throw UserException("설치기를 보관하지 못했어요.", exception)
    }
}

private fun copyInstallerTree(source: Path, destination: Path) {
    val src = source.toAbsolutePath().normalize()
    val dest = destination.toAbsolutePath().normalize()
    if (src == dest) {
        return
    }
    if (dest.startsWith(src)) {
        throw UserException("설치기를 그 폴더 안에 복사할 수 없어요.")
    }
    val staging = dest.resolveSibling("${dest.fileName}.staging")
    deleteTree(staging)
    try {
        Files.walk(src).use { stream ->
            for (path in stream) {
                val target = staging.resolve(src.relativize(path).toString())
                when {
                    Files.isSymbolicLink(path) -> {
                        target.parent?.let { Files.createDirectories(it) }
                        Files.deleteIfExists(target)
                        Files.createSymbolicLink(target, Files.readSymbolicLink(path))
                    }
                    Files.isDirectory(path) -> Files.createDirectories(target)
                    else -> {
                        target.parent?.let { Files.createDirectories(it) }
                        Files.copy(
                            path,
                            target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES,
                        )
                    }
                }
            }
        }
        deleteTree(dest)
        moveReplacing(staging, dest)
    } catch (exception: Exception) {
        deleteTree(staging)
        if (exception is UserException) {
            throw exception
        }
        throw UserException("설치기를 보관하지 못했어요.", exception)
    }
}

private fun moveReplacing(source: Path, destination: Path) {
    try {
        Files.move(
            source,
            destination,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (exception: AtomicMoveNotSupportedException) {
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun deleteTree(path: Path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        return
    }
    Files.walk(path).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
