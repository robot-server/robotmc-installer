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
        val version = loader.version?.let { " $it" }.orEmpty()
        sections += PlanSection("모드 로더", listOf(loader.type.displayName + version))
    }
    val mods = this.mod?.mods?.map { it.downloadUrl.substringAfterLast('/') }.orEmpty()
    if (mods.isNotEmpty()) {
        sections += PlanSection("모드", mods)
    }
    if (mode == InstallerProperties.Mode.INSTALL) {
        val servers = this.servers.map { "${it.name} (${it.ip})" }
        if (servers.isNotEmpty()) {
            sections += PlanSection("서버", servers)
        }
    }
    val packs = this.resourcePacks.map { it.downloadUrl.substringAfterLast('/') }
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
        val version = loader.version?.let { " $it" }.orEmpty()
        rows += SettingsRow("모드 로더", loader.type.displayName + version)
    }
    this.mod?.mods?.forEach { mod ->
        rows += SettingsRow("모드", mod.downloadUrl.substringAfterLast('/'))
    }
    this.servers.forEach { server ->
        rows += SettingsRow("서버", "${server.name} (${server.ip})")
    }
    this.resourcePacks.forEach { pack ->
        rows += SettingsRow("리소스 팩", pack.downloadUrl.substringAfterLast('/'))
    }
    return rows
}

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
