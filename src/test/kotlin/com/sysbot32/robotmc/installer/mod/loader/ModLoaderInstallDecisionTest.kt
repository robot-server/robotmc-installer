package com.sysbot32.robotmc.installer.mod.loader

import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.launcher.LauncherProfilesJson
import com.sysbot32.robotmc.installer.launcher.LauncherService
import com.sysbot32.robotmc.installer.progress.ProgressService
import org.apache.commons.exec.ExecuteException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.AbstractClientHttpRequest
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.web.client.RestClient
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ModLoaderInstallDecisionTest {
    private val installerDirectory = Files.createTempDirectory("mod-loader-installer")
    private val fabricJar = installerDirectory.resolve("fabric-installer-1.0.3.jar")
    private val neoForgeJar = installerDirectory.resolve("neoforge-21.11.6-beta-installer.jar")

    @Test
    fun fabricClientInstallUsesTheInstallerJarAndLoaderProfileId() {
        val decision = fabricDecision(profiles())

        assertEquals(
            "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.0.3/fabric-installer-1.0.3.jar",
            decision.installerUrl,
        )
        assertFalse(decision.installerUrl.contains("0.16.14"))
        assertEquals("fabric-loader-0.16.14-1.21.11", decision.profileVersionId)
        assertInstallerJar(decision, fabricJar, fabricCommand)
        assertEquals(fabricCommand, modLoaderCommandLine(decision.arguments).toStrings().toList())
        assertTrue(decision.runInstaller)
    }

    @Test
    fun neoForgeInstallKeepsTheLoaderInstallerUrlAndInstallOptions() {
        val decision = neoForgeDecision(profiles())

        assertEquals(
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.11.6-beta/neoforge-21.11.6-beta-installer.jar",
            decision.installerUrl,
        )
        assertEquals("neoforge-21.11.6-beta", decision.profileVersionId)
        assertInstallerJar(decision, neoForgeJar, neoForgeCommand)
        assertTrue(decision.runInstaller)
    }

    @Test
    fun existingLastVersionIdSkipsAndADifferentProfileKeyDoesNot() {
        val fabricInstalled = fabricDecision(
            profiles("fabric-loader-1.21.11" to "fabric-loader-0.16.14-1.21.11"),
        )
        assertFalse(fabricInstalled.runInstaller)
        assertEquals("fabric-loader-0.16.14-1.21.11", fabricInstalled.profileVersionId)

        val fabricKeyOnly = fabricDecision(
            profiles("fabric-loader-0.16.14-1.21.11" to "fabric-loader-1.21.11"),
        )
        assertTrue(fabricKeyOnly.runInstaller)
        assertEquals(fabricCommand, fabricKeyOnly.arguments)

        val neoForgeInstalled = neoForgeDecision(
            profiles("NeoForge" to "neoforge-21.11.6-beta"),
        )
        assertFalse(neoForgeInstalled.runInstaller)

        val neoForgeMissing = neoForgeDecision(
            profiles("NeoForge" to "forge-21.11.6-beta"),
        )
        assertTrue(neoForgeMissing.runInstaller)
        assertEquals(neoForgeCommand, neoForgeMissing.arguments)
    }

    @Test
    fun loaderArgumentsUseTheMinecraftDirectoryNotTheGameDirectory() {
        val minecraft = Files.createTempDirectory("mod-loader-minecraft")
        val gameDir = minecraft.toAbsolutePath().normalize().resolve("robotmc")
        val neoForge = decideModLoaderInstall(
            type = ModLoaderType.NEO_FORGE,
            loaderVersion = "21.11.6-beta",
            minecraftVersion = "1.21.11",
            minecraftDirectory = minecraft,
            installOptions = listOf("--install-client"),
            profiles = LauncherProfilesJson(
                profiles = mapOf("NeoForge" to renamedProfile("neoforge-21.11.6-beta", gameDir)),
            ),
            installerDirectory = installerDirectory,
        )
        assertFalse(neoForge.runInstaller)
        assertEquals("neoforge-21.11.6-beta", neoForge.profileVersionId)
        assertEquals(minecraft.toString(), neoForge.arguments[neoForge.arguments.indexOf("--install-client") + 1])
        assertFalse(neoForge.arguments.contains(gameDir.toString()))

        val fabric = decideModLoaderInstall(
            type = ModLoaderType.FABRIC,
            loaderVersion = "0.16.14",
            minecraftVersion = "1.21.11",
            minecraftDirectory = minecraft,
            installOptions = emptyList(),
            profiles = LauncherProfilesJson(
                profiles = mapOf(
                    "fabric-loader-1.21.11" to renamedProfile("fabric-loader-0.16.14-1.21.11", gameDir),
                ),
            ),
            installerDirectory = installerDirectory,
        )
        assertFalse(fabric.runInstaller)
        assertEquals("fabric-loader-0.16.14-1.21.11", fabric.profileVersionId)
        assertEquals(minecraft.toString(), fabric.arguments[fabric.arguments.indexOf("-dir") + 1])
        assertFalse(fabric.arguments.contains(gameDir.toString()))
    }

    @Test
    fun neoForgeClientDirectoryIsAlwaysTheConfiguredDirectory() {
        assertEquals(
            listOf(
                "java",
                "-jar",
                neoForgeJar.toString(),
                "--debug",
                "--offline",
                "--install-client",
                "/tmp/My Minecraft/instance",
            ),
            neoForgeDecision(
                profiles(),
                installOptions = listOf("--debug", "--install-client", "--offline"),
            ).arguments,
        )
        assertEquals(neoForgeCommand, neoForgeDecision(profiles(), installOptions = emptyList()).arguments)
        assertEquals(
            neoForgeCommand,
            neoForgeDecision(profiles(), installOptions = listOf("--install-client=/old/minecraft")).arguments,
        )
        assertEquals(
            listOf(
                "java",
                "-jar",
                neoForgeJar.toString(),
                "--offline",
                "--install-client",
                "/tmp/My Minecraft/instance",
            ),
            neoForgeDecision(
                profiles(),
                installOptions = listOf("--installClient", "/already/set", "--offline"),
            ).arguments,
        )
    }

    @Test
    fun loaderTypesAreNeoForgeAndFabricOnly() {
        assertEquals(setOf("NEO_FORGE", "FABRIC"), ModLoaderType.entries.map { it.name }.toSet())
        assertEquals("NeoForge", ModLoaderType.NEO_FORGE.displayName)
        assertEquals("Fabric", ModLoaderType.FABRIC.displayName)
    }

    @Test
    fun missingTypeAndMissingVersionUseDifferentMessages() {
        val missingType = assertFailsWith<IllegalArgumentException> {
            decideModLoaderInstall(
                type = null,
                loaderVersion = "0.16.14",
                minecraftVersion = "1.21.11",
                minecraftDirectory = Paths.get("/tmp/My Minecraft/instance"),
                installOptions = listOf("--install-client"),
                profiles = profiles(),
            )
        }
        assertEquals("Unsupported mod loader type null", missingType.message)

        val missingVersion = assertFailsWith<IllegalArgumentException> {
            decideModLoaderInstall(
                type = ModLoaderType.FABRIC,
                loaderVersion = null,
                minecraftVersion = "1.21.11",
                minecraftDirectory = Paths.get("/tmp/My Minecraft/instance"),
                installOptions = listOf("--install-client"),
                profiles = profiles(),
            )
        }
        assertEquals("Mod loader version is required", missingVersion.message)
    }

    @Test
    fun defaultInstallerDirectoryIsTheHomeCache() {
        val decision = decideModLoaderInstall(
            type = ModLoaderType.FABRIC,
            loaderVersion = "0.16.14",
            minecraftVersion = "1.21.11",
            minecraftDirectory = Paths.get("/tmp/My Minecraft/instance"),
            installOptions = emptyList(),
            profiles = profiles(),
        )
        val expected = Path.of(
            System.getProperty("user.home"),
            ".robotmc-installer",
            "fabric-installer-1.0.3.jar",
        )

        assertEquals(expected, decision.installerJar)
        assertEquals(expected.toString(), decision.arguments[2])
        assertTrue(decision.installerJar.isAbsolute)
        assertNotEquals(Path.of("/fabric-installer-1.0.3.jar"), decision.installerJar)
        assertNotEquals(Path.of(System.getProperty("user.dir")), decision.installerJar.parent)
    }

    private fun assertInstallerJar(decision: ModLoaderInstallDecision, jar: Path, command: List<String>) {
        assertEquals(jar, decision.installerJar)
        assertTrue(decision.installerJar.isAbsolute)
        assertEquals(installerDirectory, decision.installerJar.parent)
        assertEquals(jar.fileName.toString(), decision.installerJar.fileName.toString())
        assertNotEquals(jar.fileName.toString(), decision.arguments[2])
        assertEquals(command, decision.arguments)
        val processed = modLoaderProcessCommand(decision.arguments, "/opt/jdk", "Linux")
        assertEquals("/opt/jdk/bin/java", processed[0])
        assertEquals("-jar", processed[1])
        assertEquals(jar.toString(), processed[2])
        assertEquals(command.drop(3), processed.drop(3))
    }

    private fun fabricDecision(profiles: LauncherProfilesJson): ModLoaderInstallDecision {
        return decideModLoaderInstall(
            type = ModLoaderType.FABRIC,
            loaderVersion = "0.16.14",
            minecraftVersion = "1.21.11",
            minecraftDirectory = Paths.get("/tmp/My Minecraft/instance"),
            installOptions = listOf("--install-client"),
            profiles = profiles,
            installerDirectory = installerDirectory,
        )
    }

    private fun neoForgeDecision(
        profiles: LauncherProfilesJson,
        installOptions: List<String> = listOf("--install-client"),
    ): ModLoaderInstallDecision {
        return decideModLoaderInstall(
            type = ModLoaderType.NEO_FORGE,
            loaderVersion = "21.11.6-beta",
            minecraftVersion = "1.21.11",
            minecraftDirectory = Paths.get("/tmp/My Minecraft/instance"),
            installOptions = installOptions,
            profiles = profiles,
            installerDirectory = installerDirectory,
        )
    }

    private fun renamedProfile(lastVersionId: String, gameDir: Path): LauncherProfilesJson.Profile {
        return LauncherProfilesJson.Profile(
            name = "RobotMC",
            type = "custom",
            created = null,
            lastUsed = null,
            icon = "icon",
            lastVersionId = lastVersionId,
            gameDir = gameDir.toString(),
            javaDir = null,
            javaArgs = "-Xmx4G",
            logConfig = null,
            logConfigIsXml = null,
            resolution = null,
        )
    }

    private fun profiles(vararg entries: Pair<String, String>): LauncherProfilesJson {
        return LauncherProfilesJson(
            profiles = entries.associate { (key, lastVersionId) ->
                key to LauncherProfilesJson.Profile(
                    name = key,
                    type = "custom",
                    created = null,
                    lastUsed = null,
                    icon = "",
                    lastVersionId = lastVersionId,
                    gameDir = null,
                    javaDir = null,
                    javaArgs = null,
                    logConfig = null,
                    logConfigIsXml = null,
                    resolution = null,
                )
            },
        )
    }

    private val fabricCommand = listOf(
        "java",
        "-jar",
        fabricJar.toString(),
        "client",
        "-dir",
        "/tmp/My Minecraft/instance",
        "-mcversion",
        "1.21.11",
        "-loader",
        "0.16.14",
    )

    private val neoForgeCommand = listOf(
        "java",
        "-jar",
        neoForgeJar.toString(),
        "--install-client",
        "/tmp/My Minecraft/instance",
    )
}

