package com.sysbot32.robotmc.installer.launcher

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.config.gameDirectory
import com.sysbot32.robotmc.installer.mod.loader.ModLoaderInstallService
import com.sysbot32.robotmc.installer.mod.loader.ModLoaderType
import com.sysbot32.robotmc.installer.mod.loader.decideModLoaderInstall
import com.sysbot32.robotmc.installer.progress.ProgressService
import org.springframework.http.HttpMethod
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.web.client.RestClient
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RobotMcProfileTest {
    @Test
    fun gameDirectoryIsAStableAbsoluteChildOfTheMinecraftDirectory() {
        val minecraft = Files.createTempDirectory("robotmc-profile").resolve("My Minecraft")
        val gameDir = gameDirectory(minecraft)

        assertEquals(expectedGameDir(minecraft), gameDir)
        assertEquals(gameDir, gameDirectory(minecraft))
        assertTrue(gameDir.isAbsolute)
        assertNotEquals(minecraft.toAbsolutePath().normalize(), gameDir)

        val relative = Path.of("configured-minecraft")
        val resolved = gameDirectory(relative)
        assertTrue(resolved.isAbsolute)
        assertEquals(relative.toAbsolutePath().normalize().resolve("robotmc"), resolved)
    }

    @Test
    fun neoForgeProfileStaysWhileRobotMcProfileIsAdded() {
        assertDirectEdit(
            profileKey = "NeoForge",
            visibleName = "NeoForge",
            lastVersionId = "neoforge-21.11.6-beta",
        )
    }

    @Test
    fun fabricProfileStaysWhileRobotMcProfileIsAdded() {
        assertDirectEdit(
            profileKey = "fabric-loader-1.21.11",
            visibleName = "fabric-loader-0.16.14-1.21.11",
            lastVersionId = "fabric-loader-0.16.14-1.21.11",
        )
    }

    @Test
    fun existingRobotMcProfileKeepsFieldsThatAreNotNameVersionOrGameDir() {
        val minecraft = Path.of("/tmp/My Minecraft/instance")
        val document = """
            {"profiles":{
              "NeoForge":{"name":"NeoForge","type":"custom","icon":"","lastVersionId":"neoforge-21.11.6-beta","gameDir":null,"javaArgs":"-Xmx4G"},
              "personal-pack":{"name":"personal-pack","type":"custom","icon":"Bookshelf","lastVersionId":"neoforge-21.11.6-beta","gameDir":"/games/personal-pack","javaArgs":"-Xmx8G"},
              "$ROBOTMC_PROFILE_KEY":{"name":"Old","type":"custom","icon":"Grass","created":"2020-01-01T00:00:00Z","lastVersionId":"neoforge-old","gameDir":"/old","javaArgs":"-Xmx2G","unknownProfileField":{"keep":true}}
            }}
        """.trimIndent()

        val edited = editRobotMcLauncherProfile(document, gameDirectory(minecraft), "RobotMC")

        val mapper = ObjectMapper()
        val before = mapper.readTree(document).get("profiles")
        val after = mapper.readTree(edited).get("profiles")
        assertEquals(before.get("NeoForge"), after.get("NeoForge"))
        assertEquals(before.get("personal-pack"), after.get("personal-pack"))
        val ours = after.get(ROBOTMC_PROFILE_KEY)
        assertEquals("RobotMC", ours.get("name").asText())
        assertEquals(ROBOTMC_VERSION_ID, ours.get("lastVersionId").asText())
        assertEquals(gameDirectory(minecraft).toString(), ours.get("gameDir").asText())
        assertEquals("Grass", ours.get("icon").asText())
        assertEquals("2020-01-01T00:00:00Z", ours.get("created").asText())
        assertEquals("-Xmx2G", ours.get("javaArgs").asText())
        assertEquals(true, ours.get("unknownProfileField").get("keep").asBoolean())
    }

    @Test
    fun installAddsRobotMcProfileWithoutRunningTheLoader() {
        installAlreadyPresent(
            type = ModLoaderType.NEO_FORGE,
            loaderVersion = "21.11.6-beta",
            profileKey = "NeoForge",
            visibleName = "NeoForge",
            lastVersionId = "neoforge-21.11.6-beta",
            directoryFlag = "--install-client",
        )
        installAlreadyPresent(
            type = ModLoaderType.FABRIC,
            loaderVersion = "0.16.14",
            profileKey = "fabric-loader-1.21.11",
            visibleName = "fabric-loader-0.16.14-1.21.11",
            lastVersionId = "fabric-loader-0.16.14-1.21.11",
            directoryFlag = "-dir",
        )
    }

    private fun assertDirectEdit(profileKey: String, visibleName: String, lastVersionId: String) {
        val minecraft = Files.createTempDirectory("robotmc-profile").resolve("My Minecraft")
        Files.createDirectories(minecraft)
        val document = profilesDocument(profileKey, visibleName, lastVersionId)

        val edited = editRobotMcLauncherProfile(document, gameDirectory(minecraft), "RobotMC")

        assertProfile(minecraft, document, edited, profileKey, visibleName, lastVersionId, "RobotMC")
    }

    @Test
    fun configuredProfileNameIsWrittenAndABlankNameStaysRobotMc() {
        installAlreadyPresent(
            type = ModLoaderType.NEO_FORGE,
            loaderVersion = "21.11.6-beta",
            profileKey = "NeoForge",
            visibleName = "NeoForge",
            lastVersionId = "neoforge-21.11.6-beta",
            directoryFlag = "--install-client",
            profileName = "Pack From Config",
            expectedProfileName = "Pack From Config",
        )
        installAlreadyPresent(
            type = ModLoaderType.FABRIC,
            loaderVersion = "0.16.14",
            profileKey = "fabric-loader-1.21.11",
            visibleName = "fabric-loader-0.16.14-1.21.11",
            lastVersionId = "fabric-loader-0.16.14-1.21.11",
            directoryFlag = "-dir",
            profileName = "   ",
            expectedProfileName = "RobotMC",
        )
    }

    private fun installAlreadyPresent(
        type: ModLoaderType,
        loaderVersion: String,
        profileKey: String,
        visibleName: String,
        lastVersionId: String,
        directoryFlag: String,
        profileName: String = "RobotMC",
        expectedProfileName: String = "RobotMC",
    ) {
        val minecraft = Files.createTempDirectory("robotmc-profile").resolve("My Minecraft")
        Files.createDirectories(minecraft)
        val document = profilesDocument(profileKey, visibleName, lastVersionId)
        val profilesPath = minecraft.resolve("launcher_profiles.json")
        Files.writeString(profilesPath, document)
        val jarDirectory = Files.createTempDirectory("robotmc-jars")
        val properties = InstallerProperties(
            minecraft = InstallerProperties.Minecraft(version = "1.21.11", directory = minecraft),
            mod = InstallerProperties.Mod(
                loader = InstallerProperties.Mod.Loader(
                    type = type,
                    version = loaderVersion,
                    installOptions = listOf("--install-client"),
                ),
            ),
            profileName = profileName,
        )
        val placed = decideModLoaderInstall(
            type = type,
            loaderVersion = loaderVersion,
            minecraftVersion = "1.21.11",
            minecraftDirectory = minecraft,
            installOptions = listOf("--install-client"),
            profiles = LauncherProfilesJson(
                profiles = mapOf(profileKey to inMemoryProfile(visibleName, lastVersionId)),
            ),
            installerDirectory = jarDirectory,
        )
        Files.write(placed.installerJar, byteArrayOf(1))
        assertFalse(placed.runInstaller)
        assertDirectoryArgument(placed.arguments, directoryFlag, minecraft)
        val service = ModLoaderInstallService(
            LauncherService(properties, Jackson2ObjectMapperBuilder.json().build()),
            RestClient.builder().requestFactory(RefusingRequests()).build(),
            properties,
            ProgressService(),
        )
        service.installerDirectory = jarDirectory

        service.install()
        service.install()

        val written = Files.readString(profilesPath)
        assertProfile(minecraft, document, written, profileKey, visibleName, lastVersionId, expectedProfileName)
        assertFalse(Files.exists(expectedGameDir(minecraft).resolve("launcher_profiles.json")))
        val reread = LauncherService(properties, Jackson2ObjectMapperBuilder.json().build()).getProfiles()
        val skipped = decideModLoaderInstall(
            type = type,
            loaderVersion = loaderVersion,
            minecraftVersion = "1.21.11",
            minecraftDirectory = minecraft,
            installOptions = listOf("--install-client"),
            profiles = reread,
            installerDirectory = jarDirectory,
        )
        assertFalse(skipped.runInstaller)
        assertEquals(lastVersionId, skipped.profileVersionId)
        assertDirectoryArgument(skipped.arguments, directoryFlag, minecraft)
        assertEquals(placed.installerJar, skipped.installerJar)
        assertTrue(Files.isRegularFile(placed.installerJar))
        val alias = loaderVersionAliasPath(minecraft)
        assertTrue(Files.isRegularFile(alias))
        assertEquals(lastVersionId, loaderVersionAliasInherits(Files.readString(alias)))
        assertEquals(ROBOTMC_VERSION_ID, ObjectMapper().readTree(Files.readString(alias)).get("id").asText())
        assertFalse(Files.exists(expectedGameDir(minecraft).resolve("versions")))
    }

    @Test
    fun aliasMatchingTheLoaderSkipsTheInstaller() {
        val minecraft = Files.createTempDirectory("robotmc-profile").resolve("My Minecraft")
        Files.createDirectories(minecraft)
        Files.writeString(
            minecraft.resolve("launcher_profiles.json"),
            """{"profiles":{"vanilla":{"name":"Latest Release","type":"latest-release","icon":"Grass","lastVersionId":"latest-release"}}}""",
        )
        writeLoaderVersionAlias(minecraft, "neoforge-21.11.6-beta")
        val jarDirectory = Files.createTempDirectory("robotmc-jars")
        val properties = InstallerProperties(
            minecraft = InstallerProperties.Minecraft(version = "1.21.11", directory = minecraft),
            mod = InstallerProperties.Mod(
                loader = InstallerProperties.Mod.Loader(
                    type = ModLoaderType.NEO_FORGE,
                    version = "21.11.6-beta",
                    installOptions = listOf("--install-client"),
                ),
            ),
        )
        val placed = decideModLoaderInstall(
            type = ModLoaderType.NEO_FORGE,
            loaderVersion = "21.11.6-beta",
            minecraftVersion = "1.21.11",
            minecraftDirectory = minecraft,
            installOptions = listOf("--install-client"),
            profiles = LauncherProfilesJson(profiles = emptyMap()),
            installerDirectory = jarDirectory,
            aliasInheritsFrom = "neoforge-21.11.6-beta",
        )
        Files.write(placed.installerJar, byteArrayOf(1))
        assertFalse(placed.runInstaller)
        val service = ModLoaderInstallService(
            LauncherService(properties, Jackson2ObjectMapperBuilder.json().build()),
            RestClient.builder().requestFactory(RefusingRequests()).build(),
            properties,
            ProgressService(),
        )
        service.installerDirectory = jarDirectory

        service.install()

        val written = ObjectMapper().readTree(Files.readString(minecraft.resolve("launcher_profiles.json")))
        val robotmc = written.get("profiles").get(ROBOTMC_PROFILE_KEY)
        assertEquals(ROBOTMC_VERSION_ID, robotmc.get("lastVersionId").asText())
        assertEquals("RobotMC", robotmc.get("name").asText())
        assertEquals("latest-release", written.get("profiles").get("vanilla").get("lastVersionId").asText())
        assertEquals("neoforge-21.11.6-beta", readLoaderVersionAliasInherits(minecraft))
    }

    private fun assertDirectoryArgument(arguments: List<String>, flag: String, minecraft: Path) {
        assertEquals(minecraft.toString(), arguments[arguments.indexOf(flag) + 1])
        assertFalse(arguments.contains(expectedGameDir(minecraft).toString()))
    }

    private fun assertProfile(
        minecraft: Path,
        before: String,
        after: String,
        profileKey: String,
        visibleName: String,
        lastVersionId: String,
        expectedProfileName: String,
    ) {
        val mapper = ObjectMapper()
        val original = mapper.readTree(before)
        val edited = mapper.readTree(after)
        assertEquals(names(original), names(edited))
        for (field in listOf("settings", "accounts", "selectedProfile", "version")) {
            assertEquals(original.get(field), edited.get(field), field)
        }
        assertEquals("vanilla", edited.get("selectedProfile").asText())
        val beforeProfiles = original.get("profiles")
        val afterProfiles = edited.get("profiles")
        assertEquals(names(beforeProfiles) + ROBOTMC_PROFILE_KEY, names(afterProfiles))
        for (key in names(beforeProfiles)) {
            assertEquals(beforeProfiles.get(key), afterProfiles.get(key), key)
        }
        assertEquals(visibleName, afterProfiles.get(profileKey).get("name").asText())
        assertEquals(lastVersionId, afterProfiles.get(profileKey).get("lastVersionId").asText())
        assertTrue(afterProfiles.get(profileKey).get("gameDir").isNull)
        assertEquals("personal-pack", afterProfiles.get("personal-pack").get("name").asText())
        assertEquals("/games/personal-pack", afterProfiles.get("personal-pack").get("gameDir").asText())
        assertEquals(lastVersionId, afterProfiles.get("personal-pack").get("lastVersionId").asText())

        val robotmc = afterProfiles.get(ROBOTMC_PROFILE_KEY)
        assertEquals(expectedProfileName, robotmc.get("name").asText())
        assertEquals(ROBOTMC_VERSION_ID, robotmc.get("lastVersionId").asText())
        assertEquals("custom", robotmc.get("type").asText())
        assertTrue(robotmc.get("icon").isTextual)
        assertTrue(robotmc.get("created").isTextual)
        val gameDir = Path.of(robotmc.get("gameDir").textValue())
        assertEquals(expectedGameDir(minecraft), gameDir)
        assertTrue(gameDir.isAbsolute)
        assertNotEquals(minecraft.toAbsolutePath().normalize(), gameDir)
    }

    private fun names(node: JsonNode): Set<String> {
        val names = mutableSetOf<String>()
        val iterator = (node as ObjectNode).fieldNames()
        while (iterator.hasNext()) {
            names += iterator.next()
        }
        return names
    }

    private fun expectedGameDir(minecraft: Path): Path {
        return minecraft.toAbsolutePath().normalize().resolve("robotmc")
    }

    private fun profilesDocument(profileKey: String, visibleName: String, lastVersionId: String): String {
        return """
            {
              "settings": {"keepLauncherOpen": false, "profileSorting": "ByLastPlayed"},
              "accounts": {"active": "player"},
              "selectedProfile": "vanilla",
              "version": 3,
              "profiles": {
                "$profileKey": {
                  "name": "$visibleName",
                  "type": "custom",
                  "created": "2024-01-02T03:04:05.000Z",
                  "lastUsed": "2024-05-06T07:08:09.000Z",
                  "icon": "data:image/png;base64,AAAA",
                  "lastVersionId": "$lastVersionId",
                  "gameDir": null,
                  "javaDir": "/opt/jdk",
                  "javaArgs": "-Xmx4G -XX:+UseG1GC",
                  "logConfig": null,
                  "logConfigIsXml": null,
                  "resolution": {"width": 1280, "height": 720},
                  "unknownProfileField": {"keep": true}
                },
                "personal-pack": {
                  "name": "personal-pack",
                  "type": "custom",
                  "created": "2023-03-03T03:03:03.000Z",
                  "icon": "Bookshelf",
                  "lastVersionId": "$lastVersionId",
                  "gameDir": "/games/personal-pack",
                  "javaArgs": "-Xmx8G"
                },
                "vanilla": {
                  "name": "Latest Release",
                  "type": "latest-release",
                  "created": "2020-01-01T00:00:00.000Z",
                  "lastUsed": null,
                  "icon": "Grass",
                  "lastVersionId": "latest-release",
                  "gameDir": null,
                  "javaDir": null,
                  "javaArgs": null,
                  "logConfig": null,
                  "logConfigIsXml": null,
                  "resolution": null
                }
              }
            }
        """.trimIndent()
    }

    private fun inMemoryProfile(visibleName: String, lastVersionId: String): LauncherProfilesJson.Profile {
        return LauncherProfilesJson.Profile(
            name = visibleName,
            type = "custom",
            created = null,
            lastUsed = null,
            icon = "data:image/png;base64,AAAA",
            lastVersionId = lastVersionId,
            gameDir = null,
            javaDir = "/opt/jdk",
            javaArgs = "-Xmx4G -XX:+UseG1GC",
            logConfig = null,
            logConfigIsXml = null,
            resolution = null,
        )
    }
}

private class RefusingRequests : ClientHttpRequestFactory {
    override fun createRequest(uri: URI, httpMethod: HttpMethod): ClientHttpRequest {
        throw IllegalStateException("unexpected download $uri")
    }
}
