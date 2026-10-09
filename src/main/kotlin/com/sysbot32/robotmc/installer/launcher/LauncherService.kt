package com.sysbot32.robotmc.installer.launcher

import com.fasterxml.jackson.databind.ObjectMapper
import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.config.gameDirectory
import com.sysbot32.robotmc.installer.config.launcherProfileKey
import com.sysbot32.robotmc.installer.config.launcherVersionId
import com.sysbot32.robotmc.installer.config.profileDisplayName
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
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
    ) {
        val gameDir = installerProperties.gameDirectory()
        val profileName = installerProperties.profileDisplayName()
        val profileKey = installerProperties.launcherProfileKey()
        val versionId = installerProperties.launcherVersionId()
        val edited = editRobotMcLauncherProfile(
            Files.readString(path),
            gameDir,
            profileName,
            profileKey,
            versionId,
        )
        Files.writeString(path, edited)
        log.info { "$path: $profileKey -> $profileName ($versionId, $gameDir)" }
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
