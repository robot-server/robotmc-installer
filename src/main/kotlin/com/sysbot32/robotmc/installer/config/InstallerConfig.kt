package com.sysbot32.robotmc.installer.config

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

private val log = KotlinLogging.logger { }

@Configuration
@EnableConfigurationProperties(InstallerProperties::class)
class InstallerConfig(
    private val installerProperties: InstallerProperties,
) {
    init {
        log.info { this.installerProperties }
    }
}
