package com.sysbot32.robotmc.installer.config

import me.tongfei.progressbar.ProgressBar
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.swing.JProgressBar

@Configuration
class ProgressConfig(
    private val installerProperties: InstallerProperties,
) {
    private val max: Int
        get() = 2 + (installerProperties.mod?.mods?.size ?: 0)

    @Bean
    fun progressBar(): ProgressBar {
        return ProgressBar("Installing", this.max.toLong())
    }

    @Bean
    fun jProgressBar(): JProgressBar {
        return JProgressBar(0, this.max)
    }
}
