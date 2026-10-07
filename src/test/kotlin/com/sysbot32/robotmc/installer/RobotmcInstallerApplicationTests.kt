package com.sysbot32.robotmc.installer

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.FileAppender
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import java.io.File
import kotlin.test.assertEquals

@SpringBootTest
class RobotmcInstallerApplicationTests {

    @Test
    fun contextLoads() {
    }

    @Test
    fun logFileIsUnderTheUserHome() {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val appender = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("FILE") as FileAppender<*>
        assertEquals(
            File(System.getProperty("user.home"), ".robotmc-installer/logs/application.log").absolutePath,
            File(appender.file).absolutePath,
        )
    }

}