class ModLoaderProcessSleep {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Files.writeString(Paths.get(args[0]), "started")
            Thread.sleep(args[1].toLong())
        }
    }
}

class ModLoaderCommandTest {
    @Test
    fun javaExecutableComesFromJavaHome() {
        assertEquals(
            "/opt/jdk/bin/java",
            modLoaderJavaExecutable("/opt/jdk", "Mac OS X"),
        )
        assertEquals(
            "/opt/jdk/bin/java",
            modLoaderJavaExecutable("/opt/jdk/", "Linux"),
        )
        assertEquals(
            "C:\\Program Files\\Java\\jdk-21\\bin\\java.exe",
            modLoaderJavaExecutable("C:\\Program Files\\Java\\jdk-21", "Windows 11"),
        )
        assertEquals(
            "/opt/jdk/bin/java",
            modLoaderJavaExecutable("/opt/jdk", "darwin"),
        )
        assertEquals(
            listOf("/opt/jdk/bin/java", "-jar", "installer.jar"),
            modLoaderProcessCommand(listOf("java", "-jar", "installer.jar"), "/opt/jdk", "Linux"),
        )
        assertEquals(
            listOf("/opt/jdk/bin/java", "30"),
            modLoaderProcessCommand(listOf("/bin/sleep", "30"), "/opt/jdk", "darwin"),
        )
        val running = modLoaderJavaExecutable(System.getProperty("java.home"), System.getProperty("os.name"))
        assertTrue(Files.isExecutable(Paths.get(running)), running)
    }

