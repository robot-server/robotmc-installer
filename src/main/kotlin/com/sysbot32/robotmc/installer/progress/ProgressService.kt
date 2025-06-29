package com.sysbot32.robotmc.installer.progress

import me.tongfei.progressbar.ProgressBar
import org.springframework.stereotype.Service
import javax.swing.JProgressBar

@Service
class ProgressService(
    private val progressBar: ProgressBar,
    private val jProgressBar: JProgressBar,
) {
    fun step(n: Int = 1) {
        this.progressBar.stepBy(n.toLong())
        this.jProgressBar.value += n
    }
}
