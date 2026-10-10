package com.sysbot32.robotmc.installer.config

import com.sysbot32.robotmc.installer.prelaunch.installerJavaExecutable
import com.sysbot32.robotmc.installer.update.PreparedInstallerUpdate
import com.sysbot32.robotmc.installer.update.applicationRelaunchArguments
import com.sysbot32.robotmc.installer.update.armInstallerUpdate
import com.sysbot32.robotmc.installer.update.installerStagingPath
import com.sysbot32.robotmc.installer.update.replaceHelperJar
import com.sysbot32.robotmc.installer.update.runInstallerUpdate
import com.sysbot32.robotmc.installer.update.runningInstallerJar
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class InstallerSelfUpdateTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun downloadsOnlyTheRunningOsArtifact() {
        assertDecision("Windows 11", "1.0.0", app(), URI(WINDOWS_URL), WINDOWS_BYTES)
        assertDecision("Linux", "1.0.0", app(), URI(LINUX_URL), LINUX_BYTES)
        assertDecision("Mac OS X", "1.0.0", app(), URI(MACOS_URL), MACOS_BYTES)
        assertDecision("darwin", "1.0.0", app(), URI(MACOS_URL), MACOS_BYTES)
        assertDecision(
            "Windows 11",
            "1.0.0",
            app(windowsSha = WINDOWS_SHA.uppercase()),
            URI(WINDOWS_URL),
            WINDOWS_BYTES,
        )
        assertDecision("Windows 11", "1.2.0", app(), null, null)
        assertDecision("Windows 11", null, app(), null, null)
        assertDecision("Windows 11", " ", app(), null, null)
        assertDecision("Windows 11", "1.0.0", app(version = " "), null, null)
        assertDecision("FreeBSD", "1.0.0", app(), null, null)
        assertDecision(
            "Windows 11",
            "1.0.0",
            app(windowsUrl = "http://example.com/robotmc-windows.jar"),
            null,
            null,
        )
        assertDecision("Windows 11", "1.0.0", app(windowsUrl = ""), null, null)
        assertDecision("Windows 11", "1.0.0", app(windowsSha = "abc"), null, null)
        assertDecision("Windows 11", "1.0.0", app(windowsSha = "a".repeat(63)), null, null)
        assertDecision("Linux", "1.0.0", app(linuxSha = "gg".repeat(32)), null, null)
    }

    @Test
    fun acceptsOnlyAMatchingSha256WhileTheOwnerIsAlive() {
        val children = childPids()
        val matched = stage(
            osName = "Mac OS X",
            sha = MACOS_SHA,
            fetch = { MACOS_BYTES.inputStream() },
        )
        val prepared = assertNotNull(matched.prepared)
        assertEquals(listOf(URI(MACOS_URL)), matched.seen)
        assertEquals(listOf(prepared), matched.launched)
        assertTrue(LIVE_BYTES.contentEquals(Files.readAllBytes(matched.target)))
        assertTrue(MACOS_BYTES.contentEquals(Files.readAllBytes(prepared.staged)))
        assertNotEquals(matched.target, prepared.staged)
        assertTrue(Files.notExists(partial(matched.target)))

        val mismatched = stage(
            osName = "Mac OS X",
            sha = WINDOWS_SHA,
            fetch = { MACOS_BYTES.inputStream() },
        )
        assertNull(mismatched.prepared)
        assertEquals(emptyList(), mismatched.launched)
        assertEquals(listOf(URI(MACOS_URL)), mismatched.seen)
        assertTrue(LIVE_BYTES.contentEquals(Files.readAllBytes(mismatched.target)))
        assertTrue(Files.notExists(installerStagingPath(mismatched.target)))
        assertTrue(Files.notExists(partial(mismatched.target)))

        val failed = stage(
            osName = "Linux",
            sha = LINUX_SHA,
            fetch = { _ -> throw IOException("offline") },
        )
        assertNull(failed.prepared)
        assertEquals(emptyList(), failed.launched)
        assertEquals(listOf(URI(LINUX_URL)), failed.seen)
        assertTrue(LIVE_BYTES.contentEquals(Files.readAllBytes(failed.target)))
        assertTrue(Files.notExists(installerStagingPath(failed.target)))
        assertTrue(Files.notExists(partial(failed.target)))
        assertEquals(children, childPids())
    }

    @Test
    fun replacesAfterTheOwnerIsGone() {
        val helper = replaceHelperJar(root.resolve("helper"))
        JarFile(helper.toFile()).use { jar ->
            assertEquals(
                "com.sysbot32.robotmc.installer.replace.InstallerReplace",
                jar.manifest.mainAttributes.getValue(Attributes.Name.MAIN_CLASS),
            )
            assertNotNull(jar.getJarEntry("com/sysbot32/robotmc/installer/replace/InstallerReplace.class"))
        }
        val verified = markerJar()
        val old = byteArrayOf(1, 2, 3, 4)
        assertFalse(old.contentEquals(verified))
        val first = handoff(root.resolve("run-1"), old, verified, helper)
        val second = handoff(root.resolve("run-2"), old, verified, helper)
        assertTrue(verified.contentEquals(first))
        assertTrue(verified.contentEquals(second))
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun relaunchKeepsTheOriginalInstallerArguments() {
        assertEquals(emptyList(), applicationRelaunchArguments(emptyList()))
        val kept = listOf(
            "--installer.mode=uninstall",
            "--installer.launcher-restart=true",
            "--installer.minecraft.directory=/tmp/minecraft custom",
        )
        assertEquals(listOf("--") + kept, applicationRelaunchArguments(kept))
        val helper = replaceHelperJar(root.resolve("helper-args"))
        val verified = markerJar()
        val directory = root.resolve("args")
        Files.createDirectories(directory)
        val target = directory.resolve("installer.jar")
        val staged = installerStagingPath(target)
        Files.write(target, byteArrayOf(1, 2, 3, 4))
        Files.write(staged, verified)
        val replacer = startReplacer(target, staged, null, helper, kept)
        val output = replacer.inputStream.readBytes().decodeToString()
        assertTrue(replacer.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), output)
        assertEquals(0, replacer.exitValue(), output)
        val argv = output.trim().split('\u0000')
        assertEquals("-jar", argv[1], output)
        assertEquals(target.toRealPath(), Path.of(argv[2]).toRealPath())
        assertEquals(kept, argv.drop(3))
        val marked = Path.of(awaitMarker(target, output).lineSequence().first())
        assertEquals(target.toRealPath(), marked.toRealPath())
    }

    @Test
    fun replaceStartupFailureReturnsToTheInstaller() {
        val directory = Files.createTempDirectory(root, "spawn-fail")
        val target = directory.resolve("installer.jar")
        Files.write(target, LIVE_BYTES)
        var spawned = false
        val result = runInstallerUpdate(
            properties = InstallerProperties(
                minecraft = InstallerProperties.Minecraft(version = "1.21.11"),
                mod = null,
                update = InstallerProperties.Update(
                    app = app(
                        version = "9.9.9",
                        windowsSha = WINDOWS_SHA,
                        macosSha = WINDOWS_SHA,
                        linuxSha = WINDOWS_SHA,
                    ),
                ),
            ),
            onProgress = { _, _ -> },
            target = target,
            fetch = { _ -> WINDOWS_BYTES.inputStream() },
            spawn = { _, _, _ ->
                spawned = true
                throw java.nio.file.AccessDeniedException(target.toString())
            },
        )
        assertTrue(spawned)
        assertFalse(result)
        assertTrue(LIVE_BYTES.contentEquals(Files.readAllBytes(target)))
    }

    @Test
    fun aDirectoryLaunchIsNotReplaced() {
        val missingHome = root.resolve("no-image")
        assertNull(runningInstallerJar(codeSource = null, javaHome = missingHome))
        assertNull(runningInstallerJar(codeSource = "file:${root.toAbsolutePath()}/", javaHome = missingHome))
        val jar = root.resolve("robotmc-installer.jar")
        Files.write(jar, byteArrayOf(1, 2, 3))
        assertEquals(
            jar.toRealPath(),
            runningInstallerJar(codeSource = jar.toUri().toString(), javaHome = missingHome)?.toRealPath(),
        )
        val nested = "jar:nested:${jar.toAbsolutePath()}/!BOOT-INF/classes/!/"
        assertEquals(jar.toRealPath(), runningInstallerJar(codeSource = nested, javaHome = missingHome)?.toRealPath())
    }

    @Test
    fun doesNotReplaceWhileTheOwnerPidIsAlive() {
        val classes = root.resolve("hold-classes")
        Files.createDirectories(classes)
        compile(HOLD_SOURCE, "Hold", classes)
        val signal = root.resolve("release")
        val javaBin = installerJavaExecutable()
        val hold = ProcessBuilder(javaBin, "-cp", classes.toString(), "Hold", signal.toString())
            .redirectErrorStream(true)
            .start()
        val target = root.resolve("installer.jar")
        val staged = installerStagingPath(target)
        val old = byteArrayOf(9, 8, 7)
        val verified = markerJar()
        Files.write(target, old)
        Files.write(staged, verified)
        val fixtures = writeFixtures(root)
        val replacer = startReplacer(target, staged, hold.pid(), replaceHelperJar(root.resolve("helper")))
        try {
            val deadline = System.nanoTime() + 2_000_000_000L
            while (!replacer.isAlive && System.nanoTime() < deadline) {
                Thread.sleep(20)
            }
            assertTrue(replacer.isAlive, "replacer exited before the owner")
            val unchangedUntil = System.nanoTime() + 400_000_000L
            while (System.nanoTime() < unchangedUntil) {
                assertTrue(old.contentEquals(Files.readAllBytes(target)))
                assertTrue(replacer.isAlive)
                assertTrue(Files.notExists(target.resolveSibling("started-marker.txt")))
                Thread.sleep(40)
            }
            Files.writeString(signal, "go")
            assertTrue(hold.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            val output = replacer.inputStream.readBytes().decodeToString()
            assertTrue(replacer.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), output)
            assertEquals(0, replacer.exitValue(), output)
            assertTrue(verified.contentEquals(Files.readAllBytes(target)))
            assertLaunched(target, output)
            assertFixtures(fixtures)
        } finally {
            replacer.destroyForcibly()
            hold.destroyForcibly()
        }
    }

    private fun assertDecision(
        osName: String,
        packagedVersion: String?,
        manifest: InstallerProperties.Update.App,
        expectedUrl: URI?,
        expectedBytes: ByteArray?,
    ) {
        val directory = Files.createTempDirectory(root, "os")
        val target = directory.resolve("installer.jar")
        Files.write(target, LIVE_BYTES)
        target.toFile().setWritable(false)
        val modifiedAt = target.toFile().lastModified()
        val seen = mutableListOf<URI>()
        val launched = mutableListOf<PreparedInstallerUpdate>()
        val children = childPids()
        try {
            val prepared = armInstallerUpdate(
                app = manifest,
                packagedVersion = packagedVersion,
                osName = osName,
                target = target,
                fetch = { uri ->
                    seen += uri
                    PAYLOADS[uri]?.inputStream() ?: error("unexpected url $uri")
                },
                launch = { launched += it },
            )
            assertEquals(children, childPids())
            assertTrue(LIVE_BYTES.contentEquals(Files.readAllBytes(target)))
            assertEquals(modifiedAt, target.toFile().lastModified())
            assertTrue(Files.notExists(partial(target)))
            if (expectedUrl == null) {
                assertNull(prepared)
                assertEquals(emptyList(), seen)
                assertEquals(emptyList(), launched)
                assertTrue(Files.notExists(installerStagingPath(target)))
            } else {
                val staged = checkNotNull(prepared)
                assertEquals(listOf(expectedUrl), seen)
                assertEquals(listOf(staged), launched)
                assertEquals(installerStagingPath(target), staged.staged)
                assertTrue(checkNotNull(expectedBytes).contentEquals(Files.readAllBytes(staged.staged)))
                assertNotEquals(target, staged.staged)
            }
        } finally {
            target.toFile().setWritable(true)
        }
    }

    private fun stage(
        osName: String,
        sha: String,
        fetch: (URI) -> java.io.InputStream,
    ): StageResult {
        val directory = Files.createTempDirectory(root, "hash")
        val target = directory.resolve("installer.jar")
        Files.write(target, LIVE_BYTES)
        target.toFile().setWritable(false)
        val modifiedAt = target.toFile().lastModified()
        val seen = mutableListOf<URI>()
        val launched = mutableListOf<PreparedInstallerUpdate>()
        val prepared = armInstallerUpdate(
            app = app(
                windowsSha = sha,
                macosSha = sha,
                linuxSha = sha,
            ),
            packagedVersion = "1.0.0",
            osName = osName,
            target = target,
            fetch = { uri ->
                seen += uri
                fetch(uri)
            },
            launch = { launched += it },
        )
        assertEquals(modifiedAt, target.toFile().lastModified())
        target.toFile().setWritable(true)
        return StageResult(target, seen, launched, prepared)
    }

    private fun handoff(directory: Path, old: ByteArray, verified: ByteArray, helperJar: Path): ByteArray {
        Files.createDirectories(directory)
        val target = directory.resolve("installer.jar")
        val staged = installerStagingPath(target)
        Files.write(target, old)
        Files.write(staged, verified)
        val fixtures = writeFixtures(directory)
        val replacer = startReplacer(target, staged, null, helperJar)
        val output = replacer.inputStream.readBytes().decodeToString()
        assertTrue(replacer.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), output)
        assertEquals(0, replacer.exitValue(), output)
        val replaced = Files.readAllBytes(target)
        assertTrue(verified.contentEquals(replaced), output)
        assertLaunched(target, output)
        assertFixtures(fixtures)
        return replaced
    }

    private fun startReplacer(
        target: Path,
        staged: Path,
        waitPid: Long?,
        helperJar: Path,
        applicationArguments: List<String> = emptyList(),
    ): Process {
        val javaBin = installerJavaExecutable()
        val command = mutableListOf(
            javaBin,
            "-jar",
            helperJar.toString(),
            "--target",
            target.toString(),
            "--staged",
            staged.toString(),
            "--java",
            javaBin,
        )
        if (waitPid != null) {
            command += listOf("--wait-pid", waitPid.toString())
        }
        if (applicationArguments.isNotEmpty()) {
            command += "--"
            command += applicationArguments
        }
        return ProcessBuilder(command).redirectErrorStream(true).start()
    }

    private fun assertLaunched(target: Path, output: String) {
        val argv = output.trim().split('\u0000')
        assertEquals(3, argv.size, output)
        assertEquals("-jar", argv[1], output)
        assertEquals(target.toRealPath(), Path.of(argv[2]).toRealPath())
        val marker = awaitMarker(target, output)
        val marked = Path.of(marker.lineSequence().first())
        assertEquals(target.toRealPath(), marked.toRealPath())
    }

    private fun awaitMarker(target: Path, output: String): String {
        val marker = target.resolveSibling("started-marker.txt")
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(marker) && Files.size(marker) > 0) {
                return Files.readString(marker)
            }
            Thread.sleep(50)
        }
        fail("replaced installer did not start: $output")
    }

    private fun writeFixtures(directory: Path): Map<Path, ByteArray> {
        val files = mapOf(
            directory.resolve("runtime/lib/modules") to "bundled-jre".encodeToByteArray(),
            directory.resolve("game/mods/keep.jar") to "mod-bytes".encodeToByteArray(),
            directory.resolve("game/servers.dat") to "servers".encodeToByteArray(),
            directory.resolve("game/resourcepacks/keep.zip") to "pack".encodeToByteArray(),
        )
        files.forEach { (path, bytes) ->
            Files.createDirectories(path.parent)
            Files.write(path, bytes)
        }
        return files
    }

    private fun assertFixtures(files: Map<Path, ByteArray>) {
        files.forEach { (path, bytes) ->
            assertTrue(Files.isRegularFile(path))
            assertTrue(bytes.contentEquals(Files.readAllBytes(path)), path.toString())
        }
    }

    private fun markerJar(): ByteArray {
        val classes = Files.createTempDirectory(root, "marker")
        compile(MARKER_SOURCE, "Marker", classes)
        val jar = classes.resolve("marker.jar")
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name.MAIN_CLASS] = "Marker"
        JarOutputStream(Files.newOutputStream(jar), manifest).use { output ->
            output.putNextEntry(JarEntry("Marker.class"))
            output.write(Files.readAllBytes(classes.resolve("Marker.class")))
            output.closeEntry()
        }
        return Files.readAllBytes(jar)
    }

    private fun compile(source: String, className: String, classDir: Path) {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("javac is required")
        Files.createDirectories(classDir)
        val sourceFile = classDir.resolve("$className.java")
        Files.writeString(sourceFile, source)
        val errors = java.io.ByteArrayOutputStream()
        val exit = compiler.run(
            null,
            null,
            errors,
            "-d",
            classDir.toString(),
            sourceFile.toString(),
        )
        if (exit != 0) {
            fail(errors.toString())
        }
    }

    private fun childPids(): Set<Long> {
        return ProcessHandle.current().children().use { stream ->
            stream.map { it.pid() }.collect(java.util.stream.Collectors.toSet())
        }
    }

    private fun partial(target: Path): Path = target.resolveSibling("${target.fileName}.partial")

    private fun app(
        version: String = "1.2.0",
        windowsUrl: String = WINDOWS_URL,
        windowsSha: String = WINDOWS_SHA,
        macosUrl: String = MACOS_URL,
        macosSha: String = MACOS_SHA,
        linuxUrl: String = LINUX_URL,
        linuxSha: String = LINUX_SHA,
    ): InstallerProperties.Update.App {
        return InstallerProperties.Update.App(
            version = version,
            windows = InstallerProperties.Update.App.Artifact(windowsUrl, windowsSha),
            macos = InstallerProperties.Update.App.Artifact(macosUrl, macosSha),
            linux = InstallerProperties.Update.App.Artifact(linuxUrl, linuxSha),
        )
    }

    private data class StageResult(
        val target: Path,
        val seen: List<URI>,
        val launched: List<PreparedInstallerUpdate>,
        val prepared: PreparedInstallerUpdate?,
    )
}

