package com.sysbot32.robotmc.installer.progress

import com.sysbot32.robotmc.installer.gui.InProgressDialog
import me.tongfei.progressbar.ProgressBar
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import javax.swing.JProgressBar

@Service
class ProgressService(
    private val progressBar: ProgressBar,
    private val jProgressBar: JProgressBar,
    @Lazy private val inProgressDialog: InProgressDialog,
) {
    fun step(n: Int = 1) {
        this.progressBar.stepBy(n.toLong())
        this.jProgressBar.value += n
    }

    fun step(status: String, n: Int = 1) {
        this.inProgressDialog.setStatus(status)
        this.step(n)
    }

    fun setStatus(status: String) {
        this.inProgressDialog.setStatus(status)
    }
}
