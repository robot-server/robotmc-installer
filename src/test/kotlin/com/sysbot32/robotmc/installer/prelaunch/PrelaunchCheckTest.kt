package com.sysbot32.robotmc.installer.prelaunch

import com.fasterxml.jackson.databind.ObjectMapper
import com.sysbot32.robotmc.installer.config.DEFAULT_PROFILE_ICON
import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.config.gameDirectory
import com.sysbot32.robotmc.installer.launcher.LauncherService
import com.sysbot32.robotmc.installer.mod.loader.ModLoaderType
import com.sysbot32.robotmc.installer.mod.loader.profileVersionId
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrelaunchCheckTest {
    @Test
    fun installWritesOneAbsoluteJavaAgentOnlyOnTheRobotMcProfile() {
        val minecraft = Files.createTempDirectory("robotmc-agent-profile").resolve("My Minecraft")
        Files.createDirectories(minecraft)
        val gameDir = gameDirectory(minecraft)
        val document = profiles(gameDir, "RobotMC", "-Xmx4G -XX:+UseG1GC")
        val profilesPath = minecraft.resolve("launcher_profiles.json")
        Files.writeString(profilesPath, document)
        Files.createDirectories(gameDir.resolve("mods"))
        Files.writeString(gameDir.resolve("mods/user.jar"), "user")
        val home = minecraft.resolve("My Agent")
        val manifestUrl =
            "https://raw.githubusercontent.com/robot-server/robotmc-installer/refs/heads/main/src/main/resources/application.yml"
        val properties = InstallerProperties(
            minecraft = InstallerProperties.Minecraft(version = "1.21.11", directory = minecraft),
            mod = InstallerProperties.Mod(
                loader = InstallerProperties.Mod.Loader(ModLoaderType.NEO_FORGE, "21.11.6-beta"),
            ),
            update = InstallerProperties.Update(manifestUrl = manifestUrl),
        )
        val service = LauncherService(properties, Jackson2ObjectMapperBuilder.json().build())

        service.applyRobotMcProfile(profilesPath, home)
        service.applyRobotMcProfile(profilesPath, home)

        val before = ObjectMapper().readTree(document)
        val after = ObjectMapper().readTree(Files.readString(profilesPath))
        assertEquals(before.get("settings"), after.get("settings"))
        assertEquals(before.get("selectedProfile"), after.get("selectedProfile"))
        assertEquals(before.get("profiles").get("personal-pack"), after.get("profiles").get("personal-pack"))
        assertEquals(before.get("profiles").get("vanilla"), after.get("profiles").get("vanilla"))
        assertEquals("latest-release", after.get("profiles").get("vanilla").get("type").asText())
        assertTrue(after.get("profiles").get("vanilla").get("javaArgs").isNull)
        assertEquals("-Xmx8G", after.get("profiles").get("personal-pack").get("javaArgs").asText())
        val robotmc = after.get("profiles").get("robotmc")
        assertEquals("RobotMC", robotmc.get("name").asText())
        assertEquals("RobotMC", robotmc.get("lastVersionId").asText())
        assertEquals(DEFAULT_PROFILE_ICON, robotmc.get("icon").asText())
        assertEquals("2020-01-01T00:00:00Z", robotmc.get("created").asText())
        assertEquals(true, robotmc.get("unknownProfileField").get("keep").asBoolean())
        assertEquals(gameDir.toString(), robotmc.get("gameDir").asText())
        val tokens = tokenizeJavaArgs(robotmc.get("javaArgs").asText())
        val agents = tokens.filter { isJavaAgentToken(it) }
        assertEquals(1, agents.size)
        assertTrue(tokens.contains("-Xmx4G"))
        assertTrue(tokens.contains("-XX:+UseG1GC"))
        val agent = javaAgentJar(agents.single())
        val options = AgentOptions.read(javaAgentOptionsFile(agents.single()))
        assertTrue(agent.isAbsolute)
        assertTrue(Files.isRegularFile(agent))
        assertTrue(javaAgentOptionsFile(agents.single()).isAbsolute)
        assertFalse(agent.startsWith(gameDir))
        assertFalse(Files.exists(gameDir.resolve("mods").resolve(agent.fileName)))
        assertEquals("user", Files.readString(gameDir.resolve("mods/user.jar")))
        assertNotNull(LauncherService::class.java.classLoader.getResource(AgentOptions.AGENT_JAR_NAME))
        JarFile(agent.toFile()).use { jar ->
            assertEquals(
                "com.sysbot32.robotmc.installer.prelaunch.PrelaunchAgent",
                jar.manifest.mainAttributes.getValue("Premain-Class"),
            )
            assertNull(jar.getJarEntry("META-INF/mods.toml"))
            assertNull(jar.getJarEntry("META-INF/neoforge.mods.toml"))
            assertNull(jar.getJarEntry("fabric.mod.json"))
            assertNull(jar.getJarEntry("org/springframework/boot/SpringApplication.class"))
        }
        assertFalse(String(Files.readAllBytes(agent), Charsets.ISO_8859_1).contains("iris-neoforge-1.10.2"))
        val command = installerLaunchCommand()
        assertEquals(command, options.installerCommand)
        assertTrue(command.isNotEmpty())
        assertEquals(manifestUrl, options.manifestUrl)
        assertEquals(minecraft.toAbsolutePath().normalize(), options.minecraftDirectory)
        assertEquals("robotmc", options.profileKey)
        val javaBin = installerJavaExecutable()
        val appImage = command.size == 1 && Path.of(command.first()).fileName.toString().contains("RobotMC Installer")
        val applicationEntry = command.first() == javaBin && (
            command.contains(INSTALLER_MAIN_CLASS) ||
                (command.contains("-jar") && command.any { it.endsWith(".jar") })
            )
        assertTrue(appImage || applicationEntry, command.toString())
        assertNotEquals(agent.toString(), command.first())
        assertFalse(command.any { path ->
            runCatching { Path.of(path).startsWith(gameDir.resolve("mods")) }.getOrDefault(false)
        })
    }

    @Test
    fun installerCommandUsesTheAppImageLauncherWhenJavaHomeIsInsideTheImage() {
        val root = Files.createTempDirectory("robotmc-image")
        val macJavaHome = root.resolve("RobotMC Installer.app/Contents/runtime/Contents/Home")
        val macLauncher = root.resolve("RobotMC Installer.app/Contents/MacOS/RobotMC Installer")
        Files.createDirectories(macJavaHome)
        Files.createDirectories(macLauncher.parent)
        Files.writeString(macLauncher, "launcher")
        assertEquals(listOf(macLauncher.toString()), appImageLauncher(macJavaHome))
        assertEquals(
            listOf(macLauncher.toString()),
            installerCommand(macJavaHome, root.resolve("app.jar"), "unused", "/java"),
        )

        val linuxJavaHome = root.resolve("RobotMC Installer/lib/runtime")
        val linuxLauncher = root.resolve("RobotMC Installer/bin/RobotMC Installer")
        Files.createDirectories(linuxJavaHome)
        Files.createDirectories(linuxLauncher.parent)
        Files.writeString(linuxLauncher, "launcher")
        assertEquals(listOf(linuxLauncher.toString()), appImageLauncher(linuxJavaHome))

        val windowsJavaHome = root.resolve("RobotMC Installer/runtime")
        val windowsLauncher = root.resolve("RobotMC Installer/RobotMC Installer.exe")
        Files.createDirectories(windowsJavaHome)
        Files.writeString(windowsLauncher, "launcher")
        assertEquals(listOf(windowsLauncher.toString()), appImageLauncher(windowsJavaHome))

        val jar = root.resolve("robotmc-installer-1.0.0.jar")
        Files.write(jar, byteArrayOf(1))
        assertEquals(
            listOf("/opt/java", "-jar", jar.toAbsolutePath().normalize().toString()),
            installerCommand(root.resolve("jdk"), jar, "unused", "/opt/java"),
        )
        assertEquals(
            listOf("/opt/java", "-cp", "classes", INSTALLER_MAIN_CLASS),
            installerCommand(root.resolve("jdk"), root.resolve("classes"), "classes", "/opt/java"),
        )
        assertNull(appImageLauncher(Path.of(System.getProperty("java.home"))))
    }

    @Test
    fun launchCheckMatchesAliasAndDirectLoaderIdsAndJarNames() {
        val minecraft = Files.createTempDirectory("robotmc-check").resolve("My Minecraft")
        Files.createDirectories(minecraft)
        val gameDir = gameDirectory(minecraft)
        val jars = listOf("iris.jar", "sodium.jar")
        val yaml = applicationYaml("neo_forge", "21.11.6-beta", "1.21.11", jars)
        Files.writeString(minecraft.resolve("launcher_profiles.json"), profiles(gameDir, "RobotMC", "-Xmx2G"))
        writeAlias(minecraft, "RobotMC", "neoforge-21.11.6-beta")
        writeJars(gameDir.resolve("mods"), jars)
        Files.writeString(gameDir.resolve("mods/readme.txt"), "note")
        Files.createDirectories(gameDir.resolve("mods/nested"))
        Files.write(gameDir.resolve("mods/nested/extra.jar"), byteArrayOf(9))

        val match = checkLaunchConfig(minecraft, "robotmc", yaml)
        assertTrue(match.match)
        assertEquals(profileVersionId(ModLoaderType.NEO_FORGE, "21.11.6-beta", "1.21.11"), match.remoteLoaderId)
        assertEquals("neoforge-21.11.6-beta", match.launchedLoaderId)
        assertFalse(isDirectLoaderVersionId("RobotMC"))
        assertEquals(match, checkLaunchConfig(minecraft, "robotmc", yaml))

        Files.delete(minecraft.resolve("versions/RobotMC/RobotMC.json"))
        val aliasWithoutParent = checkLaunchConfig(minecraft, "robotmc", yaml)
        assertFalse(aliasWithoutParent.match)
        assertNull(aliasWithoutParent.launchedLoaderId)

        Files.writeString(
            minecraft.resolve("launcher_profiles.json"),
            profiles(gameDir, "neoforge-21.11.6-beta", null),
        )
        val direct = checkLaunchConfig(minecraft, "robotmc", yaml)
        assertTrue(direct.match)
        assertEquals("neoforge-21.11.6-beta", direct.launchedLoaderId)

        Files.writeString(minecraft.resolve("launcher_profiles.json"), profiles(gameDir, "RobotMC", null))
        writeAlias(minecraft, "RobotMC", "neoforge-21.11.5")
        assertFalse(checkLaunchConfig(minecraft, "robotmc", yaml).match)

        writeAlias(minecraft, "RobotMC", "neoforge-21.11.6-beta")
        Files.delete(gameDir.resolve("mods/sodium.jar"))
        assertFalse(checkLaunchConfig(minecraft, "robotmc", yaml).match)

        writeJars(gameDir.resolve("mods"), listOf("sodium.jar", "extra.jar"))
        assertFalse(checkLaunchConfig(minecraft, "robotmc", yaml).match)

        Files.delete(gameDir.resolve("mods/extra.jar"))
        Files.move(gameDir.resolve("mods/sodium.jar"), gameDir.resolve("mods/sodium-renamed.jar"))
        assertFalse(checkLaunchConfig(minecraft, "robotmc", yaml).match)

        val fabricJars = listOf("fabric-api.jar")
        val fabricYaml = applicationYaml("fabric", "0.16.14", "1.21.11", fabricJars)
            .replace("fabric-api.jar'", "fabric-api.jar?token=1'")
        Files.writeString(
            minecraft.resolve("launcher_profiles.json"),
            profiles(gameDir, "fabric-loader-0.16.14-1.21.11", null),
        )
        replaceJars(gameDir.resolve("mods"), fabricJars)
        val fabric = checkLaunchConfig(minecraft, "robotmc", fabricYaml)
        assertTrue(fabric.match)
        assertEquals(setOf("fabric-api.jar"), fabric.remoteJarNames)
        assertEquals(
            profileVersionId(ModLoaderType.FABRIC, "0.16.14", "1.21.11"),
            fabric.remoteLoaderId,
        )
    }

    @Test
    fun editingTheRemoteFixtureFlipsAMatchWithoutChangingMods() {
        val minecraft = Files.createTempDirectory("robotmc-flip").resolve("My Minecraft")
        Files.createDirectories(minecraft)
        val gameDir = gameDirectory(minecraft)
        val jars = listOf("iris.jar", "sodium.jar")
        val yaml = applicationYaml("neo_forge", "21.11.6-beta", "1.21.11", jars)
        Files.writeString(minecraft.resolve("launcher_profiles.json"), profiles(gameDir, "RobotMC", null))
        writeAlias(minecraft, "RobotMC", "neoforge-21.11.6-beta")
        writeJars(gameDir.resolve("mods"), jars)
        val sodium = gameDir.resolve("mods/sodium.jar")
        val before = Files.readAllBytes(sodium)

        assertTrue(checkLaunchConfig(minecraft, "robotmc", yaml).match)
        assertFalse(checkLaunchConfig(minecraft, "robotmc", yaml.replace("21.11.6-beta", "21.11.7")).match)
        assertEquals(before.toList(), Files.readAllBytes(sodium).toList())
        assertFalse(checkLaunchConfig(minecraft, "robotmc", yaml.replace("sodium.jar", "sodium-2.jar")).match)
        assertEquals(before.toList(), Files.readAllBytes(sodium).toList())
        assertTrue(checkLaunchConfig(minecraft, "robotmc", yaml).match)
    }

    @Test
    fun remoteConfigUsesBundledWhenTheManifestUrlIsEmptyAndCacheWhenFetchFails() {
        val root = Files.createTempDirectory("robotmc-config")
        val cache = root.resolve("cache.yml")
        val bundled = root.resolve("bundled.yml")
        val cached = applicationYaml("neo_forge", "9.9.9", "1.21.11", listOf("cached.jar"))
        val bundledText = applicationYaml("neo_forge", "21.11.6-beta", "1.21.11", listOf("bundled.jar"))
        Files.writeString(cache, cached)
        Files.writeString(bundled, bundledText)

        assertEquals(bundledText, loadRemoteConfig("", cache, bundled))
        assertEquals(cached, loadRemoteConfig("http://127.0.0.1/manifest.yml", cache, bundled))
        Files.writeString(cache, "not: [")
        assertEquals(bundledText, loadRemoteConfig("http://127.0.0.1/manifest.yml", cache, bundled))
    }

    @Test
    fun agentExitsNonZeroWhenTheOptionsFileIsMissing() {
        val classes = Files.createTempDirectory("robotmc-sentinel")
        compileStandIns(classes)
        val agent = Path.of(System.getProperty("robotmc.prelaunch.agent"))
        val missing = Files.createTempDirectory("robotmc-missing").resolve("no-options.properties")
        val command = listOf(
            installerJavaExecutable(),
            "-javaagent:${agent.toAbsolutePath()}=${missing.toAbsolutePath()}",
            "-cp",
            classes.toString(),
            "SentinelMain",
        )
        val out = Files.createTempFile("robotmc-out", ".txt")
        val err = Files.createTempFile("robotmc-err", ".txt")
        val process = ProcessBuilder(command)
            .redirectOutput(out.toFile())
            .redirectError(err.toFile())
            .start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(PrelaunchAgent.EXIT_OPTIONS_UNREADABLE, 1)
        assertEquals(1, process.exitValue())
        assertFalse(Files.readString(out).contains("SENTINEL_MAIN_RAN"))
        assertTrue(Files.readString(err).contains("실행 전 검사를 읽지 못해서"))
    }

    @Test
    fun agentExitCodeTellsACrashReportWhyTheInstallerDidNotStart() {
        assertEquals(0, PrelaunchAgent.EXIT_INSTALLER_STARTED)
        assertEquals(2, PrelaunchAgent.EXIT_CHECK_FAILED)
        assertEquals(3, PrelaunchAgent.EXIT_INSTALLER_NOT_STARTED)
        val classes = Files.createTempDirectory("robotmc-sentinel")
        compileStandIns(classes)
        val checkFailed = runAgent(classes, brokenCheck = true)
        val installerMissing = runAgent(classes, brokenCheck = false)
        assertEquals(2, checkFailed.exit)
        assertEquals(3, installerMissing.exit)
        assertFalse(checkFailed.stdout.contains("SENTINEL_MAIN_RAN"))
        assertFalse(installerMissing.stdout.contains("SENTINEL_MAIN_RAN"))
        assertTrue(checkFailed.stderr.contains("실행 전 검사를 끝내지 못해서"))
        assertTrue(installerMissing.stderr.contains("로더 또는 모드가 원격 구성과 달라서"))
        assertTrue(checkFailed.stderr.contains("설치기를 실행하지 못했어요."))
        assertTrue(installerMissing.stderr.contains("설치기를 실행하지 못했어요."))
    }

    @Test
    fun agentReturnsOnMatchAndExitsAfterTheInstallerOnMismatch() {
        val classes = Files.createTempDirectory("robotmc-sentinel")
        compileStandIns(classes)
        val match = launchTwice(match = true, classes = classes)
        val mismatch = launchTwice(match = false, classes = classes)
        assertEquals(0, match.first.exit)
        assertEquals(0, match.second.exit)
        assertTrue(match.first.stdout.contains("SENTINEL_MAIN_RAN"))
        assertTrue(match.second.stdout.contains("SENTINEL_MAIN_RAN"))
        assertFalse(match.first.marker)
        assertFalse(match.second.marker)
        assertFalse(match.first.stdout.contains("SENTINEL_MAIN_RAN") && match.first.stderr.contains("설치기를 실행해요."))
        assertEquals(0, mismatch.first.exit)
        assertEquals(0, mismatch.second.exit)
        assertFalse(mismatch.first.stdout.contains("SENTINEL_MAIN_RAN"))
        assertFalse(mismatch.second.stdout.contains("SENTINEL_MAIN_RAN"))
        assertTrue(mismatch.first.marker)
        assertTrue(mismatch.second.marker)
        assertTrue(mismatch.first.stderr.contains("설치기를 실행해요."))
        assertEquals(match.first.exit, match.second.exit)
        assertEquals(mismatch.first.marker, mismatch.second.marker)
    }

    private fun launchTwice(match: Boolean, classes: Path): Pair<GameRun, GameRun> {
        val minecraft = Files.createTempDirectory("robotmc-play").resolve("My Minecraft")
        Files.createDirectories(minecraft)
        val gameDir = gameDirectory(minecraft)
        val jars = listOf("iris.jar", "sodium.jar")
        val yaml = applicationYaml("neo_forge", "21.11.6-beta", "1.21.11", jars)
        Files.writeString(minecraft.resolve("launcher_profiles.json"), profiles(gameDir, "RobotMC", "-Xmx2G"))
        val marker = minecraft.resolve("installer-ran.txt")
        val command = listOf(
            installerJavaExecutable(),
            "-cp",
            classes.toString(),
            "InstallerMarker",
            marker.toString(),
        )
        val properties = InstallerProperties(
            minecraft = InstallerProperties.Minecraft(version = "1.21.11", directory = minecraft),
            mod = InstallerProperties.Mod(
                loader = InstallerProperties.Mod.Loader(ModLoaderType.NEO_FORGE, "21.11.6-beta"),
            ),
        )
        val service = LauncherService(properties, Jackson2ObjectMapperBuilder.json().build())
        service.applyRobotMcProfile(
            path = minecraft.resolve("launcher_profiles.json"),
            prelaunchHome = minecraft.resolve("My Agent"),
            installerCommand = command,
            manifestUrl = "",
            cacheFile = minecraft.resolve("no-cache.yml"),
            bundledYaml = yaml,
        )
        val inherits = if (match) "neoforge-21.11.6-beta" else "neoforge-0.0.1"
        writeAlias(minecraft, "RobotMC", inherits)
        writeJars(gameDir.resolve("mods"), jars)
        val javaArgs = ObjectMapper().readTree(Files.readString(minecraft.resolve("launcher_profiles.json")))
            .get("profiles").get("robotmc").get("javaArgs").asText()
        val stored = AgentOptions.read(javaAgentOptionsFile(tokenizeJavaArgs(javaArgs).single { isJavaAgentToken(it) }))
        assertEquals(command, stored.installerCommand)
        assertTrue(stored.installerCommand.contains("InstallerMarker"))
        val first = runGame(javaArgs, gameDir, classes, marker)
        recordRun(if (match) "match" else "mismatch", 1, first)
        if (!match) {
            assertEquals("ran", Files.readString(marker))
            Files.delete(marker)
        }
        val second = runGame(javaArgs, gameDir, classes, marker)
        recordRun(if (match) "match" else "mismatch", 2, second)
        return first to second
    }

    private fun runAgent(classes: Path, brokenCheck: Boolean): GameRun {
        val root = Files.createTempDirectory("robotmc-exit")
        val minecraft = root.resolve("minecraft")
        val gameDir = gameDirectory(minecraft)
        Files.createDirectories(minecraft)
        Files.createDirectories(gameDir.resolve("mods"))
        val yaml = applicationYaml("neo_forge", "21.11.6-beta", "1.21.11", listOf("iris.jar"))
        val bundled = root.resolve("bundled.yml")
        Files.writeString(bundled, yaml)
        if (!brokenCheck) {
            Files.writeString(minecraft.resolve("launcher_profiles.json"), profiles(gameDir, "RobotMC", null))
            writeJars(gameDir.resolve("mods"), listOf("other.jar"))
        }
        val options = root.resolve("options.properties")
        AgentOptions(
            minecraft,
            "robotmc",
            "",
            root.resolve("no-cache.yml"),
            bundled,
            listOf(root.resolve("missing-installer").toString()),
        ).write(options)
        val agent = Path.of(System.getProperty("robotmc.prelaunch.agent"))
        val out = Files.createTempFile("robotmc-out", ".txt")
        val err = Files.createTempFile("robotmc-err", ".txt")
        val process = ProcessBuilder(
            listOf(
                installerJavaExecutable(),
                "-javaagent:${agent.toAbsolutePath()}=${options.toAbsolutePath()}",
                "-cp",
                classes.toString(),
                "SentinelMain",
            ),
        )
            .directory(gameDir.toFile())
            .redirectOutput(out.toFile())
            .redirectError(err.toFile())
            .start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        return GameRun(
            command = emptyList(),
            exit = if (finished) process.exitValue() else -1,
            stdout = Files.readString(out),
            stderr = Files.readString(err),
            marker = false,
        )
    }

    private fun runGame(javaArgs: String, cwd: Path, sentinelCp: Path, marker: Path): GameRun {
        val command = mutableListOf(installerJavaExecutable())
        command += tokenizeJavaArgs(javaArgs)
        command += listOf("-cp", sentinelCp.toString(), "SentinelMain")
        assertTrue(command.any { isJavaAgentToken(it) })
        val out = Files.createTempFile("robotmc-out", ".txt")
        val err = Files.createTempFile("robotmc-err", ".txt")
        val process = ProcessBuilder(command)
            .directory(cwd.toFile())
            .redirectOutput(out.toFile())
            .redirectError(err.toFile())
            .start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        val exit = if (finished) process.exitValue() else -1
        return GameRun(
            command = command,
            exit = exit,
            stdout = Files.readString(out),
            stderr = Files.readString(err),
            marker = Files.exists(marker),
        )
    }

    private fun recordRun(fixture: String, run: Int, result: GameRun) {
        val text = buildString {
            appendLine("fixture=$fixture run=$run")
            appendLine("command=${result.command.joinToString(" ")}")
            appendLine("exit=${result.exit}")
            appendLine("stdout:")
            appendLine(result.stdout)
            appendLine("stderr:")
            appendLine(result.stderr)
            appendLine("marker=${result.marker}")
            appendLine()
        }
        print(text)
        val configured = System.getProperty("robotmc.prelaunch.log")
        if (!configured.isNullOrBlank()) {
            val path = Path.of(configured)
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    private fun compileStandIns(dir: Path) {
        val sentinel = dir.resolve("SentinelMain.java")
        val marker = dir.resolve("InstallerMarker.java")
        Files.writeString(
            sentinel,
            """
            public class SentinelMain {
                public static void main(String[] args) {
                    System.out.println("SENTINEL_MAIN_RAN");
                }
            }
            """.trimIndent() + "\n",
        )
        Files.writeString(
            marker,
            """
            import java.nio.file.Files;
            import java.nio.file.Path;
            public class InstallerMarker {
                public static void main(String[] args) throws Exception {
                    Files.writeString(Path.of(args[0]), "ran");
                }
            }
            """.trimIndent() + "\n",
        )
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("javac is required")
        val ok = compiler.run(null, null, null, "-d", dir.toString(), sentinel.toString(), marker.toString())
        check(ok == 0)
    }

    private data class GameRun(
        val command: List<String>,
        val exit: Int,
        val stdout: String,
        val stderr: String,
        val marker: Boolean,
    )
}

private fun applicationYaml(
    loaderType: String,
    loaderVersion: String,
    minecraftVersion: String,
    jars: List<String>,
): String {
    return buildString {
        appendLine("installer:")
        appendLine("  minecraft:")
        appendLine("    version: \"$minecraftVersion\"")
        appendLine("  mod:")
        appendLine("    loader:")
        appendLine("      type: $loaderType")
        appendLine("      version: \"$loaderVersion\"")
        appendLine("    mods:")
        for (jar in jars) {
            appendLine("      - download-url: 'https://cdn.example/mods/$jar'")
        }
    }
}

private fun profiles(gameDir: Path, lastVersionId: String, javaArgs: String?): String {
    val args = if (javaArgs == null) "null" else "\"$javaArgs\""
    val game = gameDir.toString().replace("\\", "\\\\")
    return """
        {
          "settings": {"keepLauncherOpen": false},
          "selectedProfile": "vanilla",
          "version": 3,
          "profiles": {
            "robotmc": {
              "name": "Old",
              "type": "custom",
              "icon": "Grass",
              "created": "2020-01-01T00:00:00Z",
              "lastVersionId": "$lastVersionId",
              "gameDir": "$game",
              "javaArgs": $args,
              "note": "a\\b\"c",
              "unknownProfileField": {"keep": true}
            },
            "personal-pack": {
              "name": "personal-pack",
              "type": "custom",
              "lastVersionId": "neoforge-21.11.6-beta",
              "gameDir": "/games/personal-pack",
              "javaArgs": "-Xmx8G"
            },
            "vanilla": {
              "name": "Latest Release",
              "type": "latest-release",
              "lastVersionId": "latest-release",
              "gameDir": null,
              "javaArgs": null
            }
          }
        }
    """.trimIndent()
}

private fun writeAlias(minecraft: Path, versionId: String, inheritsFrom: String) {
    val path = minecraft.resolve("versions").resolve(versionId).resolve("$versionId.json")
    path.parent?.let { Files.createDirectories(it) }
    Files.writeString(
        path,
        """{"id":"$versionId","inheritsFrom":"$inheritsFrom","type":"release","note":"a\\b\"c"}""",
    )
}

private fun writeJars(mods: Path, names: List<String>) {
    Files.createDirectories(mods)
    for (name in names) {
        Files.write(mods.resolve(name), byteArrayOf(1, 2, 3))
    }
}

private fun replaceJars(mods: Path, names: List<String>) {
    Files.list(mods).use { children ->
        for (child in children) {
            if (Files.isRegularFile(child)) {
                Files.delete(child)
            }
        }
    }
    writeJars(mods, names)
}
