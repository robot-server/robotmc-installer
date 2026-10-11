package com.sysbot32.robotmc.installer.launcher

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.FileAppender
import com.sysbot32.robotmc.installer.exception.UserException
import com.sysbot32.robotmc.installer.prelaunch.installerLaunchCommand
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger { }

private val pendingHomeCacheCancels = mutableListOf<Path>()
private val pendingInProcessCleanups = mutableListOf<AtomicBoolean>()

const val INSTALLER_HOME_DIRECTORY_NAME = ".robotmc-installer"

/**
 * 복사해 둔 설치기, 실행 전 검사 에이전트, 구성 캐시, 로더 설치 JAR, 로그가 있는 디렉터리.
 * `robotmc.prelaunch.home` 이 다른 곳이면 그 디렉터리는 여기 포함하지 않는다.
 */
fun defaultInstallerHome(): Path {
    return Path.of(System.getProperty("user.home"), INSTALLER_HOME_DIRECTORY_NAME)
}

/**
 * 앱 이미지 런처이거나 `java -jar` 의 JAR 이면 그 파일이다.
 * 클래스패스로 띄운 개발 실행은 지울 파일이 없으므로 null 이다.
 */
fun runningInstallerFile(command: List<String> = installerLaunchCommand()): Path? {
    if (command.size == 1) {
        return existingFile(command[0])
    }
    val jarIndex = command.indexOf("-jar")
    if (jarIndex >= 0 && jarIndex + 1 < command.size) {
        return existingFile(command[jarIndex + 1])
    }
    return null
}

/**
 * [candidate] 가 [directory] 자신이거나 그 안의 경로이면 true.
 * 마지막 심볼릭 링크는 따라가지 않아서, 캐시 안 링크의 바깥 대상은 안으로 치지 않는다.
 */
fun pathIsInside(directory: Path, candidate: Path): Boolean {
    val root = normalized(directory)
    val file = normalized(candidate)
    return file == root || file.startsWith(root)
}

/**
 * 프로필, 별칭, 게임 폴더를 지운 뒤에 호출한다.
 * 실행 중인 파일이 [home] 안에 있으면 그 프로세스가 끝나기 전에는 지우지 않는다.
 * 디렉터리가 이미 없어도 성공이다.
 */
fun removeInstallerHomeCache(
    home: Path,
    runningFile: Path? = runningInstallerFile(),
    waitPid: Long? = null,
) {
    val root = home.toAbsolutePath().normalize()
    if (runningFile != null && waitPid != null && pathIsInside(root, runningFile)) {
        deleteHomeCacheAfterExit(root, waitPid)
        return
    }
    deleteInstallerHomeCache(root)
}

/**
 * 심볼릭 링크는 링크만 지운다. DOS 읽기 전용 속성이 보이면 끄고 지운다.
 */
fun deleteInstallerHomeCache(home: Path) {
    val root = home.toAbsolutePath().normalize()
    refuseUserHome(root)
    // Windows는 열린 로그를 FILE_SHARE_DELETE 없이 잡는다. 닫기 전에는 캐시 삭제가 실패한다.
    releaseLogFilesInside(root)
    try {
        deleteTree(root)
    } catch (exception: IOException) {
        throw UserException("삭제하지 못했어요: $root", exception)
    }
}

/**
 * 설치가 같은 프로세스에서 캐시를 다시 만들기 전에 호출한다.
 * 이미 예약된 종료 후 삭제는 그 캐시까지 지우므로 취소한다.
 */
fun cancelPendingInstallerHomeCacheJanitor() {
    val cancels = synchronized(pendingHomeCacheCancels) {
        pendingHomeCacheCancels.toList().also { pendingHomeCacheCancels.clear() }
    }
    for (cancel in cancels) {
        try {
            cancel.parent?.let { Files.createDirectories(it) }
            Files.writeString(cancel, "cancel\n")
        } catch (exception: IOException) {
            log.warn(exception) { "홈 캐시 정리를 취소하지 못했어요: $cancel" }
        }
    }
    val flags = synchronized(pendingInProcessCleanups) {
        pendingInProcessCleanups.toList().also { pendingInProcessCleanups.clear() }
    }
    flags.forEach { it.set(true) }
}

