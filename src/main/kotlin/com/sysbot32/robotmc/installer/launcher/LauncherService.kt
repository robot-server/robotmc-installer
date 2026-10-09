package com.sysbot32.robotmc.installer.launcher

import com.fasterxml.jackson.databind.ObjectMapper
import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.config.gameDirectory
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
     * 설치기가 방금 쓴 프로필도 여기서 이름을 맞춘다.
     */
    fun applyRobotMcProfile(
        profileVersionId: String,
        path: Path = installerProperties.minecraft.directory.resolve("launcher_profiles.json"),
    ) {
        val gameDir = gameDirectory(installerProperties.minecraft.directory)
        val edited = editRobotMcLauncherProfile(Files.readString(path), profileVersionId, gameDir)
        Files.writeString(path, edited)
        log.info { "$path: $profileVersionId -> $ROBOTMC_PROFILE_NAME ($gameDir)" }
    }
}