    @Test
    fun watchdogKillsAProcessThatDoesNotExit() {
        val marker = Files.createTempFile("mod-loader-sleep", ".txt")
        Files.delete(marker)
        val java = modLoaderJavaExecutable(System.getProperty("java.home"), System.getProperty("os.name"))
        val started = System.nanoTime()
        try {
            assertFailsWith<ExecuteException> {
                executeModLoaderCommand(
                    listOf(
                        java,
                        "-cp",
                        System.getProperty("java.class.path"),
                        "com.sysbot32.robotmc.installer.mod.loader.ModLoaderProcessSleep",
                        marker.toString(),
                        "30000",
                    ),
                    Duration.ofSeconds(2),
                )
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(Files.exists(marker), "sleeper did not start")
            assertTrue(elapsedMs < 8_000, "elapsed ${elapsedMs}ms")
        } finally {
            Files.deleteIfExists(marker)
        }
    }
}

class ModLoaderInstallServiceTest {
    @Test
    fun installedProfileWritesTheAbsoluteJarAndDoesNotRunTheInstaller() {
        installAlreadyPresent(
            type = ModLoaderType.FABRIC,
            loaderVersion = "0.16.14",
            fileName = "fabric-installer-1.0.3.jar",
            profileKey = "fabric-loader-1.21.11",
            profileVersionId = "fabric-loader-0.16.14-1.21.11",
            installerUrl = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.0.3/fabric-installer-1.0.3.jar",
        )
        installAlreadyPresent(
            type = ModLoaderType.NEO_FORGE,
            loaderVersion = "21.11.6-beta",
            fileName = "neoforge-21.11.6-beta-installer.jar",
            profileKey = "NeoForge",
            profileVersionId = "neoforge-21.11.6-beta",
            installerUrl = "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.11.6-beta/neoforge-21.11.6-beta-installer.jar",
        )
    }

