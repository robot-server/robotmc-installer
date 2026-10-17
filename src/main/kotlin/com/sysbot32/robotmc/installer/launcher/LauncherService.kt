package com.sysbot32.robotmc.installer.launcher

import com.fasterxml.jackson.databind.ObjectMapper
import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.config.RemoteInstallerConfig
import com.sysbot32.robotmc.installer.config.gameDirectory
import com.sysbot32.robotmc.installer.config.launcherProfileKey
import com.sysbot32.robotmc.installer.config.launcherVersionId
import com.sysbot32.robotmc.installer.config.profileDisplayIcon
import com.sysbot32.robotmc.installer.config.profileDisplayName
import com.sysbot32.robotmc.installer.prelaunch.AgentOptions
import com.sysbot32.robotmc.installer.prelaunch.installerLaunchCommand
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.exists

private val log = KotlinLogging.logger { }

@Service
class LauncherService(
    private val installerProperties: InstallerProperties,
    private val objectMapper: ObjectMapper,
) {
    fun getProfiles(path: Path = installerProperties.minecraft.directory.resolve("launcher_profiles.json")): LauncherProfilesJson {
        if (!path.exists()) {
            throw MinecraftLauncherProfileNotFoundException(installerProperties.minecraft.downloadUrl)
        }
        return this.objectMapper.readValue(
            Files.readString(path).also { log.info { "$path: $it" } },
            LauncherProfilesJson::class.java,
        )
    }

    /**
     * 로더 설치를 실행했거나 건너뛴 뒤에 호출한다.
     * NeoForge, Fabric, 사용자가 만든 프로필은 그대로 둔다.
     */
    fun applyRobotMcProfile(
        path: Path = installerProperties.minecraft.directory.resolve("launcher_profiles.json"),
        prelaunchHome: Path = defaultPrelaunchHome(),
        installerCommand: List<String> = installerLaunchCommand(),
        manifestUrl: String = installerProperties.update.manifestUrl,
        cacheFile: Path = RemoteInstallerConfig.defaultCacheFile(),
        bundledYaml: String = bundledApplicationYaml(),
    ) {
        val gameDir = installerProperties.gameDirectory()
        val profileName = installerProperties.profileDisplayName()
        val profileKey = installerProperties.launcherProfileKey()
        val versionId = installerProperties.launcherVersionId()
        val profileIcon = installerProperties.profileDisplayIcon()
        val home = prelaunchHome.toAbsolutePath().normalize()
        val agentJar = installPrelaunchAgent(home)
        val bundledFile = home.resolve("bundled-application.yml")
        Files.writeString(bundledFile, bundledYaml)
        val optionsFile = home.resolve("prelaunch-$profileKey.properties")
        val storedCommand = stableInstallerCommand(installerCommand, home)
        AgentOptions(
            installerProperties.minecraft.directory,
            profileKey,
            manifestUrl,
            cacheFile,
            bundledFile,
            storedCommand,
        ).write(optionsFile)
        val edited = editRobotMcLauncherProfile(
            Files.readString(path),
            gameDir,
            profileName,
            profileKey,
            versionId,
            profileIcon,
            agentJar,
            optionsFile,
        )
        Files.writeString(path, edited)
        log.info { "$path: $profileKey -> $profileName ($versionId, $gameDir) javaagent $agentJar" }
    }

    /**
     * 이 구성의 프로필 키만 뺀다. 파일이 없으면 그냥 끝낸다.
     */
    fun removeConfiguredProfile(
        path: Path = installerProperties.minecraft.directory.resolve("launcher_profiles.json"),
    ) {
        if (!path.exists()) {
            return
        }
        val edited = removeLauncherProfile(Files.readString(path), installerProperties.launcherProfileKey())
        Files.writeString(path, edited)
        log.info { "$path: removed ${installerProperties.launcherProfileKey()}" }
    }
}

/**
 * 테스트는 빌드 디렉터리를 넣고, 설치는 홈 캐시에 에이전트 JAR 을 둔다.
 */
fun defaultPrelaunchHome(): Path {
    val override = System.getProperty("robotmc.prelaunch.home")
    if (!override.isNullOrBlank()) {
        return Path.of(override)
    }
    return Path.of(System.getProperty("user.home"), ".robotmc-installer")
}

fun bundledApplicationYaml(): String {
    val stream = LauncherService::class.java.classLoader.getResourceAsStream("application.yml")
        ?: error("application.yml is missing")
    return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
}

/**
 * 게임 프로필이 가리킬 절대 경로로 에이전트 JAR 을 복사한다.
 * 빌드가 넘긴 경로를 먼저 보고, 없으면 설치기 안의 리소스를 쓴다.
 */
fun installPrelaunchAgent(home: Path): Path {
    val directory = home.toAbsolutePath().normalize()
    Files.createDirectories(directory)
    val bytes = agentJarBytes()
    val stable = directory.resolve(AgentOptions.AGENT_JAR_NAME)
    if (!Files.exists(stable)) {
        writeAgentJar(directory, stable, bytes)
        return stable
    }
    if (sameFileBytes(stable, bytes)) {
        return stable
    }
    // 게임이 이 JAR 을 연 채로 설치가 끝날 때까지 기다린다. Windows 는 그 파일을 지울 수 없다.
    val versioned = directory.resolve(versionedAgentJarName(bytes))
    if (!Files.isRegularFile(versioned) || !sameFileBytes(versioned, bytes)) {
        writeAgentJar(directory, versioned, bytes)
    }
    return versioned
}

private fun agentJarBytes(): ByteArray {
    val property = System.getProperty("robotmc.prelaunch.agent")
    if (!property.isNullOrBlank()) {
        val path = Path.of(property)
        if (Files.isRegularFile(path)) {
            return Files.readAllBytes(path)
        }
    }
    val resource = LauncherService::class.java.classLoader.getResourceAsStream(AgentOptions.AGENT_JAR_NAME)
        ?: error("robotmc-prelaunch-agent.jar is missing")
    return resource.use { it.readBytes() }
}

private fun versionedAgentJarName(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val hex = digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(16)
    return "robotmc-prelaunch-agent-$hex.jar"
}

private fun sameFileBytes(path: Path, bytes: ByteArray): Boolean {
    if (!Files.isRegularFile(path) || Files.size(path) != bytes.size.toLong()) {
        return false
    }
    return Files.readAllBytes(path).contentEquals(bytes)
}

private fun writeAgentJar(directory: Path, destination: Path, bytes: ByteArray) {
    val temporary = Files.createTempFile(directory, ".robotmc-prelaunch-agent-", ".tmp")
    try {
        Files.write(temporary, bytes)
        try {
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (exception: Exception) {
        Files.deleteIfExists(temporary)
        throw exception
    }
}
