package com.sysbot32.robotmc.installer.prelaunch

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * 에이전트에 넘기는 위치. 로더 버전과 모드 목록은 여기 넣지 않고, 실행할 때 읽는다.
 */
class AgentOptions(
    minecraftDirectory: Path,
    val profileKey: String,
    manifestUrl: String?,
    cacheFile: Path,
    bundledFile: Path,
    installerCommand: List<String>,
) {
    val minecraftDirectory: Path = minecraftDirectory.toAbsolutePath().normalize()
    val manifestUrl: String = manifestUrl.orEmpty()
    val cacheFile: Path = cacheFile.toAbsolutePath().normalize()
    val bundledFile: Path = bundledFile.toAbsolutePath().normalize()
    val installerCommand: List<String> = installerCommand.toList()

    init {
        require(this.installerCommand.isNotEmpty() && this.installerCommand.none { it.isBlank() }) {
            "Installer command is empty"
        }
    }

    fun write(file: Path) {
        val properties = Properties()
        properties.setProperty("minecraft.directory", this.minecraftDirectory.toString())
        properties.setProperty("profile.key", this.profileKey)
        properties.setProperty("manifest.url", this.manifestUrl)
        properties.setProperty("cache.file", this.cacheFile.toString())
        properties.setProperty("bundled.file", this.bundledFile.toString())
        properties.setProperty("installer.command.count", this.installerCommand.size.toString())
        this.installerCommand.forEachIndexed { index, part ->
            properties.setProperty("installer.command.$index", part)
        }
        file.parent?.let { Files.createDirectories(it) }
        Files.newOutputStream(file).use { output ->
            properties.store(output, "RobotMC prelaunch")
        }
    }

    companion object {
        const val AGENT_JAR_NAME = "robotmc-prelaunch-agent.jar"

        fun read(file: Path): AgentOptions {
            val properties = Properties()
            Files.newInputStream(file).use { input ->
                properties.load(input)
            }
            val count = properties.getProperty("installer.command.count", "0").toInt()
            if (count <= 0) {
                throw IllegalArgumentException("Installer command is empty")
            }
            val command = (0 until count).map { index ->
                val part = properties.getProperty("installer.command.$index")
                if (part.isNullOrBlank()) {
                    throw IllegalArgumentException("Installer command is empty")
                }
                part
            }
            return AgentOptions(
                Path.of(required(properties, "minecraft.directory")),
                required(properties, "profile.key"),
                properties.getProperty("manifest.url", ""),
                Path.of(required(properties, "cache.file")),
                Path.of(required(properties, "bundled.file")),
                command,
            )
        }

        private fun required(properties: Properties, key: String): String {
            val value = properties.getProperty(key)
            if (value.isNullOrBlank()) {
                throw IllegalArgumentException("$key is missing")
            }
            return value
        }
    }
}