    private fun installAlreadyPresent(
        type: ModLoaderType,
        loaderVersion: String,
        fileName: String,
        profileKey: String,
        profileVersionId: String,
        installerUrl: String,
    ) {
        val minecraft = Files.createTempDirectory("mod-loader-minecraft")
        val jarDirectory = Files.createTempDirectory("mod-loader-jars")
        val written = jarDirectory.resolve(fileName)
        val cwdJar = Path.of(System.getProperty("user.dir"), fileName)
        val rootJar = Path.of("/", fileName)
        val cwdBefore = readIfExists(cwdJar)
        val userDir = System.getProperty("user.dir")
        val firstBody = byteArrayOf(1, 2, 3, 4)
        val secondBody = byteArrayOf(9, 9, 9, 9)
        val client = SequencedBytesClient(listOf(firstBody, secondBody))
        val profiles = LauncherProfilesJson(
            profiles = mapOf(profileKey to profile(profileKey, profileVersionId)),
        )
        writeProfiles(minecraft, profileKey, profileVersionId)
        val properties = InstallerProperties(
            minecraft = InstallerProperties.Minecraft(version = "1.21.11", directory = minecraft),
            mod = InstallerProperties.Mod(
                loader = InstallerProperties.Mod.Loader(
                    type = type,
                    version = loaderVersion,
                    installOptions = listOf("--install-client"),
                ),
            ),
        )
        val service = ModLoaderInstallService(
            LauncherService(properties, Jackson2ObjectMapperBuilder.json().build()),
            RestClient.builder().requestFactory(client).build(),
            properties,
            ProgressService(),
        )
        service.installerDirectory = jarDirectory

        service.install()
        service.install()

        assertEquals(userDir, System.getProperty("user.dir"))
        assertEquals(1, client.executions)
        assertEquals(listOf(URI.create(installerUrl)), client.uris)
        assertTrue(Files.isRegularFile(written))
        assertTrue(written.isAbsolute)
        assertEquals(jarDirectory, written.parent)
        assertNotEquals(fileName, written.toString())
        assertNotEquals(rootJar, written)
        assertTrue(firstBody.contentEquals(Files.readAllBytes(written)))
        assertTrue(sameBytes(cwdBefore, readIfExists(cwdJar)))
        assertFalse(Files.exists(rootJar))
        val notInstalled = decideModLoaderInstall(
            type = type,
            loaderVersion = loaderVersion,
            minecraftVersion = "1.21.11",
            minecraftDirectory = minecraft,
            installOptions = listOf("--install-client"),
            profiles = LauncherProfilesJson(profiles = emptyMap()),
            installerDirectory = jarDirectory,
        )
        assertTrue(notInstalled.runInstaller)
        assertEquals(written, notInstalled.installerJar)
        assertEquals(written.toString(), notInstalled.arguments[2])
        assertEquals(installerUrl, notInstalled.installerUrl)
        assertEquals(profileVersionId, notInstalled.profileVersionId)
        val installed = decideModLoaderInstall(
            type = type,
            loaderVersion = loaderVersion,
            minecraftVersion = "1.21.11",
            minecraftDirectory = minecraft,
            installOptions = listOf("--install-client"),
            profiles = profiles,
            installerDirectory = jarDirectory,
        )
        assertFalse(installed.runInstaller)
        assertEquals(written.toString(), installed.arguments[2])
    }

