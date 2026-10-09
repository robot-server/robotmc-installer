package com.sysbot32.robotmc.installer

import org.junit.jupiter.api.Test
import org.springframework.boot.SpringBootVersion
import java.io.File
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallerReleasePinsTest {

    @Test
    fun springBootStaysOnThreeFiveSixteen() {
        assertEquals("3.5.16", SpringBootVersion.getVersion())
    }

    @Test
    fun prelaunchAgentBytecodeStaysRunnableOnJava21() {
        val jar = File(
            System.getProperty("robotmc.prelaunch.agent")
                ?: error("robotmc.prelaunch.agent 가 없습니다."),
        )
        JarFile(jar).use { archive ->
            val entry = archive.getJarEntry("com/sysbot32/robotmc/installer/prelaunch/PrelaunchAgent.class")
                ?: error("PrelaunchAgent.class 가 없습니다: ${jar.absolutePath}")
            val bytes = archive.getInputStream(entry).readNBytes(8)
            check(bytes.size == 8 && bytes[0] == 0xCA.toByte() && bytes[1] == 0xFE.toByte()) {
                "클래스 파일 헤더가 아닙니다."
            }
            val major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
            assertTrue(major <= 65, "PrelaunchAgent 클래스 메이저 $major 는 Java 21(65)보다 높습니다.")
        }
    }
}
