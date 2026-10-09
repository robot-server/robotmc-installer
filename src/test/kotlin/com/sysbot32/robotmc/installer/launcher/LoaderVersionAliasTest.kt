package com.sysbot32.robotmc.installer.launcher

import com.fasterxml.jackson.databind.ObjectMapper
import com.sysbot32.robotmc.installer.config.DEFAULT_VERSION_ID
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
}
