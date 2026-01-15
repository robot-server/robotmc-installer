package com.sysbot32.robotmc.installer.gui

import com.sysbot32.robotmc.installer.config.InstallerProperties
import org.springframework.stereotype.Component
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.Toolkit
import javax.swing.*

@Component
class InProgressDialog(
    private val progressBar: JProgressBar,
    installerProperties: InstallerProperties,
) : JDialog() {
    private val statusLabel = JLabel("준비 중...")

    init {
        this.title = when (installerProperties.mode) {
            InstallerProperties.Mode.INSTALL -> "설치 중"
            InstallerProperties.Mode.UNINSTALL -> "제거 중"
        }
        this.size = Dimension(400, 120)
        this.isResizable = false
        this.defaultCloseOperation = DO_NOTHING_ON_CLOSE

        val screenSize = Toolkit.getDefaultToolkit().screenSize
        this.location = Point(screenSize.width / 2 - this.width / 2, screenSize.height / 2 - this.height / 2)

        this.layout = BorderLayout(10, 10)
        val panel = JPanel(BorderLayout(10, 10)).apply {
            border = BorderFactory.createEmptyBorder(20, 20, 20, 20)
            add(statusLabel, BorderLayout.NORTH)
            add(progressBar, BorderLayout.CENTER)
        }
        this.add(panel)
    }

    fun setStatus(status: String) {
        statusLabel.text = status
    }
}
