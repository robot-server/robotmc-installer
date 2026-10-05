package com.sysbot32.robotmc.installer.mod.loader

import com.sysbot32.robotmc.installer.InstallService
import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.launcher.LauncherService
import com.sysbot32.robotmc.installer.progress.ProgressService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.exec.DefaultExecutor
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.nio.file.Paths

private val log = KotlinLogging.logger { }

@Service
class ModLoaderInstallService(
    private val launcherService: LauncherService,
    private val restClient: RestClient,
    private val installerProperties: InstallerProperties,
    private val progressService: ProgressService,
) : InstallService {
    override val order: Int
        get() = 10

    override fun install() {
        val loader = this.installerProperties.mod?.loader
        log.info { "Minecraft ${this.installerProperties.minecraft.version}" }
        log.info { "${loader?.type?.displayName} ${loader?.version}" }
        val decision = decideModLoaderInstall(
            type = loader?.type,
            loaderVersion = loader?.version,
            minecraftVersion = this.installerProperties.minecraft.version,
            minecraftDirectory = this.installerProperties.minecraft.directory,
            installOptions = loader?.installOptions.orEmpty(),
            profiles = this.launcherService.getProfiles(),
        )
        val installerPath = Paths.get(decision.installerUrl.substringAfterLast('/'))
        if (!Files.exists(installerPath)) {
            this.progressService.setStatus("모드 로더 설치 프로그램 다운로드 중...")
            this.restClient.get()
                .uri(decision.installerUrl)
                .retrieve()
                .toEntity(ByteArray::class.java)
                .body?.let { Files.write(installerPath, it) }
        }
        this.progressService.step("모드 로더 설치 중...")
        if (decision.runInstaller) {
            val commandLine = modLoaderCommandLine(decision.arguments)
            log.info { "$commandLine" }
            DefaultExecutor.builder().get().execute(commandLine)
        }
        this.progressService.step()
    }

    override fun uninstall() {
        this.progressService.setStatus("모드 로더 제거 중...")
        this.progressService.step(-2)
    }
}
