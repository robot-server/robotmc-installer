package com.sysbot32.robotmc.installer.gui

import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.mod.loader.ModLoaderType
import com.sysbot32.robotmc.installer.server.ServersDat
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class InstallerInfoTest {
    @Test
    fun settingsRowsListTheActiveConfiguration() {
        val properties = sampleProperties()

        assertEquals(
            listOf(
                SettingsRow("동작", "설치"),
                SettingsRow("Minecraft", "1.21.11"),
                SettingsRow("폴더", Paths.get("/tmp/minecraft").toString()),
                SettingsRow("모드 로더", "NeoForge 21.11.6-beta"),
                SettingsRow("모드", "iris.jar"),
                SettingsRow("서버", "Robot Server (minecraft.o-r.cc)"),
                SettingsRow("리소스 팩", "Faithful.zip"),
            ),
            properties.settingsRows(),
        )
    }

    @Test
    fun planListsWhatInstallAddsAndWhatUninstallRemoves() {
        val properties = sampleProperties()

        assertEquals(
            listOf(
                PlanSection("모드 로더", listOf("NeoForge 21.11.6-beta")),
                PlanSection("모드", listOf("iris.jar")),
                PlanSection("서버", listOf("Robot Server (minecraft.o-r.cc)")),
                PlanSection("리소스 팩", listOf("Faithful.zip")),
            ),
            properties.plan(InstallerProperties.Mode.INSTALL),
        )
        assertEquals(
            listOf(
                PlanSection("모드 로더", listOf("NeoForge 21.11.6-beta")),
                PlanSection("모드", listOf("iris.jar")),
                PlanSection("리소스 팩", listOf("Faithful.zip")),
            ),
            properties.plan(InstallerProperties.Mode.UNINSTALL),
        )
    }

    private fun sampleProperties(): InstallerProperties {
        return InstallerProperties(
            mode = InstallerProperties.Mode.INSTALL,
            minecraft = InstallerProperties.Minecraft(
                version = "1.21.11",
                directory = Paths.get("/tmp/minecraft"),
            ),
            mod = InstallerProperties.Mod(
                loader = InstallerProperties.Mod.Loader(
                    type = ModLoaderType.NEO_FORGE,
                    version = "21.11.6-beta",
                ),
                mods = listOf(
                    InstallerProperties.Mod.Mod("https://example/iris.jar"),
                ),
            ),
            servers = listOf(ServersDat.Server(ip = "minecraft.o-r.cc", name = "Robot Server")),
            resourcePacks = listOf(
                InstallerProperties.ResourcePack("https://example/packs/Faithful.zip"),
            ),
        )
    }
}
