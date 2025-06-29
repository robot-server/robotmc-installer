package com.sysbot32.robotmc.installer.gui

import org.springframework.stereotype.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.Toolkit
import javax.swing.JDialog
import javax.swing.JProgressBar

@Component
class InProgressDialog(
    private val progressBar: JProgressBar,
) : JDialog() {

    init {
        this.title = "설치 중"
        this.size = Dimension(400, 100)

        val screenSize = Toolkit.getDefaultToolkit().screenSize
        this.location = Point(screenSize.width / 2 - this.width / 2, screenSize.height / 2 - this.height / 2)

        this.add(this.progressBar)
    }
}