private fun deleteHomeCacheAfterExit(home: Path, pid: Long) {
    if (pid == ProcessHandle.current().pid()) {
        // 이 프로세스가 끝나야 자기 파일을 지울 수 있다. 같은 프로세스 안에서는 기다릴 수 없다.
        log.info { "홈 캐시는 이 프로세스가 끝난 뒤에 지워요: $home" }
        spawnDetachedHomeCacheJanitor(home, pid)
        return
    }
    val handle = ProcessHandle.of(pid).orElse(null)
    if (handle == null || !handle.isAlive) {
        deleteInstallerHomeCache(home)
        return
    }
    log.info { "홈 캐시는 프로세스 $pid 가 끝난 뒤에 지워요: $home" }
    val cancelled = AtomicBoolean(false)
    synchronized(pendingInProcessCleanups) { pendingInProcessCleanups.add(cancelled) }
    Thread({
        try {
            handle.onExit().join()
            if (cancelled.get()) {
                return@Thread
            }
            if (!handle.isAlive) {
                deleteInstallerHomeCache(home)
            }
        } catch (exception: Exception) {
            log.warn(exception) { "홈 캐시를 지우지 못했어요: $home" }
        }
    }, "robotmc-home-cache").apply {
        isDaemon = true
        start()
    }
}

internal fun spawnDetachedHomeCacheJanitor(home: Path, pid: Long, errorLog: Path? = null) {
    val root = home.toAbsolutePath().normalize()
    if (root.fileName?.toString() != INSTALLER_HOME_DIRECTORY_NAME) {
        throw UserException("홈 캐시 경로가 아니에요: $root")
    }
    refuseUserHome(root)
    val windows = System.getProperty("os.name", "").lowercase().startsWith("windows")
    val work = janitorDirectory(root)
    val scriptName = if (windows) "home-cache-janitor.ps1" else "home-cache-janitor.sh"
    val script = work.resolve(scriptName)
    Files.writeString(script, janitorScript(scriptName))
    if (!windows) {
        script.toFile().setExecutable(true)
    }
    val cancel = work.resolve("cancel")
    val command = if (windows) {
        windowsHomeCacheJanitorCommand(powershellExecutable(), script, pid, root, cancel)
    } else {
        listOf(nohupExecutable(), script.toString(), pid.toString(), root.toString(), cancel.toString())
    }
    try {
        val process = ProcessBuilder(command).directory(work.toFile())
        if (errorLog == null) {
            process.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            process.redirectError(ProcessBuilder.Redirect.DISCARD)
        } else {
            errorLog.parent?.let { Files.createDirectories(it) }
            process.redirectErrorStream(true)
            process.redirectOutput(errorLog.toFile())
        }
        process.start()
        synchronized(pendingHomeCacheCancels) { pendingHomeCacheCancels.add(cancel) }
    } catch (exception: IOException) {
        throw UserException("홈 캐시를 나중에 지우지 못해요.", exception)
    }
}

internal fun windowsHomeCacheJanitorCommand(
    powershell: String,
    script: Path,
    pid: Long,
    home: Path,
    cancel: Path,
): List<String> {
    return listOf(
        powershell,
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        script.toString(),
        "-WaitPid",
        pid.toString(),
        "-CacheDirectory",
        home.toString(),
        "-CancelFile",
        cancel.toString(),
    )
}

private fun janitorDirectory(home: Path): Path {
    val created = Files.createTempDirectory("robotmc-home-cache")
    val work = created.toAbsolutePath().normalize()
    if (work == home || pathIsInside(home, work)) {
        deleteTree(created)
        val fallback = home.parent.resolve("robotmc-home-cache-janitor")
        Files.createDirectories(fallback)
        return fallback
    }
    return work
}

