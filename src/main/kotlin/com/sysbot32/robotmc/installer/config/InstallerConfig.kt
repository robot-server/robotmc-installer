package com.sysbot32.robotmc.installer.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(InstallerProperties::class)
class InstallerConfig {
}