    private fun writeProfiles(minecraft: Path, key: String, lastVersionId: String) {
        val json = """
            {"profiles":{"$key":{
              "name":"$key",
              "type":"custom",
              "created":null,
              "lastUsed":null,
              "icon":"",
              "lastVersionId":"$lastVersionId",
              "gameDir":null,
              "javaDir":null,
              "javaArgs":null,
              "logConfig":null,
              "logConfigIsXml":null,
              "resolution":null
            }}}
        """.trimIndent()
        Files.writeString(minecraft.resolve("launcher_profiles.json"), json)
    }

    private fun profile(key: String, lastVersionId: String): LauncherProfilesJson.Profile {
        return LauncherProfilesJson.Profile(
            name = key,
            type = "custom",
            created = null,
            lastUsed = null,
            icon = "",
            lastVersionId = lastVersionId,
            gameDir = null,
            javaDir = null,
            javaArgs = null,
            logConfig = null,
            logConfigIsXml = null,
            resolution = null,
        )
    }

    private fun readIfExists(path: Path): ByteArray? {
        return if (Files.exists(path)) Files.readAllBytes(path) else null
    }

    private fun sameBytes(left: ByteArray?, right: ByteArray?): Boolean {
        return when {
            left == null && right == null -> true
            left == null || right == null -> false
            else -> left.contentEquals(right)
        }
    }
}

/**
 * 고정 바이트를 돌려주는 RestClient용 요청 팩토리. 호출 횟수를 센다.
 */
private class SequencedBytesClient(
    private val bodies: List<ByteArray>,
) : ClientHttpRequestFactory {
    var executions: Int = 0
    val uris: MutableList<URI> = mutableListOf()

    override fun createRequest(uri: URI, httpMethod: HttpMethod): ClientHttpRequest {
        return object : AbstractClientHttpRequest() {
            override fun getMethod(): HttpMethod = httpMethod

            override fun getURI(): URI = uri

            override fun getBodyInternal(headers: HttpHeaders): OutputStream = OutputStream.nullOutputStream()

            override fun executeInternal(headers: HttpHeaders): ClientHttpResponse {
                val body = bodies[executions]
                executions++
                uris += uri
                return object : ClientHttpResponse {
                    override fun getStatusCode() = HttpStatus.OK

                    override fun getStatusText() = "OK"

                    override fun close() = Unit

                    override fun getHeaders(): HttpHeaders {
                        val responseHeaders = HttpHeaders()
                        responseHeaders.contentType = MediaType.APPLICATION_OCTET_STREAM
                        responseHeaders.contentLength = body.size.toLong()
                        return responseHeaders
                    }

                    override fun getBody() = ByteArrayInputStream(body)
                }
            }
        }
    }
}
