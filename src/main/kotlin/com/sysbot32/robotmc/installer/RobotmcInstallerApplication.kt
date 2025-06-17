package com.sysbot32.robotmc.installer

import com.sysbot32.robotmc.installer.mod.ModInstallService
import com.sysbot32.robotmc.installer.mod.loader.ModLoaderInstallService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

private val log = KotlinLogging.logger { }

@SpringBootApplication
class RobotmcInstallerApplication

fun main(args: Array<String>) {
    runApplication<RobotmcInstallerApplication>(*args).run {
        log.info { "========== Start ==========" }
        getBean(ModLoaderInstallService::class.java).install()
        getBean(ModInstallService::class.java).install()
        log.info { "========== End ==========" }
        exitProcess(0)
    }
}
