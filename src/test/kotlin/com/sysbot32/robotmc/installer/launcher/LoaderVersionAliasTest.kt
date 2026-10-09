package com.sysbot32.robotmc.installer.launcher

import com.fasterxml.jackson.databind.ObjectMapper
import com.sysbot32.robotmc.installer.config.DEFAULT_VERSION_ID
import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.config.gameDirectory
import com.sysbot32.robotmc.installer.exception.UserException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoaderVersionAliasTest {
    @Test
    fun aliasPointsAtTheLoaderVersionAndKeepsOtherFields() {
        val minecraft = Files.createTempDirectory("robotmc-alias")
        val original = """
            {"id":"other","inheritsFrom":"neoforge-old","type":"release","time":"2020-01-01T00:00:00+00:00","releaseTime":"2020-01-01T00:00:00+00:00","keep":true}
        """.trimIndent()

        val edited = editLoaderVersionAlias(original, "neoforge-21.11.6-beta")

        val node = ObjectMapper().readTree(edited)
        assertEquals(DEFAULT_VERSION_ID, node.get("id").asText())
        assertEquals("neoforge-21.11.6-beta", node.get("inheritsFrom").asText())
        assertEquals("release", node.get("type").asText())
        assertEquals("2020-01-01T00:00:00+00:00", node.get("time").asText())
        assertEquals("2020-01-01T00:00:00+00:00", node.get("releaseTime").asText())
        assertTrue(node.get("keep").asBoolean())
        assertEquals(edited, editLoaderVersionAlias(edited, "neoforge-21.11.6-beta"))

        writeLoaderVersionAlias(minecraft, "neoforge-21.11.6-beta")
        val path = loaderVersionAliasPath(minecraft)
        assertEquals(minecraft.resolve("versions").resolve(DEFAULT_VERSION_ID).resolve("$DEFAULT_VERSION_ID.json"), path)
        assertTrue(Files.isRegularFile(path))
        assertEquals("neoforge-21.11.6-beta", readLoaderVersionAliasInherits(minecraft))
        assertFalse(Files.exists(minecraft.resolve("robotmc").resolve("versions")))
        assertNull(loaderVersionAliasInherits("""{"id":"RobotMC"}"""))
    }

    @Test
    fun deleteGameDirectoryKeepsSavesAndRemovesAnEmptyFolder() {
        val minecraft = Files.createTempDirectory("robotmc-alias")
        val withSaves = gameDirectory(minecraft, "kept")
        Files.createDirectories(withSaves.resolve("saves/World"))
        Files.writeString(withSaves.resolve("saves/World/level.dat"), "save")
        Files.writeString(withSaves.resolve("options.txt"), "options")
        val empty = gameDirectory(minecraft, "empty")
        Files.createDirectories(empty.resolve("mods"))
        Files.writeString(empty.resolve("mods/a.jar"), "mod")

        deleteGameDirectory(minecraft, "kept")
        deleteGameDirectory(minecraft, "empty")
        deleteGameDirectory(minecraft, "../outside")

        assertEquals("save", Files.readString(withSaves.resolve("saves/World/level.dat")))
        assertFalse(Files.exists(withSaves.resolve("options.txt")))
        assertFalse(Files.exists(empty))
        assertTrue(Files.isDirectory(minecraft))
    }

    @Test
    fun deleteDoesNotFollowANestedSymbolicLink() {
        val minecraft = Files.createTempDirectory("robotmc-alias")
        val gameDir = gameDirectory(minecraft, "pack")
        val outside = Files.createTempDirectory("robotmc-outside")
        Files.createDirectories(gameDir.resolve("config/nested"))
        Files.writeString(outside.resolve("keep.txt"), "keep")
        Files.createSymbolicLink(gameDir.resolve("config/nested/link"), outside)

        deleteGameDirectory(minecraft, "pack")

        assertEquals("keep", Files.readString(outside.resolve("keep.txt")))
        assertFalse(Files.exists(gameDir.resolve("config")))
    }

    @Test
    fun savesGameDirectoryNameIsRejected() {
        val minecraft = Files.createTempDirectory("robotmc-alias")
        Files.createDirectories(minecraft.resolve("saves/World"))
        Files.writeString(minecraft.resolve("saves/World/level.dat"), "world")
        val properties = InstallerProperties(
            minecraft = InstallerProperties.Minecraft(version = "1.21.11", directory = minecraft),
            mod = null,
            gameDirectoryName = "saves",
        )

        assertFailsWith<UserException> { properties.gameDirectory() }
        assertFailsWith<UserException> { deleteGameDirectory(minecraft, "saves") }
        assertEquals("world", Files.readString(minecraft.resolve("saves/World/level.dat")))
    }

    @Test
    fun aliasDoesNotReplaceOrDeleteTheLoaderVersion() {
        val minecraft = Files.createTempDirectory("robotmc-alias")
        val loaderJson = minecraft.resolve("versions/neoforge-21.11.6-beta/neoforge-21.11.6-beta.json")
        Files.createDirectories(loaderJson.parent)
        Files.writeString(loaderJson, """{"id":"neoforge-21.11.6-beta","inheritsFrom":"1.21.11"}""")

        assertFailsWith<UserException> {
            writeLoaderVersionAlias(minecraft, "neoforge-21.11.6-beta", "neoforge-21.11.6-beta")
        }
        deleteVersionAlias(minecraft, "neoforge-21.11.6-beta", "neoforge-21.11.6-beta")

        assertEquals(
            """{"id":"neoforge-21.11.6-beta","inheritsFrom":"1.21.11"}""",
            Files.readString(loaderJson),
        )
    }

    @Test
    fun installedLoaderJsonIsNotTreatedAsAnAlias() {
        val minecraft = Files.createTempDirectory("robotmc-alias")
        val loaderId = "neoforge-21.11.6-beta"
        val loaderJson = minecraft.resolve("versions/$loaderId/$loaderId.json")
        val original = """
            {"id":"$loaderId","inheritsFrom":"1.21.11","mainClass":"cpw.mods.bootstraplauncher.BootstrapLauncher","libraries":[{"name":"net.neoforged:neoforge:21.11.6-beta"}]}
        """.trimIndent()
        Files.createDirectories(loaderJson.parent)
        Files.writeString(loaderJson, original)

        assertFailsWith<UserException> {
            writeLoaderVersionAlias(minecraft, "neoforge-21.11.7-beta", loaderId)
        }
        deleteVersionAlias(minecraft, loaderId, "neoforge-21.11.7-beta")

        assertEquals(original, Files.readString(loaderJson))
        assertTrue(Files.isDirectory(loaderJson.parent))
    }

    @Test
    fun uninstallRemovesAnAliasThatPointsAtAnOlderLoader() {
        val minecraft = Files.createTempDirectory("robotmc-alias")
        writeLoaderVersionAlias(minecraft, "neoforge-21.11.6-beta", DEFAULT_VERSION_ID)

        deleteVersionAlias(minecraft, DEFAULT_VERSION_ID, "neoforge-21.11.7-beta")

        assertFalse(Files.exists(loaderVersionAliasPath(minecraft, DEFAULT_VERSION_ID)))
    }

    @Test
    fun deleteFailureIsReported() {
        val minecraft = Files.createTempDirectory("robotmc-alias")
        val gameDir = gameDirectory(minecraft, "pack")
        val locked = gameDir.resolve("locked")
        Files.createDirectories(locked)
        Files.writeString(locked.resolve("keep.txt"), "keep")
        locked.toFile().setWritable(false)
        try {
            assertFailsWith<UserException> { deleteGameDirectory(minecraft, "pack") }
            assertEquals("keep", Files.readString(locked.resolve("keep.txt")))
        } finally {
            locked.toFile().setWritable(true)
        }
    }
}
