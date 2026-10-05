package com.sysbot32.robotmc.installer

import androidx.compose.ui.window.application
import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.gui.InstallerSession
import com.sysbot32.robotmc.installer.gui.InstallerWindow
import com.sysbot32.robotmc.installer.progress.ProgressService
import com.sysbot32.robotmc.installer.progress.plannedSteps
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

private val log = KotlinLogging.logger { }

@SpringBootApplication
class RobotmcInstallerApplication

fun main(args: Array<String>) {
    // Spring Boot는 기본으로 java.awt.headless=true 이다. 그 상태면 Compose가 창을 못 연다.
    System.setProperty("java.awt.headless", "false")
    System.setProperty("apple.awt.application.name", "RobotMC Installer")
    val context = runApplication<RobotmcInstallerApplication>(*args)
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
    )
    log.info { "세션 준비: ${session.state.value.phase}" }
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
