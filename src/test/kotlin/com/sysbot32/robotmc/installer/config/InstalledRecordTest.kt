package com.sysbot32.robotmc.installer.config

import com.sysbot32.robotmc.installer.mod.ModInstallService
import com.sysbot32.robotmc.installer.mod.loader.ModLoaderType
import com.sysbot32.robotmc.installer.progress.ProgressService
import com.sysbot32.robotmc.installer.resource_pack.ResourcePackInstallService
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
            resourcePacks = listOf(InstallerProperties.ResourcePack("https://example.com/pack.zip")),
        )

        assertEquals(listOf("mods/good.jar", "resourcepacks/pack.zip"), installedPaths(properties))
    }

    @Test
    fun recordedNamesStayInsideTheFolder() {
        assertEquals(
            listOf("good.jar"),
            recordedNames(
                listOf("mods/good.jar", "mods/../secret", "mods/..", "resourcepacks/a.zip"),
                "mods",
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
    fun successfulInstallRecordsModAndResourcePackNames() {
        val minecraft = Files.createTempDirectory("installed-record")
        val properties = properties(
            minecraft,
            mods = listOf("https://example.com/good.jar", "https://example.com/.."),
            packs = listOf("https://example.com/pack.zip"),
        )

        InstalledRecordService(properties).install()

        assertEquals(listOf("mods/good.jar", "resourcepacks/pack.zip"), InstalledRecord.read(minecraft))
        assertTrue(InstalledRecordService(properties).order > packService(properties).order)
        assertTrue(packService(properties).order > modService(properties).order)
    }

    @Test
    fun uninstallDeletesTheUnionAndLeavesNamesThatEscapeTheFolder() {
        val minecraft = Files.createTempDirectory("installed-record")
        Files.createDirectories(minecraft.resolve("mods"))
        Files.createDirectories(minecraft.resolve("resourcepacks"))
        Files.writeString(minecraft.resolve("mods/old.jar"), "old")
        Files.writeString(minecraft.resolve("mods/current.jar"), "current")
        Files.writeString(minecraft.resolve("mods/user.jar"), "user")
        Files.writeString(minecraft.resolve("outside.txt"), "outside")
        Files.writeString(minecraft.resolve("resourcepacks/old.zip"), "old")
        Files.writeString(minecraft.resolve("resourcepacks/current.zip"), "current")
        InstalledRecord.write(
            minecraft,
            listOf(
                "mods/old.jar",
                "mods/../outside.txt",
                "mods/..",
                "resourcepacks/old.zip",
                "resourcepacks/../outside.txt",
            ),
        )
        val properties = properties(
            minecraft,
            mods = listOf("https://example.com/current.jar", "https://example.com/.."),
            packs = listOf("https://example.com/current.zip"),
        )

        modService(properties).uninstall()
        packService(properties).uninstall()

        assertFalse(Files.exists(minecraft.resolve("mods/old.jar")))
        assertFalse(Files.exists(minecraft.resolve("mods/current.jar")))
        assertTrue(Files.exists(minecraft.resolve("mods/user.jar")))
        assertTrue(Files.exists(minecraft.resolve("outside.txt")))
        assertTrue(Files.exists(minecraft.resolve("mods")))
        assertFalse(Files.exists(minecraft.resolve("resourcepacks/old.zip")))
        assertFalse(Files.exists(minecraft.resolve("resourcepacks/current.zip")))
        assertTrue(Files.exists(minecraft))
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