private const val WINDOWS_URL = "https://example.com/robotmc-windows.jar"
private const val MACOS_URL = "https://example.com/robotmc-macos.jar"
private const val LINUX_URL = "https://example.com/robotmc-linux.jar"
private const val WINDOWS_SHA = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
private const val MACOS_SHA = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
private const val LINUX_SHA = "fca3e6eec7cb0570cd9b04820c19686869610c46a8a37223c9ebae865c8a2e93"
private val WINDOWS_BYTES = "abc".encodeToByteArray()
private val MACOS_BYTES = "hello".encodeToByteArray()
private val LINUX_BYTES = "linux-artifact".encodeToByteArray()
private val LIVE_BYTES = "live-installer".encodeToByteArray()
private val PAYLOADS = mapOf(
    URI(WINDOWS_URL) to WINDOWS_BYTES,
    URI(MACOS_URL) to MACOS_BYTES,
    URI(LINUX_URL) to LINUX_BYTES,
)

private const val MARKER_SOURCE = """
public class Marker {
    public static void main(String[] args) throws Exception {
        java.nio.file.Path self = java.nio.file.Path.of(
            Marker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        java.nio.file.Path marker = self.resolveSibling("started-marker.txt");
        String command = java.lang.ProcessHandle.current().info().commandLine().orElse("");
        java.nio.file.Files.writeString(
            marker,
            self.toAbsolutePath().normalize() + "\n" + command + "\n");
    }
}
"""

private const val HOLD_SOURCE = """
public class Hold {
    public static void main(String[] args) throws Exception {
        java.nio.file.Path signal = java.nio.file.Path.of(args[0]);
        while (!java.nio.file.Files.exists(signal)) {
            Thread.sleep(20);
        }
    }
}
"""
