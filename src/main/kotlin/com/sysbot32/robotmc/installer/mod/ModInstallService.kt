package com.sysbot32.robotmc.installer.mod

import com.sysbot32.robotmc.installer.InstallService
import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.progress.ProgressService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.nio.file.Files
import kotlin.io.path.createDirectory
import kotlin.io.path.name

private val log = KotlinLogging.logger { }

@Service
class ModInstallService(
    private val restClient: RestClient,
    private val installerProperties: InstallerProperties,
    private val progressService: ProgressService,
) : InstallService {
    override val order: Int
        get() = 20

    override fun install() {
        val modsDir = installerProperties.minecraft.directory.resolve("mods").also { log.info { it } }
        if (!Files.exists(modsDir)) {
            modsDir.createDirectory()
        }
        val modsOld = installerProperties.minecraft.directory.resolve("mods_old").also { log.info { it } }
        if (!Files.exists(modsOld)) {
            modsOld.createDirectory()
        }
        modsDir.toFile().listFiles()?.forEach { log.info { it } }
        modsDir.toFile().listFiles()?.forEach {
            val newPath = modsOld.resolve(it.name)
            if (!Files.exists(newPath)) {
                it.renameTo(newPath.toFile())
            } else {
                it.deleteRecursively()
            }
        }

        for (mod in installerProperties.mod?.mods ?: listOf()) {
            val path = modsDir.resolve(mod.downloadUrl.split("/").last())
            val backupFile = modsOld.resolve(path.name)
            if (Files.exists(backupFile)) {
                backupFile.toFile().copyTo(path.toFile())
                log.info { "copied from mods_old $backupFile" }
            } else {
                this.restClient.get()
                    .uri(mod.downloadUrl)
                    .retrieve()
                    .toEntity(ByteArray::class.java)
                    .body?.let { Files.write(path, it) }
            }
            this.progressService.step()
        }
    }
}
