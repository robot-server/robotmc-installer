package com.sysbot32.robotmc.installer.config

import com.sysbot32.robotmc.installer.mod.ModInstallService
import com.sysbot32.robotmc.installer.mod.loader.ModLoaderType
import com.sysbot32.robotmc.installer.progress.ProgressService
import com.sysbot32.robotmc.installer.resource_pack.ResourcePackInstallService
import com.sysbot32.robotmc.installer.server.ServerService
import com.sysbot32.robotmc.installer.server.ServersDat
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstalledRecordTest {
    @Test
    fun pathsSkipUnsafeFileNames() {
        val properties = InstallerProperties(
            minecraft = InstallerProperties.Minecraft(
                version = "1.21.11",
                directory = Paths.get("/tmp/minecraft"),
            ),
            mod = InstallerProperties.Mod(
                loader = InstallerProperties.Mod.Loader(ModLoaderType.FABRIC, "0.16.0"),
                mods = listOf(
                    InstallerProperties.Mod.Mod("https://example.com/good.jar"),
                    InstallerProperties.Mod.Mod("https://example.com/.."),
                ),
            ),
            resourcePacks = listOf(
                InstallerProperties.ResourcePack("https://example.com/pack.zip"),
                InstallerProperties.ResourcePack("https://example.com/.."),
            ),
        )

        assertEquals(listOf("resourcepacks/pack.zip"), installedPaths(properties))
    }

    @Test
    fun recordedNamesStayInsideTheFolder() {
        assertEquals(
            listOf("pack.zip"),
            recordedNames(
                listOf("resourcepacks/pack.zip", "resourcepacks/../secret", "resourcepacks/..", "mods/a.jar"),
                "resourcepacks",
            ),
        )
    }

    @Test
    fun writeThenReadRoundTrips() {
        val directory = Files.createTempDirectory("installed-record")

        InstalledRecord.write(directory, listOf("mods/a.jar", "resourcepacks/b.zip"))

        assertEquals(listOf("mods/a.jar", "resourcepacks/b.zip"), InstalledRecord.read(directory))
        assertEquals(emptyList(), InstalledRecord.read(directory.resolve("missing")))
    }

    @Test
    fun successfulInstallRecordsResourcePackNames() {
        val minecraft = Files.createTempDirectory("installed-record")
        val properties = properties(
            minecraft,
            mods = listOf("https://example.com/good.jar", "https://example.com/.."),
            packs = listOf("https://example.com/pack.zip"),
        )

        InstalledRecordService(properties).install()

        assertEquals(
            listOf("resourcepacks/pack.zip"),
            InstalledRecord.read(expectedGameDir(minecraft)),
        )
        assertFalse(Files.exists(InstalledRecord.file(minecraft)))
        assertTrue(InstalledRecordService(properties).order > packService(properties).order)
        assertTrue(packService(properties).order > modService(properties).order)
    }

    @Test
    fun reinstallRemovesAPreviouslyRecordedResourcePack() {
        val minecraft = Files.createTempDirectory("installed-record")
        val packs = expectedGameDir(minecraft).resolve("resourcepacks")
        val minecraftPacks = minecraft.resolve("resourcepacks")
        Files.createDirectories(packs)
        Files.createDirectories(minecraftPacks)
        Files.writeString(packs.resolve("a.zip"), "a")
        Files.writeString(packs.resolve("b.zip"), "b")
        Files.writeString(packs.resolve("user.zip"), "user")
        Files.writeString(minecraftPacks.resolve("a.zip"), "keep-a")
        Files.writeString(minecraftPacks.resolve("b.zip"), "keep-b")
        Files.writeString(minecraftPacks.resolve("user.zip"), "keep-user")
        InstalledRecord.write(expectedGameDir(minecraft), listOf("resourcepacks/a.zip"))
        InstalledRecord.write(minecraft, listOf("resourcepacks/user.zip"))
        val properties = properties(
            minecraft,
            mods = emptyList(),
            packs = listOf("https://example.com/b.zip"),
        )

        packService(properties).install()
        InstalledRecordService(properties).install()

        assertFalse(Files.exists(packs.resolve("a.zip")))
        assertEquals("b", Files.readString(packs.resolve("b.zip")))
        assertEquals("user", Files.readString(packs.resolve("user.zip")))
        assertEquals("keep-a", Files.readString(minecraftPacks.resolve("a.zip")))
        assertEquals("keep-b", Files.readString(minecraftPacks.resolve("b.zip")))
        assertEquals("keep-user", Files.readString(minecraftPacks.resolve("user.zip")))
        assertEquals(listOf("resourcepacks/b.zip"), InstalledRecord.read(expectedGameDir(minecraft)))
        assertEquals(listOf("resourcepacks/user.zip"), InstalledRecord.read(minecraft))
    }

    @Test
    fun installMovesExistingModsOnlyInsideTheGameDirectory() {
        val minecraft = Files.createTempDirectory("installed-record")
        val gameDir = expectedGameDir(minecraft)
        Files.createDirectories(gameDir.resolve("mods"))
        Files.createDirectories(minecraft.resolve("mods"))
        Files.createDirectories(minecraft.resolve("mods_old"))
        Files.writeString(gameDir.resolve("mods/leftover.jar"), "game")
        Files.writeString(gameDir.resolve("mods/current.jar"), "current")
        Files.writeString(minecraft.resolve("mods/leftover.jar"), "vanilla-mods")
        Files.writeString(minecraft.resolve("mods/current.jar"), "vanilla-current")
        Files.writeString(minecraft.resolve("mods_old/leftover.jar"), "vanilla-old")
        Files.createDirectories(minecraft.resolve("versions"))
        Files.writeString(minecraft.resolve("versions/keep.txt"), "version")
        Files.createDirectories(minecraft.resolve("libraries"))
        Files.writeString(minecraft.resolve("libraries/keep.txt"), "lib")
        Files.createDirectories(minecraft.resolve("assets"))
        Files.writeString(minecraft.resolve("assets/keep.txt"), "assets")
        Files.createDirectories(minecraft.resolve("saves/World"))
        Files.writeString(minecraft.resolve("saves/World/level.dat"), "save")
        val properties = properties(
            minecraft,
            mods = listOf("https://example.com/current.jar"),
            packs = emptyList(),
        )

        modService(properties).install()

        assertEquals("game", Files.readString(gameDir.resolve("mods_old/leftover.jar")))
        assertFalse(Files.exists(gameDir.resolve("mods/leftover.jar")))
        assertEquals("current", Files.readString(gameDir.resolve("mods/current.jar")))
        assertEquals("current", Files.readString(gameDir.resolve("mods_old/current.jar")))
        assertEquals("vanilla-mods", Files.readString(minecraft.resolve("mods/leftover.jar")))
        assertEquals("vanilla-current", Files.readString(minecraft.resolve("mods/current.jar")))
        assertEquals("vanilla-old", Files.readString(minecraft.resolve("mods_old/leftover.jar")))
        assertFalse(Files.exists(minecraft.resolve("mods_old/current.jar")))
        assertEquals("version", Files.readString(minecraft.resolve("versions/keep.txt")))
        assertEquals("lib", Files.readString(minecraft.resolve("libraries/keep.txt")))
        assertEquals("assets", Files.readString(minecraft.resolve("assets/keep.txt")))
        assertEquals("save", Files.readString(minecraft.resolve("saves/World/level.dat")))
        assertFalse(Files.exists(gameDir.resolve("saves")))
        assertFalse(Files.exists(gameDir.resolve("versions")))
        assertFalse(Files.exists(gameDir.resolve("libraries")))
        assertFalse(Files.exists(gameDir.resolve("assets")))
        assertEquals(gameDir, gameDirectory(minecraft))
    }

    @Test
    fun serverListIsWrittenOnlyUnderTheGameDirectory() {
        val minecraft = Files.createTempDirectory("installed-record")
        val gameDir = expectedGameDir(minecraft)
        Files.writeString(minecraft.resolve("servers.dat"), "vanilla")
        Files.createDirectories(minecraft.resolve("saves/World"))
        Files.writeString(minecraft.resolve("saves/World/level.dat"), "save")
        val properties = properties(minecraft, mods = emptyList(), packs = emptyList()).copy(
            servers = listOf(ServersDat.Server(ip = "minecraft.o-r.cc", name = "Robot Server")),
        )
        val service = ServerService(properties, ProgressService())

        service.install()

        assertEquals("vanilla", Files.readString(minecraft.resolve("servers.dat")))
        assertTrue(Files.isRegularFile(gameDir.resolve("servers.dat")))
        assertFalse(Files.exists(gameDir.resolve("saves")))
        assertEquals("save", Files.readString(minecraft.resolve("saves/World/level.dat")))
        assertEquals(listOf("minecraft.o-r.cc"), service.getServers().servers.map { it.ip })
        assertEquals(listOf("Robot Server"), service.getServers().servers.map { it.name })

        service.uninstall()
        assertTrue(Files.isRegularFile(gameDir.resolve("servers.dat")))
        assertEquals("vanilla", Files.readString(minecraft.resolve("servers.dat")))
    }

    private fun expectedGameDir(minecraft: Path): Path {
        return minecraft.toAbsolutePath().normalize().resolve("robotmc")
    }

    private fun properties(minecraft: Path, mods: List<String>, packs: List<String>): InstallerProperties {
        return InstallerProperties(
            minecraft = InstallerProperties.Minecraft(version = "1.21.11", directory = minecraft),
            mod = InstallerProperties.Mod(
                loader = InstallerProperties.Mod.Loader(ModLoaderType.NEO_FORGE, "21.11.6-beta"),
                mods = mods.map { InstallerProperties.Mod.Mod(it) },
            ),
            resourcePacks = packs.map { InstallerProperties.ResourcePack(it) },
        )
    }

    private fun modService(properties: InstallerProperties): ModInstallService {
        return ModInstallService(RestClient.builder().build(), properties, ProgressService())
    }

    private fun packService(properties: InstallerProperties): ResourcePackInstallService {
        return ResourcePackInstallService(RestClient.builder().build(), properties, ProgressService())
    }
}
