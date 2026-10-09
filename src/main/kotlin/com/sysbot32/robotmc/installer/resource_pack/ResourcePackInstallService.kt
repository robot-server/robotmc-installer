package com.sysbot32.robotmc.installer.resource_pack

import com.sysbot32.robotmc.installer.InstallService
import com.sysbot32.robotmc.installer.config.InstalledRecord
import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.config.configFileName
import com.sysbot32.robotmc.installer.config.deleteInstalledFile
import com.sysbot32.robotmc.installer.config.gameDirectory
import com.sysbot32.robotmc.installer.config.recordedNames
import com.sysbot32.robotmc.installer.config.uninstallFileNames
import com.sysbot32.robotmc.installer.progress.ProgressService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.toEntity
import java.nio.file.Files

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
        val gameDir = gameDirectory(installerProperties.minecraft.directory)
        val resourcePackDir = gameDir.resolve("resourcepacks").also { log.info { it } }
        Files.createDirectories(resourcePackDir)
        resourcePackDir.toFile().listFiles()?.forEach { log.info { it } }
        val current = installerProperties.resourcePacks.map { configFileName(it.downloadUrl) }.toSet()
        val previous = recordedNames(InstalledRecord.read(gameDir), "resourcepacks")
        for (fileName in previous) {
            if (fileName in current) {
                continue
            }
            this.progressService.setStatus("리소스 팩 삭제 중: $fileName")
            deleteInstalledFile(resourcePackDir, fileName)
        }
        for (resourcePack in installerProperties.resourcePacks) {
            val fileName = configFileName(resourcePack.downloadUrl)
            this.progressService.setStatus("리소스 팩 다운로드 중: $fileName")
            val path = resourcePackDir.resolve(fileName)
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
        val gameDir = gameDirectory(installerProperties.minecraft.directory)
        val resourcePackDir = gameDir.resolve("resourcepacks").also { log.info { it } }
        val configured = installerProperties.resourcePacks.map { configFileName(it.downloadUrl) }
        val names = uninstallFileNames(
            configured,
            InstalledRecord.read(gameDir),
            "resourcepacks",
        )
        for (fileName in names) {
            this.progressService.setStatus("리소스 팩 삭제 중: $fileName")
            deleteInstalledFile(resourcePackDir, fileName)
            if (fileName in configured) {
                this.progressService.step(-1)
            }
        }
    }
}
