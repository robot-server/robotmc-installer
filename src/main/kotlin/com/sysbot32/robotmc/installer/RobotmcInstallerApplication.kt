package com.sysbot32.robotmc.installer

import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.exception.UserException
import com.sysbot32.robotmc.installer.gui.InProgressDialog
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import java.awt.GraphicsEnvironment
import javax.swing.JOptionPane
import kotlin.system.exitProcess

private val log = KotlinLogging.logger { }

@SpringBootApplication
class RobotmcInstallerApplication

fun main(args: Array<String>) {
    if (GraphicsEnvironment.isHeadless() && "nogui" !in args) {
        System.setProperty("java.awt.headless", false.toString())
    }
    runApplication<RobotmcInstallerApplication>(*args).run {
        try {
            if (JOptionPane.showConfirmDialog(
                    null,
                    "서버 접속에 필요한 모드 로더 및 모드를 설치할까요?\n기존 설치 모드는 mods_old로 옮겨집니다.",
                    "설치",
                    JOptionPane.YES_NO_OPTION
                ) != JOptionPane.YES_OPTION
            ) {
                exitProcess(0)
            }
            log.info { "========== Start ==========" }
            val inProgressDialog = getBean(InProgressDialog::class.java)
            inProgressDialog.isVisible = true
            val mode = getBean(InstallerProperties::class.java).mode
            log.info { "mode: $mode" }
            when (mode) {
                InstallerProperties.Mode.INSTALL -> getBean(MainInstallService::class.java).install()
                InstallerProperties.Mode.UNINSTALL -> getBean(MainInstallService::class.java).uninstall()
            }
            inProgressDialog.isVisible = false
            log.info { "========== End ==========" }
            JOptionPane.showMessageDialog(null, "완료됐어요.")
            exitProcess(0)
        } catch (e: UserException) {
            log.error(e) { e.message }
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(
                    null,
                    e.message,
                    "오류",
                    JOptionPane.ERROR_MESSAGE
                )
            }
            e.afterThrow()
            exitProcess(e.exitStatus)
        } catch (e: Exception) {
            log.error(e) { e.message }
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(
                    null,
                    "오류가 발생했어요.\n로그 파일 첨부하여 제보 부탁드려요.",
                    e.message,
                    JOptionPane.ERROR_MESSAGE
                )
            }
            exitProcess(-1)
        }
    }
}
