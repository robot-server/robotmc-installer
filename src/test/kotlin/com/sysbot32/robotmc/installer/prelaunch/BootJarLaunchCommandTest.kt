package com.sysbot32.robotmc.installer.prelaunch

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootJarLaunchCommandTest {
    @Test
    fun nestedCodeSourceKeepsTheOuterJar() {
        val path = installerJarPath(
            "jar:nested:/Users/example/robotmc-installer-1.0.0.jar/!BOOT-INF/classes/!/",
        )
        assertEquals("/Users/example/robotmc-installer-1.0.0.jar", path)
        assertEquals(
            "/tmp/app.jar",
            installerJarPath("jar:file:/tmp/app.jar!/BOOT-INF/classes!/"),
        )
    }

    @Test
    fun bootJarRelaunchCommandUsesTheOuterJar() {
        val jar = Path.of(System.getProperty("robotmc.boot.jar"))
        assertTrue(jar.toFile().isFile, jar.toString())
        val process = ProcessBuilder(
            installerJavaExecutable(),
            "-Drobotmc.dump-launch-command=true",
            "-jar",
            jar.toString(),
        )
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(finished, output)
        assertEquals(0, process.exitValue(), output)
        val lines = output.lines().filter { it.isNotBlank() }
        assertEquals(installerJavaExecutable(), lines[0], output)
        assertEquals("-jar", lines[1], output)
        assertEquals(jar.toAbsolutePath().normalize().toString(), lines[2], output)
        assertFalse(output.contains("ClassNotFoundException"), output)
    }
}
