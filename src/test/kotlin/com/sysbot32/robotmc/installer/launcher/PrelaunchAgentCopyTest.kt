package com.sysbot32.robotmc.installer.launcher

import com.sysbot32.robotmc.installer.prelaunch.AgentOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class PrelaunchAgentCopyTest {
    @Test
    fun identicalAgentFileIsNotReplaced() {
        val home = Files.createTempDirectory("robotmc-agent-same")
        val source = Path.of(System.getProperty("robotmc.prelaunch.agent"))
        val stable = home.resolve(AgentOptions.AGENT_JAR_NAME)
        Files.copy(source, stable)
        val stamped = FileTime.fromMillis(1_700_000_000_000)
        Files.setLastModifiedTime(stable, stamped)

        val installed = installPrelaunchAgent(home)

        assertEquals(stable, installed)
        assertEquals(stamped, Files.getLastModifiedTime(stable))
        assertTrue(Files.readAllBytes(source).contentEquals(Files.readAllBytes(stable)))
    }

    @Test
    fun changedAgentIsWrittenBesideTheExistingFile() {
        val home = Files.createTempDirectory("robotmc-agent-diff")
        val source = Path.of(System.getProperty("robotmc.prelaunch.agent"))
        val stable = home.resolve(AgentOptions.AGENT_JAR_NAME)
        val previous = byteArrayOf(1, 2, 3)
        Files.write(stable, previous)

        val installed = installPrelaunchAgent(home)

        assertNotEquals(stable, installed)
        assertTrue(previous.contentEquals(Files.readAllBytes(stable)))
        assertTrue(Files.readAllBytes(source).contentEquals(Files.readAllBytes(installed)))
        assertTrue(installed.fileName.toString().startsWith("robotmc-prelaunch-agent-"))
        assertTrue(installed.fileName.toString().endsWith(".jar"))
        assertFalse(installed.fileName.toString() == AgentOptions.AGENT_JAR_NAME)
    }
}
