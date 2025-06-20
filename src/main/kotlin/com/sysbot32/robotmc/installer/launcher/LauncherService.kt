package com.sysbot32.robotmc.installer.launcher

import com.fasterxml.jackson.databind.ObjectMapper
import com.sysbot32.robotmc.installer.config.InstallerProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

private val log = KotlinLogging.logger { }

@Service
class LauncherService(
    private val installerProperties: InstallerProperties,
    private val objectMapper: ObjectMapper,
) {
    fun getProfiles(path: Path = installerProperties.minecraft.directory.resolve("launcher_profiles.json")): LauncherProfilesJson {
        return this.objectMapper.readValue(
            Files.readString(path).also { log.info { "$path: $it" } },
            LauncherProfilesJson::class.java,
        )
    }
}
