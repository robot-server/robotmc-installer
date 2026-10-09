package com.sysbot32.robotmc.installer.prelaunch

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

const val INSTALLER_APP_NAME = "RobotMC Installer"
const val INSTALLER_MAIN_CLASS = "com.sysbot32.robotmc.installer.RobotmcInstallerApplicationKt"

/**
 * 불일치일 때 에이전트가 실행하는 RobotMC Installer.
 * 앱 이미지 안이면 그 런처이고, 아니면 이 프로세스의 java 와 애플리케이션 진입점이다.
 */
fun installerLaunchCommand(): List<String> {
    return installerCommand(
        Path.of(System.getProperty("java.home")),
        installerCodeSource(),
        System.getProperty("java.class.path"),
        installerJavaExecutable(),
    )
}

fun installerJavaExecutable(): String {
    val windows = System.getProperty("os.name", "").lowercase(Locale.ROOT).startsWith("windows")
    val name = if (windows) "java.exe" else "java"
    return Path.of(System.getProperty("java.home"), "bin", name).toString()
}

fun installerCommand(javaHome: Path, codeSource: Path?, classPath: String, javaBin: String): List<String> {
    appImageLauncher(javaHome)?.let { return it }
    if (codeSource != null && Files.isRegularFile(codeSource) && codeSource.fileName.toString().endsWith(".jar")) {
        return listOf(javaBin, "-jar", codeSource.toAbsolutePath().normalize().toString())
    }
    return listOf(javaBin, "-cp", classPath, INSTALLER_MAIN_CLASS)
}

/**
 * jpackage 앱 이미지의 java.home 은 이미지 안의 runtime 이다.
 * 그 옆의 런처가 설치기 진입점이다.
 */
fun appImageLauncher(javaHome: Path): List<String>? {
    val home = javaHome.toAbsolutePath().normalize()
    if (home.endsWith(Path.of("Contents", "runtime", "Contents", "Home"))) {
        val contents = home.parent?.parent?.parent
        if (contents != null) {
            val launcher = contents.resolve("MacOS").resolve(INSTALLER_APP_NAME)
            if (Files.isRegularFile(launcher)) {
                return listOf(launcher.toString())
            }
        }
    }
    if (home.fileName?.toString() != "runtime") {
        return null
    }
    val parent = home.parent ?: return null
    if (parent.fileName?.toString() == "lib") {
        val image = parent.parent
        if (image != null) {
            val launcher = image.resolve("bin").resolve(INSTALLER_APP_NAME)
            if (Files.isRegularFile(launcher)) {
                return listOf(launcher.toString())
            }
        }
    }
    val windows = parent.resolve("$INSTALLER_APP_NAME.exe")
    if (Files.isRegularFile(windows)) {
        return listOf(windows.toString())
    }
    return null
}

private fun installerCodeSource(): Path? {
    return try {
        val application = Class.forName("com.sysbot32.robotmc.installer.RobotmcInstallerApplication")
        val location = application.protectionDomain?.codeSource?.location ?: return null
        var external = location.toString()
        if (external.startsWith("jar:")) {
            external = external.removePrefix("jar:")
        }
        if (external.startsWith("nested:")) {
            external = external.removePrefix("nested:")
        }
        val bang = external.indexOf("!/")
        if (bang >= 0) {
            external = external.substring(0, bang)
        }
        if (external.startsWith("file:")) {
            external = external.removePrefix("file:")
        }
        val file = Path.of(URLDecoder.decode(external, StandardCharsets.UTF_8))
        if (Files.isRegularFile(file) && file.fileName.toString().endsWith(".jar")) {
            file.toAbsolutePath().normalize()
        } else {
            null
        }
    } catch (exception: Exception) {
        null
    }
}
