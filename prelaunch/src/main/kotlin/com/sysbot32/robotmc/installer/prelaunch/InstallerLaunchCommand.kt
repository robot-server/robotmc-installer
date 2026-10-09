package com.sysbot32.robotmc.installer.prelaunch

import java.net.URI
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
        val path = installerJarPath(location.toString()) ?: return null
        val file = Path.of(path)
        if (Files.isRegularFile(file) && file.fileName.toString().endsWith(".jar")) {
            file.toAbsolutePath().normalize()
        } else {
            null
        }
    } catch (exception: Exception) {
        null
    }
}

/**
 * Spring Boot bootJar 의 CodeSource 는 `jar:nested:/app.jar/!BOOT-INF/classes/!/` 이다.
 * 바깥 구분자는 `!/` 가 아니라 `/!` 라서, `!/` 만 찾으면 classes 쪽에서 잘린다.
 */
fun installerJarPath(location: String): String? {
    var external = location
    if (external.startsWith("jar:")) {
        external = external.removePrefix("jar:")
    }
    if (external.startsWith("nested:")) {
        external = external.removePrefix("nested:")
    }
    val nested = external.indexOf("/!")
    if (nested >= 0) {
        external = external.substring(0, nested)
    } else {
        val bang = external.indexOf("!/")
        if (bang >= 0) {
            external = external.substring(0, bang)
        }
    }
    if (external.startsWith("file:")) {
        external = external.removePrefix("file:")
    }
    return decodePath(external)
}

/** `+` 는 경로 문자다. URLDecoder 는 그것을 공백으로 바꾼다. */
private fun decodePath(external: String): String? {
    if (external.isBlank()) {
        return null
    }
    return try {
        val uri = if (external.startsWith("/")) "file:$external" else "file:/$external"
        URI.create(uri).path?.ifBlank { null } ?: external
    } catch (exception: Exception) {
        external
    }
}