private fun janitorScript(name: String): String {
    val stream = InstallerHomeCacheScripts::class.java.classLoader.getResourceAsStream(name)
        ?: error("$name is missing")
    return stream.bufferedReader().use { it.readText() }.replace("\r\n", "\n")
}

private object InstallerHomeCacheScripts

private fun nohupExecutable(): String {
    val absolute = Path.of("/usr/bin/nohup")
    return if (Files.isExecutable(absolute)) absolute.toString() else "nohup"
}

private fun powershellExecutable(): String {
    val systemRoot = System.getenv("SystemRoot")
    if (!systemRoot.isNullOrBlank()) {
        val absolute = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        if (Files.isExecutable(absolute)) {
            return absolute.toString()
        }
    }
    return "powershell.exe"
}

private fun deleteTree(path: Path) {
    // junction은 심볼릭 링크가 아니다. 디렉터리로 열면 바깥 대상의 파일이 지워진다.
    if (isReparsePoint(path)) {
        clearDosReadOnly(path)
        Files.deleteIfExists(path)
        return
    }
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        return
    }
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        val directory = path.toAbsolutePath().normalize()
        val children = Files.newDirectoryStream(path).use { stream -> stream.toList() }
        for (child in children) {
            if (!isReparsePoint(child) && child.toAbsolutePath().normalize().parent != directory) {
                continue
            }
            deleteTree(child)
        }
    }
    clearDosReadOnly(path)
    Files.deleteIfExists(path)
}

/**
 * NOFOLLOW로 읽은 심볼릭 링크이거나, junction 같은 재분석 지점(isOther)이면 true.
 */
private fun isReparsePoint(path: Path): Boolean {
    return try {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        attributes.isSymbolicLink || attributes.isOther
    } catch (_: IOException) {
        false
    }
}

/**
 * JDK 25의 jpackage는 Windows 런처에 읽기 전용 속성을 붙인다.
 * 그 비트가 남아 있으면 DeleteFile이 거부한다.
 * Linux에서도 DosFileAttributeView가 있고, 유닉스 쓰기 비트가 꺼져 있으면
 * 삭제가 AccessDenied가 나므로 일반 파일은 쓰기 권한도 되돌린다.
 * 심볼릭 링크와 junction에는 File.setWritable를 쓰지 않는다. 대상을 따라간다.
 */
private fun clearDosReadOnly(path: Path) {
    val attributes = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        return
    }
    if (attributes.isSymbolicLink) {
        return
    }
    val dos = Files.getFileAttributeView(path, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
    if (dos != null && dos.readAttributes().isReadOnly) {
        if (!attributes.isOther) {
            path.toFile().setWritable(true)
        }
        dos.setReadOnly(false)
    } else if (!attributes.isOther && !path.toFile().canWrite()) {
        path.toFile().setWritable(true)
    }
}

private fun releaseLogFilesInside(home: Path) {
    val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
    for (logger in context.loggerList) {
        for (appender in logger.iteratorForAppenders()) {
            if (appender !is FileAppender<*>) {
                continue
            }
            val file = appender.file ?: continue
            val path = try {
                Path.of(file)
            } catch (_: InvalidPathException) {
                continue
            }
            if (pathIsInside(home, path)) {
                appender.stop()
            }
        }
    }
}

private fun refuseUserHome(root: Path) {
    val userHome = Path.of(System.getProperty("user.home", "")).toAbsolutePath().normalize()
    if (root == userHome || root.parent == null || root.nameCount == 0) {
        throw UserException("홈 폴더 전체는 지우지 않아요.")
    }
}

private fun existingFile(value: String): Path? {
    return try {
        val path = Path.of(value).toAbsolutePath().normalize()
        if (Files.isSymbolicLink(path) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            path
        } else {
            null
        }
    } catch (_: InvalidPathException) {
        null
    }
}

private fun normalized(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    return try {
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            absolute.toRealPath(LinkOption.NOFOLLOW_LINKS)
        } else {
            absolute
        }
    } catch (_: IOException) {
        absolute
    }
}
