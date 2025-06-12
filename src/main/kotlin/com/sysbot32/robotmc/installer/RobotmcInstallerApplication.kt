package com.sysbot32.robotmc.installer

import com.sysbot32.robotmc.installer.mod.ModInstallService
import com.sysbot32.robotmc.installer.mod.loader.ModLoaderInstallService
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

@SpringBootApplication
class RobotmcInstallerApplication

fun main(args: Array<String>) {
    runApplication<RobotmcInstallerApplication>(*args).run {
        getBean(ModLoaderInstallService::class.java).install()
        getBean(ModInstallService::class.java).install()
        exitProcess(0)
    }
}
