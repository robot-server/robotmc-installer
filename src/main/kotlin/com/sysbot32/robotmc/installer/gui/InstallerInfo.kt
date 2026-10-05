package com.sysbot32.robotmc.installer.gui

import com.sysbot32.robotmc.installer.config.InstallerProperties
import java.awt.Desktop
import java.awt.EventQueue

const val APPLICATION_NAME = "RobotMC Installer"

data class SettingsRow(
    val label: String,
    val value: String,
)

data class PlanSection(
    val title: String,
    val items: List<String>,
)

fun InstallerProperties.plan(mode: InstallerProperties.Mode = this.mode): List<PlanSection> {
    val sections = mutableListOf<PlanSection>()
    this.mod?.loader?.let { loader ->
        sections += PlanSection("모드 로더", listOf(loaderLabel(loader)))
    }
    val mods = this.mod?.mods?.map { downloadFileName(it.downloadUrl) }.orEmpty()
    if (mods.isNotEmpty()) {
        sections += PlanSection("모드", mods)
    }
    if (mode == InstallerProperties.Mode.INSTALL) {
        val servers = this.servers.map { serverLabel(it.name, it.ip) }
        if (servers.isNotEmpty()) {
            sections += PlanSection("서버", servers)
        }
    }
    val packs = this.resourcePacks.map { downloadFileName(it.downloadUrl) }
    if (packs.isNotEmpty()) {
        sections += PlanSection("리소스 팩", packs)
    }
    return sections
}

fun InstallerProperties.settingsRows(mode: InstallerProperties.Mode = this.mode): List<SettingsRow> {
    val rows = mutableListOf(
        SettingsRow(
            "동작",
            when (mode) {
                InstallerProperties.Mode.INSTALL -> "설치"
                InstallerProperties.Mode.UNINSTALL -> "제거"
            },
        ),
        SettingsRow("Minecraft", this.minecraft.version),
        SettingsRow("폴더", this.minecraft.directory.toString()),
    )
    this.mod?.loader?.let { loader ->
        rows += SettingsRow("모드 로더", loaderLabel(loader))
    }
    this.mod?.mods?.forEach { mod ->
        rows += SettingsRow("모드", downloadFileName(mod.downloadUrl))
    }
    this.servers.forEach { server ->
        rows += SettingsRow("서버", serverLabel(server.name, server.ip))
    }
    this.resourcePacks.forEach { pack ->
        rows += SettingsRow("리소스 팩", downloadFileName(pack.downloadUrl))
    }
    return rows
}

private fun loaderLabel(loader: InstallerProperties.Mod.Loader): String {
    val version = loader.version?.let { " $it" }.orEmpty()
    return loader.type.displayName + version
}

private fun downloadFileName(url: String): String = url.substringAfterLast('/')

private fun serverLabel(name: String, ip: String): String = "$name ($ip)"

fun applicationVersion(): String? {
    return InstallerInfo::class.java.`package`?.implementationVersion?.takeIf { it.isNotBlank() }
}

/**
 * macOS 애플리케이션 메뉴의 정보·설정. 지원하지 않으면 false.
 * 핸들러는 AWT 이벤트 스레드에서 상태를 바꾼다.
 */
fun registerApplicationMenu(onAbout: () -> Unit, onSettings: () -> Unit): Boolean {
    if (!Desktop.isDesktopSupported()) {
        return false
    }
    val desktop = Desktop.getDesktop()
    val about = desktop.isSupported(Desktop.Action.APP_ABOUT)
    val settings = desktop.isSupported(Desktop.Action.APP_PREFERENCES)
    if (!about || !settings) {
        return false
    }
    desktop.setAboutHandler { _ -> EventQueue.invokeLater(onAbout) }
    desktop.setPreferencesHandler { _ -> EventQueue.invokeLater(onSettings) }
    return true
}

private object InstallerInfo
