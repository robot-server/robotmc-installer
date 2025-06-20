package com.sysbot32.robotmc.installer.mod

import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.gui.InProgressDialog
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.nio.file.Files

private val log = KotlinLogging.logger { }

@Service
class ModInstallService(
    private val restClient: RestClient,
    private val installerProperties: InstallerProperties,
    private val inProgressDialog: InProgressDialog,
) {
    fun install() {
        val modsDir = installerProperties.minecraft.directory.resolve("mods").also { log.info { it } }
        modsDir.toFile().listFiles()?.forEach { log.info { it } }
        for (mod in installerProperties.mod?.mods ?: listOf()) {
            val path = modsDir.resolve(mod.downloadUrl.split("/").last())
            if (!Files.exists(path)) {
                this.restClient.get()
                    .uri(mod.downloadUrl)
                    .retrieve()
                    .toEntity(ByteArray::class.java)
                    .body?.let { Files.write(path, it) }
            }
            inProgressDialog.progressBar.value++
        }
    }
}
