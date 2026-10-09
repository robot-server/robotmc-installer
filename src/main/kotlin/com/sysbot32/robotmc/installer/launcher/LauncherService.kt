package com.sysbot32.robotmc.installer.launcher

import com.fasterxml.jackson.databind.ObjectMapper
import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.config.gameDirectory
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
        profileVersionId: String,
        path: Path = installerProperties.minecraft.directory.resolve("launcher_profiles.json"),
    ) {
        val gameDir = gameDirectory(installerProperties.minecraft.directory)
        val profileName = installerProperties.profileDisplayName()
        val edited = editRobotMcLauncherProfile(Files.readString(path), profileVersionId, gameDir, profileName)
        Files.writeString(path, edited)
        log.info { "$path: $profileVersionId -> $profileName ($gameDir)" }
    }
}
