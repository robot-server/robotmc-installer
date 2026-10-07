package com.sysbot32.robotmc.installer.config

import com.sysbot32.robotmc.installer.InstallService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

private val log = KotlinLogging.logger { }

/**
 * 마지막으로 설치에 성공한 모드·리소스 팩 파일 이름.
 * 이후 원격 구성이 바뀌어도 제거는 이 기록과 현재 구성의 합을 지운다.
 */
object InstalledRecord {
    fun file(directory: Path): Path {
        return directory.resolve(".robotmc-installer").resolve("installed.txt")
    }

    fun write(directory: Path, relativePaths: List<String>) {
        val path = this.file(directory)
        Files.createDirectories(path.parent)
        Files.writeString(path, relativePaths.joinToString("\n"))
    }

    fun read(directory: Path): List<String> {
        val path = this.file(directory)
        if (!Files.isRegularFile(path)) {
            return emptyList()
        }
        return Files.readAllLines(path).map { it.trim() }.filter { it.isNotEmpty() }
    }
}

fun configFileName(url: String): String = url.split("/").last()

fun isSafeFileName(name: String): Boolean {
    return name.isNotBlank() &&
        name != "." &&
        name != ".." &&
        '/' !in name &&
        '\\' !in name &&
        '\u0000' !in name
}

fun installedPaths(properties: InstallerProperties): List<String> {
    val mods = properties.mod?.mods.orEmpty().mapNotNull { safeRelative("mods", configFileName(it.downloadUrl)) }
    val packs = properties.resourcePacks.mapNotNull { safeRelative("resourcepacks", configFileName(it.downloadUrl)) }
    return mods + packs
}

fun recordedNames(relativePaths: List<String>, folder: String): List<String> {
    val prefix = "$folder/"
    return relativePaths
        .map { it.trim() }
        .filter { it.startsWith(prefix) }
        .map { it.removePrefix(prefix) }
        .filter(::isSafeFileName)
}

/**
 * 현재 구성의 파일 이름과, 같은 폴더에 남긴 기록의 합.
 * 폴더 밖으로 나가는 기록 이름은 빠진다.
 */
fun uninstallFileNames(configuredFileNames: List<String>, recordedRelative: List<String>, folder: String): List<String> {
    val configured = configuredFileNames.filter(::isSafeFileName)
    val extras = recordedNames(recordedRelative, folder).filter { it !in configured }
    return configured + extras
}

fun deleteInstalledFile(folder: Path, fileName: String) {
    if (!isSafeFileName(fileName)) {
        return
    }
    val directory = folder.normalize()
    val path = directory.resolve(fileName).normalize()
    if (path == directory || !path.startsWith(directory)) {
        return
    }
    Files.deleteIfExists(path)
}

private fun safeRelative(folder: String, name: String): String? {
    if (!isSafeFileName(name)) {
        return null
    }
    return "$folder/$name"
}

@Service
class InstalledRecordService(
    private val installerProperties: InstallerProperties,
) : InstallService {
    /**
     * 모드·리소스 팩 설치가 끝난 뒤에 기록을 남긴다.
     * 그 전에 실패하면 이전 기록이 남는다.
     */
    override val order: Int
        get() = 45

    override fun install() {
        val directory = this.installerProperties.minecraft.directory
        InstalledRecord.write(directory, installedPaths(this.installerProperties))
        log.info { InstalledRecord.file(directory) }
    }

    // 제거는 기록과 현재 구성의 합을 지운다. 기록 파일은 다음 설치가 덮어쓴다.
    override fun uninstall() = Unit
}
