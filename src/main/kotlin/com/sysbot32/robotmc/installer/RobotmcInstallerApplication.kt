package com.sysbot32.robotmc.installer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class RobotmcInstallerApplication

fun main(args: Array<String>) {
    runApplication<RobotmcInstallerApplication>(*args)
}
