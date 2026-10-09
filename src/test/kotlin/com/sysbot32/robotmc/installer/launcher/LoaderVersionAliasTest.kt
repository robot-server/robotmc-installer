package com.sysbot32.robotmc.installer.launcher

import com.fasterxml.jackson.databind.ObjectMapper
import com.sysbot32.robotmc.installer.config.DEFAULT_VERSION_ID
import com.sysbot32.robotmc.installer.config.gameDirectory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
