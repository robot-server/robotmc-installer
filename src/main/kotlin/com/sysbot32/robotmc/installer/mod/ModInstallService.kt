package com.sysbot32.robotmc.installer.mod

import com.sysbot32.robotmc.installer.InstallService
import com.sysbot32.robotmc.installer.config.InstalledRecord
import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.config.configFileName
import com.sysbot32.robotmc.installer.config.gameDirectory
import com.sysbot32.robotmc.installer.progress.ProgressService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.nio.file.Files
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
        val gameDir = installerProperties.gameDirectory()
        val modsDir = gameDir.resolve("mods").also { log.info { it } }
        this.progressService.setStatus("모드 폴더 준비 중...")
        Files.createDirectories(modsDir)
        val modsOld = gameDir.resolve("mods_old").also { log.info { it } }
        Files.createDirectories(modsOld)
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
            val fileName = configFileName(mod.downloadUrl)
            this.progressService.setStatus("모드 다운로드 중: $fileName")
            val path = modsDir.resolve(fileName)
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

    override fun uninstall() {
        val count = installerProperties.mod?.mods?.size ?: 0
        if (count > 0) {
            this.progressService.step(-count)
        }
    }
}
