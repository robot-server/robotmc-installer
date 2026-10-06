package com.sysbot32.robotmc.installer.gui

import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.config.RemoteInstallerConfig
import com.sysbot32.robotmc.installer.config.pendingAppUpdate
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
        SettingsRow("구성", configSourceLabel(this.update.source)),
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

private fun configSourceLabel(source: String): String {
    return when (source) {
        RemoteInstallerConfig.SOURCE_ONLINE -> "온라인"
        RemoteInstallerConfig.SOURCE_CACHE -> "저장된 온라인 구성"
        else -> "설치 파일"
    }
}

private fun loaderLabel(loader: InstallerProperties.Mod.Loader): String {
    val version = loader.version?.let { " $it" }.orEmpty()
    return loader.type.displayName + version
}

private fun downloadFileName(url: String): String = url.substringAfterLast('/')

private fun serverLabel(name: String, ip: String): String = "$name ($ip)"

/**
 * 설치기에 들어 있는 버전. Gradle project version 을 클래스패스 리소스로 읽는다.
 * 리소스가 없으면 null 이다.
 */
fun applicationVersion(resource: String = INSTALLER_VERSION_RESOURCE): String? {
    val stream = InstallerInfo::class.java.getResourceAsStream(resource) ?: return null
    return stream.use { it.readBytes().decodeToString() }.trim().takeIf { it.isNotBlank() }
}

/**
 * 원격 설치기 버전이 내부 버전과 다를 때만 안내한다.
 * 후보가 아니거나 내부 버전이 비어 있으면 null 이다. JAR 는 받지 않는다.
 */
fun installerUpdateNotice(
    properties: InstallerProperties,
    internalVersion: String? = applicationVersion(),
): String? {
    val remote = properties.pendingAppUpdate()?.version ?: return null
    val internal = internalVersion?.trim().orEmpty()
    if (internal.isBlank() || remote == internal) {
        return null
    }
    return "원격 설치기 버전은 ${remote}이에요. 지금 버전은 ${internal}이에요."
}

private const val INSTALLER_VERSION_RESOURCE = "/installer-version.txt"

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
