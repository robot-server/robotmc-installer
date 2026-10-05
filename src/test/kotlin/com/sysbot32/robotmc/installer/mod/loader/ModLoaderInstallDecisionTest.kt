package com.sysbot32.robotmc.installer.mod.loader

import com.sysbot32.robotmc.installer.launcher.LauncherProfilesJson
import org.apache.commons.exec.ExecuteException
import java.nio.file.Paths
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModLoaderInstallDecisionTest {
    @Test
    fun fabricClientInstallUsesTheInstallerJarAndLoaderProfileId() {
        val decision = fabricDecision(profiles())

        assertEquals(
            "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.0.3/fabric-installer-1.0.3.jar",
            decision.installerUrl,
        )
        assertFalse(decision.installerUrl.contains("0.16.14"))
        assertEquals("fabric-loader-0.16.14-1.21.11", decision.profileVersionId)
        assertEquals(fabricCommand, decision.arguments)
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
        assertEquals(neoForgeCommand, decision.arguments)
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

    private fun fabricDecision(profiles: LauncherProfilesJson): ModLoaderInstallDecision {
        return decideModLoaderInstall(
            type = ModLoaderType.FABRIC,
            loaderVersion = "0.16.14",
            minecraftVersion = "1.21.11",
            minecraftDirectory = Paths.get("/tmp/My Minecraft/instance"),
            installOptions = listOf("--install-client"),
            profiles = profiles,
        )
    }

    private fun neoForgeDecision(profiles: LauncherProfilesJson): ModLoaderInstallDecision {
        return decideModLoaderInstall(
            type = ModLoaderType.NEO_FORGE,
            loaderVersion = "21.11.6-beta",
            minecraftVersion = "1.21.11",
            minecraftDirectory = Paths.get("/tmp/My Minecraft/instance"),
            installOptions = listOf("--install-client"),
            profiles = profiles,
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
        "fabric-installer-1.0.3.jar",
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
        "neoforge-21.11.6-beta-installer.jar",
        "--install-client",
        "/tmp/My Minecraft/instance",
    )
}

class ModLoaderCommandTest {
    @Test
    fun watchdogKillsAProcessThatDoesNotExit() {
        val started = System.nanoTime()
        assertFailsWith<ExecuteException> {
            executeModLoaderCommand(listOf("/bin/sleep", "30"), Duration.ofMillis(500))
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 5_000, "elapsed ${elapsedMs}ms")
    }
}
