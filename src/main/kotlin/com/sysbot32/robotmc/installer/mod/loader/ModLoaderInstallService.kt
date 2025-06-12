package com.sysbot32.robotmc.installer.mod.loader

import com.sysbot32.robotmc.installer.config.InstallerProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.exec.CommandLine
import org.apache.commons.exec.DefaultExecutor
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.nio.file.Paths

private val log = KotlinLogging.logger { }

@Service
class ModLoaderInstallService(
    private val restClient: RestClient,
    private val installerProperties: InstallerProperties,
) {
    fun install() {
        log.info { "Minecraft ${installerProperties.minecraft.version}" }
        log.info { "${installerProperties.mod?.loader?.type?.displayName} ${installerProperties.mod?.loader?.version}" }
        val installerUrl = when (installerProperties.mod?.loader?.type) {
            ModLoaderType.NEO_FORGE -> "https://maven.neoforged.net/releases/net/neoforged/neoforge/${installerProperties.mod.loader.version}/neoforge-${installerProperties.mod.loader.version}-installer.jar"
            ModLoaderType.FABRIC -> "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.0.3/fabric-installer-1.0.3.jar"
            else -> throw IllegalArgumentException("Unsupported mod loader type ${installerProperties.mod?.loader?.type}")
        }
        val installerPath = Paths.get(installerUrl.split("/").last())
        if (!Files.exists(installerPath)) {
            this.restClient.get()
                .uri(installerUrl)
                .retrieve()
                .toEntity(ByteArray::class.java)
                .body?.let { Files.write(installerPath, it) }
        }
        DefaultExecutor.builder().get().run {
            execute(
                CommandLine.parse(
                    "java -jar $installerPath ${installerProperties.mod.loader.installOptions.joinToString(" ")}"
                ).also { log.info { "$it" } })
        }
    }
}
