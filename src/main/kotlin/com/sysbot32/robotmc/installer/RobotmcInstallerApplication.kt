package com.sysbot32.robotmc.installer

import androidx.compose.ui.window.application
import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.config.RemoteInstallerConfig
import com.sysbot32.robotmc.installer.gui.InstallerSession
import com.sysbot32.robotmc.installer.gui.InstallerWindow
import com.sysbot32.robotmc.installer.prelaunch.LAUNCHER_RESTART_DETAIL
import com.sysbot32.robotmc.installer.prelaunch.installerLaunchCommand
import com.sysbot32.robotmc.installer.progress.ProgressService
import com.sysbot32.robotmc.installer.progress.plannedSteps
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

private val log = KotlinLogging.logger { }

internal fun startupArguments(
    args: Array<String>,
    resolved: RemoteInstallerConfig.Resolved?,
): Array<String> {
    return args + (resolved?.arguments() ?: emptyArray())
}

@SpringBootApplication
class RobotmcInstallerApplication

fun main(args: Array<String>) {
    if (System.getProperty("robotmc.dump-launch-command") == "true") {
        println(installerLaunchCommand().joinToString("\n"))
        exitProcess(0)
    }
    // Spring Boot는 기본으로 java.awt.headless=true 이다. 그 상태면 Compose가 창을 못 연다.
    System.setProperty("java.awt.headless", "false")
    System.setProperty("apple.awt.application.name", "RobotMC Installer")
    val resolved = runCatching { RemoteInstallerConfig.fromBundled().resolve() }
        .onFailure { log.warn(it) { "Remote config was not applied" } }
        .getOrNull()
    if (resolved == null) {
        log.info { "Using bundled installer config" }
    } else {
        log.info { "Using ${resolved.source} installer config ${resolved.location}" }
    }
    val context = runApplication<RobotmcInstallerApplication>(*startupArguments(args, resolved))
    val properties = context.getBean(InstallerProperties::class.java)
    val progress = context.getBean(ProgressService::class.java)
    val installer = context.getBean(MainInstallService::class.java)
    log.info { "mode: ${properties.mode}" }
    lateinit var session: InstallerSession
    session = InstallerSession(
        mode = properties.mode,
        totalSteps = properties.plannedSteps(),
        progress = progress,
        work = {
            log.info { "========== Start ==========" }
            when (session.state.value.mode) {
                InstallerProperties.Mode.INSTALL -> installer.install()
                InstallerProperties.Mode.UNINSTALL -> installer.uninstall()
            }
            log.info { "========== End ==========" }
        },
        notice = if (properties.launcherRestart) LAUNCHER_RESTART_DETAIL else null,
    )
    var exitCode = 0
    application(exitProcessOnExit = false) {
        val app = this
        InstallerWindow(session, properties) { code ->
            exitCode = code
            app.exitApplication()
        }
    }
    exitProcess(exitCode)
}
