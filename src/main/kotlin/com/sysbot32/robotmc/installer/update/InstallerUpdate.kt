package com.sysbot32.robotmc.installer.update

import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.config.httpsRedirect
import com.sysbot32.robotmc.installer.gui.applicationVersion
import com.sysbot32.robotmc.installer.prelaunch.installerJarPath
import com.sysbot32.robotmc.installer.prelaunch.installerJavaExecutable
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val log = KotlinLogging.logger { }

private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

internal val INSTALLER_OS_KEYS = listOf("windows", "macos", "linux")

private const val MAX_REDIRECTS = 3

private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

private val BODY_TIMEOUT: Duration = Duration.ofSeconds(60)

internal enum class InstallerOs {
    WINDOWS,
    MACOS,
    LINUX,
}

internal fun installerOs(osName: String): InstallerOs? {
    val name = osName.trim().lowercase(Locale.ROOT)
    return when {
        name.startsWith("windows") -> InstallerOs.WINDOWS
        name.startsWith("mac") || name.contains("darwin") -> InstallerOs.MACOS
        name.startsWith("linux") -> InstallerOs.LINUX
        else -> null
    }
}

internal fun InstallerProperties.Update.App.artifact(os: InstallerOs): InstallerProperties.Update.App.Artifact {
    return when (os) {
        InstallerOs.WINDOWS -> this.windows
        InstallerOs.MACOS -> this.macos
        InstallerOs.LINUX -> this.linux
    }
}

internal fun installerArtifact(
    app: InstallerProperties.Update.App,
    osName: String,
): InstallerProperties.Update.App.Artifact? {
    val os = installerOs(osName) ?: return null
    val artifact = app.artifact(os)
    val url = artifact.url.trim()
    val sha = artifact.sha256.trim()
    if (!validHttpsUrl(url) || !validSha256(sha)) {
        return null
    }
    return artifact
}

internal fun validHttpsUrl(value: String): Boolean {
    if (!value.startsWith("https://")) {
        return false
    }
    return try {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }
}

internal fun validSha256(value: String): Boolean = value.matches(SHA256)

internal data class SelectedInstallerArtifact(
    val version: String,
    val os: InstallerOs,
    val uri: URI,
    val sha256: String,
)

/**
 * 패키지 버전과 다르고, 실행 중인 OS의 URL 이 https 이고 SHA-256 이 64자리일 때만 고른다.
 * 다른 OS 주소는 결과에 넣지 않는다.
 */
internal fun selectInstallerArtifact(
    app: InstallerProperties.Update.App,
    packagedVersion: String?,
    osName: String,
): SelectedInstallerArtifact? {
    val packaged = packagedVersion?.trim().orEmpty()
    if (packaged.isEmpty()) {
        return null
    }
    val remote = app.version.trim()
    if (remote.isEmpty() || remote == packaged) {
        return null
    }
    val os = installerOs(osName) ?: return null
    val artifact = installerArtifact(app, osName) ?: return null
    val uri = try {
        URI(artifact.url.trim())
    } catch (_: Exception) {
        return null
    }
    return SelectedInstallerArtifact(
        version = remote,
        os = os,
        uri = uri,
        sha256 = artifact.sha256.trim().lowercase(Locale.ROOT),
    )
}

internal data class PreparedInstallerUpdate(
    val target: Path,
    val staged: Path,
    val sha256: String,
)

internal fun installerStagingPath(target: Path): Path {
    return target.resolveSibling("${target.fileName}.staged")
}

private fun installerPartialPath(target: Path): Path {
    return target.resolveSibling("${target.fileName}.partial")
}

/**
 * 고른 주소만 받아 SHA-256 이 맞으면 설치기 옆에 스테이지한다.
 * 실행 중인 설치기 파일은 열어서 쓰지 않는다. 교체는 그 프로세스가 끝난 뒤 installer-replace.jar 가 한다.
 */
internal fun prepareInstallerUpdate(
    app: InstallerProperties.Update.App,
    packagedVersion: String?,
    osName: String,
    target: Path,
    fetch: (URI) -> InputStream,
): PreparedInstallerUpdate? {
    val selected = selectInstallerArtifact(app, packagedVersion, osName) ?: return null
    if (!Files.isRegularFile(target)) {
        return null
    }
    val partial = installerPartialPath(target)
    val staged = installerStagingPath(target)
    check(partial.toAbsolutePath().normalize() != target.toAbsolutePath().normalize()) {
        "staging path must not be the live installer"
    }
    val actual = try {
        fetch(selected.uri).use { input ->
            Files.newOutputStream(partial).use { output ->
                copyHashed(input, output, BODY_TIMEOUT)
            }
        }
    } catch (exception: Exception) {
        Files.deleteIfExists(partial)
        log.warn(exception) { "Installer download failed" }
        return null
    }
    if (!actual.equals(selected.sha256, ignoreCase = true)) {
        Files.deleteIfExists(partial)
        log.warn { "Installer download hash did not match" }
        return null
    }
    return try {
        moveReplacing(partial, staged)
        PreparedInstallerUpdate(target = target, staged = staged, sha256 = actual)
    } catch (exception: Exception) {
        Files.deleteIfExists(partial)
        log.warn(exception) { "Installer download was not staged" }
        null
    }
}

/**
 * 받을 설치기가 있을 때만 [launch] 를 부른다. [launch] 는 지금 파일을 덮어쓰지 않는 교체 예약이다.
 */
internal fun armInstallerUpdate(
    app: InstallerProperties.Update.App,
    packagedVersion: String?,
    osName: String,
    target: Path,
    fetch: (URI) -> InputStream,
    launch: (PreparedInstallerUpdate) -> Unit,
): PreparedInstallerUpdate? {
    val prepared = prepareInstallerUpdate(app, packagedVersion, osName, target, fetch) ?: return null
    launch(prepared)
    return prepared
}

