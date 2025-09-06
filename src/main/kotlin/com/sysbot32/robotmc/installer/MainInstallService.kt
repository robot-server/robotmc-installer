package com.sysbot32.robotmc.installer

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

private val log = KotlinLogging.logger { }

@Service
class MainInstallService(
    private val installServices: List<InstallService>,
) : InstallService {
    override val order: Int
        get() = 0

    override fun install() {
        this.installServices.sortedBy { it.order }.also { log.info { it } }.forEach { it.install() }
    }

    override fun uninstall() {
        this.installServices.sortedBy { -it.order }.also { log.info { it } }.forEach { it.uninstall() }
    }
}
