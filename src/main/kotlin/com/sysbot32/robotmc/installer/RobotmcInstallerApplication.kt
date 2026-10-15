package com.sysbot32.robotmc.installer

import androidx.compose.ui.window.application
import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.config.RemoteInstallerConfig
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.sysbot32.robotmc.installer.config.InstallerProperties.Mode
import com.sysbot32.robotmc.installer.gui.InstallerSession
import com.sysbot32.robotmc.installer.gui.InstallerUpdateProgress
import com.sysbot32.robotmc.installer.gui.InstallerWindow
import com.sysbot32.robotmc.installer.gui.SessionPhase
import com.sysbot32.robotmc.installer.gui.installInstallerTaskbarIcon
import com.sysbot32.robotmc.installer.prelaunch.LAUNCHER_RESTART_DETAIL
import com.sysbot32.robotmc.installer.update.InstallerRelaunch
import com.sysbot32.robotmc.installer.update.installerUpdateDue
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

/**
 * 검사가 끝난 로더·모드 불일치이고 설치 모드일 때만 확인 없이 설치를 시작한다.
 * 업데이트가 화면에 있거나, 신호가 없거나, 제거·런처 재시작 안내이면 시작하지 않는다.
 */
internal fun startFinishedMismatchInstall(
    session: InstallerSession,
    finishedMismatch: Boolean,
    updateActive: Boolean,
): Boolean {
    if (updateActive || !finishedMismatch) {
        return false
    }
    val state = session.state.value
    if (state.mode != Mode.INSTALL || state.notice || state.phase != SessionPhase.Confirm) {
        return false
    }
    return session.launchAcceptedWork()
}

/**
 * 설치기 교체가 적용됐거나 업데이트 화면이 아직 떠 있으면 이 프로세스에서는 설치하지 않는다.
 * 교체가 적용되지 않았고 업데이트 화면이 없으면, 불일치 신호일 때 확인 없이 설치를 시작한다.
 */
internal fun continueAfterInstallerUpdate(
    updateApplied: Boolean,
    updateActive: Boolean,
    session: InstallerSession,
    finishedMismatch: Boolean,
): Boolean {
    if (updateApplied || updateActive) {
        return false
    }
    return startFinishedMismatchInstall(session, finishedMismatch, updateActive = false)
}

@SpringBootApplication
class RobotmcInstallerApplication

fun main(args: Array<String>) {
    if (System.getProperty("robotmc.dump-launch-command") == "true") {
        println(installerLaunchCommand().joinToString("\n"))
        exitProcess(0)
    }
    // Spring Boot는 기본으로 java.awt.headless=true 이다. 그 상태면 Compose가 창을 못 연다.
    InstallerRelaunch.arguments = args.toList()
    System.setProperty("java.awt.headless", "false")
    System.setProperty("apple.awt.application.name", "RobotMC Installer")
    runCatching { installInstallerTaskbarIcon() }
        .onFailure { log.warn(it) { "Taskbar icon was not set" } }
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
    val updateDue = runCatching { installerUpdateDue(properties) }
        .onFailure { log.warn(it) { "Installer update was not checked" } }
        .getOrDefault(false)
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
        var updating by remember { mutableStateOf(updateDue) }
        if (updating) {
            InstallerUpdateProgress(properties) { success ->
                continueAfterInstallerUpdate(
                    updateApplied = success,
                    updateActive = true,
                    session = session,
                    finishedMismatch = properties.finishedMismatch,
                )
                if (success) {
                    exitCode = 0
                    app.exitApplication()
                } else {
                    updating = false
                }
            }
        } else {
            remember {
                continueAfterInstallerUpdate(
                    updateApplied = false,
                    updateActive = false,
                    session = session,
                    finishedMismatch = properties.finishedMismatch,
                )
                true
            }
            InstallerWindow(session, properties) { code ->
                exitCode = code
                app.exitApplication()
            }
        }
    }
    exitProcess(exitCode)
}
