package com.sysbot32.robotmc.installer.config

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.util.*

private val log = KotlinLogging.logger { }

@Configuration
@EnableConfigurationProperties(InstallerProperties::class)
class InstallerConfig(
    private val installerProperties: InstallerProperties,
) {
    init {
        Thread.currentThread().name = UUID.randomUUID().toString()
        Runtime.getRuntime().addShutdownHook(Thread { log.info { "Shutting down..." } })

        log.info { "Environments: ${System.getenv()}" }
        log.info { "Properties: ${System.getProperties()}" }
        log.info { "Java Runtime Version: ${Runtime.version()}" }

        log.info { this.installerProperties }
    }
}
