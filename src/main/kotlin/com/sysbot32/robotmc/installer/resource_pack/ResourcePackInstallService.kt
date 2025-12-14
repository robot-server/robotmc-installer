package com.sysbot32.robotmc.installer.resource_pack

import com.sysbot32.robotmc.installer.InstallService
import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.progress.ProgressService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.toEntity
import java.nio.file.Files
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteIfExists

private val log = KotlinLogging.logger { }

@Service
class ResourcePackInstallService(
    private val restClient: RestClient,
    private val installerProperties: InstallerProperties,
    private val progressService: ProgressService,
) : InstallService {
    override val order: Int
        get() = 40

    override fun install() {
        val resourcePackDir = installerProperties.minecraft.directory.resolve("resourcepacks").also { log.info { it } }
        if (!Files.exists(resourcePackDir)) {
            resourcePackDir.createDirectory()
        }
        resourcePackDir.toFile().listFiles()?.forEach { log.info { it } }
        for (resourcePack in installerProperties.resourcePacks) {
            val path = resourcePackDir.resolve(resourcePack.downloadUrl.split("/").last())
            if (!Files.exists(path)) {
                this.restClient.get()
                    .uri(resourcePack.downloadUrl)
                    .retrieve()
                    .toEntity<ByteArray>()
                    .body?.let { Files.write(path, it) }
            }
            this.progressService.step()
        }
    }

    override fun uninstall() {
        val resourcePackDir = installerProperties.minecraft.directory.resolve("resourcepacks").also { log.info { it } }
        for (resourcePack in installerProperties.resourcePacks) {
            val path = resourcePackDir.resolve(resourcePack.downloadUrl.split("/").last())
            path.deleteIfExists()
            this.progressService.step(-1)
        }
    }
}