internal fun armRunningInstallerUpdate(properties: InstallerProperties) {
    val target = runningInstallerJar()
    if (target == null) {
        val due = selectInstallerArtifact(
            properties.update.app,
            applicationVersion(),
            System.getProperty("os.name", ""),
        ) != null
        if (due) {
            log.info { "Installer update skipped because this process is not an installer jar" }
        }
        return
    }
    val prepared = armInstallerUpdate(
        app = properties.update.app,
        packagedVersion = applicationVersion(),
        osName = System.getProperty("os.name", ""),
        target = target,
        fetch = ::openHttpsArtifact,
        launch = { staged ->
            spawnInstallerReplace(staged.target, staged.staged, ProcessHandle.current().pid())
        },
    ) ?: return
    log.info { "Installer update ${properties.update.app.version} staged at ${prepared.staged}" }
}

internal fun runningInstallerJar(
    codeSource: String? = RobotmcInstallerApplicationClass.codeSource(),
    javaHome: Path = Path.of(System.getProperty("java.home", "")),
): Path? {
    if (!codeSource.isNullOrBlank()) {
        val path = installerJarPath(codeSource)
        if (path != null) {
            val file = Path.of(path)
            if (Files.isRegularFile(file) && file.fileName.toString().endsWith(".jar")) {
                return file.toAbsolutePath().normalize()
            }
        }
    }
    return appImageInstallerJar(javaHome)
}

private object RobotmcInstallerApplicationClass {
    fun codeSource(): String? {
        return try {
            val application = Class.forName("com.sysbot32.robotmc.installer.RobotmcInstallerApplication")
            application.protectionDomain?.codeSource?.location?.toString()
        } catch (_: Exception) {
            null
        }
    }
}

internal fun appImageInstallerJar(javaHome: Path): Path? {
    val home = javaHome.toAbsolutePath().normalize()
    val appDir = when {
        home.endsWith(Path.of("Contents", "runtime", "Contents", "Home")) ->
            home.parent?.parent?.parent?.resolve("app")
        home.fileName?.toString() == "runtime" && home.parent?.fileName?.toString() == "lib" ->
            home.parent?.resolve("app")
        home.fileName?.toString() == "runtime" ->
            home.parent?.resolve("app")
        else -> null
    } ?: return null
    if (!Files.isDirectory(appDir)) {
        return null
    }
    return Files.list(appDir).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
            .toList()
            .singleOrNull()
            ?.toAbsolutePath()
            ?.normalize()
    }
}

internal fun openHttpsArtifact(uri: URI): InputStream {
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
        throw IOException("Installer URL must use https")
    }
    val client = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    var current = uri
    repeat(MAX_REDIRECTS + 1) {
        val request = HttpRequest.newBuilder(current)
            .timeout(CONNECT_TIMEOUT)
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        when (response.statusCode()) {
            in 200..299 -> return response.body()
            in 300..399 -> {
                response.body().close()
                val location = response.headers().firstValue("location")
                    .orElseThrow { IOException("Redirect is missing location") }
                current = httpsRedirect(current, location)
            }
            else -> {
                response.body().close()
                throw IOException("Installer download failed: ${response.statusCode()}")
            }
        }
    }
    throw IOException("Installer download redirected too many times")
}

internal const val INSTALLER_REPLACE_JAR = "installer-replace.jar"

internal fun defaultReplaceRoot(): Path {
    return Path.of(System.getProperty("user.home", "."), ".robotmc-installer", "replace")
}

/**
 * 설치기에 들어 있는 교체 JAR을 [root]로 복사한다.
 * 이 프로세스는 그 JAR로 뜨고, 실행 중인 설치기 파일은 클래스패스로 쓰지 않는다.
 */
private object ReplaceHelper

internal fun replaceHelperJar(root: Path = defaultReplaceRoot()): Path {
    val destination = root.resolve(INSTALLER_REPLACE_JAR)
    val stream = ReplaceHelper::class.java.classLoader.getResourceAsStream(INSTALLER_REPLACE_JAR)
        ?: throw IllegalStateException("$INSTALLER_REPLACE_JAR is missing")
    Files.createDirectories(destination.parent)
    val partial = destination.resolveSibling("${destination.fileName}.partial")
    stream.use { input ->
        Files.newOutputStream(partial).use { output -> input.transferTo(output) }
    }
    moveReplacing(partial, destination)
    return destination
}

internal fun spawnInstallerReplace(target: Path, staged: Path, waitPid: Long) {
    val javaBin = installerJavaExecutable()
    val helper = replaceHelperJar()
    val command = listOf(
        javaBin,
        "-jar",
        helper.toAbsolutePath().normalize().toString(),
        "--wait-pid",
        waitPid.toString(),
        "--target",
        target.toAbsolutePath().normalize().toString(),
        "--staged",
        staged.toAbsolutePath().normalize().toString(),
        "--java",
        javaBin,
    )
    ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}

private fun copyHashed(input: InputStream, output: java.io.OutputStream, timeout: Duration): String {
    val future = CompletableFuture.supplyAsync {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
        output.flush()
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    try {
        return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join()
    } catch (exception: CompletionException) {
        runCatching { input.close() }
        val cause = exception.cause
        if (cause is TimeoutException) {
            throw IOException("Installer download timed out", cause)
        }
        if (cause is IOException) {
            throw cause
        }
        if (cause is Exception) {
            throw IOException("Installer download failed", cause)
        }
        throw exception
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
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }
}
