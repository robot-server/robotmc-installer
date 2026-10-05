package com.sysbot32.robotmc.installer.mod.loader

import com.sysbot32.robotmc.installer.launcher.LauncherProfilesJson
import org.apache.commons.exec.CommandLine
import java.nio.file.Path

private const val FABRIC_INSTALLER_VERSION = "1.0.3"

/**
 * 모드 로더 installer를 받을 주소, 프로필 version id, 프로세스 인자, 실행 여부.
 * 다운로드와 프로세스 실행은 여기 없다.
 */
data class ModLoaderInstallDecision(
    val installerUrl: String,
    val profileVersionId: String,
    val arguments: List<String>,
    val runInstaller: Boolean,
)

/**
 * [profiles]의 키는 보지 않는다. Fabric installer는 프로필 키를 `fabric-loader-<mc>`로 두고
 * `lastVersionId`에 `fabric-loader-<loader>-<mc>`를 쓴다.
 */
fun decideModLoaderInstall(
    type: ModLoaderType?,
    loaderVersion: String?,
    minecraftVersion: String,
    minecraftDirectory: Path,
    installOptions: List<String>,
    profiles: LauncherProfilesJson,
): ModLoaderInstallDecision {
    if (type == null) {
        throw IllegalArgumentException("Unsupported mod loader type null")
    }
    if (loaderVersion == null) {
        throw IllegalArgumentException("Mod loader version is required")
    }
    val installerUrl = installerUrl(type, loaderVersion)
    val profileVersionId = profileVersionId(type, loaderVersion, minecraftVersion)
    val arguments = listOf("java", "-jar", installerUrl.substringAfterLast('/')) +
        installerArguments(type, loaderVersion, minecraftVersion, minecraftDirectory, installOptions)
    val alreadyInstalled = profiles.profiles.values.any { it.lastVersionId == profileVersionId }
    return ModLoaderInstallDecision(
        installerUrl = installerUrl,
        profileVersionId = profileVersionId,
        arguments = arguments,
        runInstaller = !alreadyInstalled,
    )
}

/**
 * 인자 목록을 한 문자열로 합쳐 [CommandLine.parse]하지 않는다. 공백 있는 디렉터리가 갈라진다.
 * handleQuoting도 끈다. 켜면 따옴표가 인자 값에 남는다.
 */
fun modLoaderCommandLine(arguments: List<String>): CommandLine {
    val command = CommandLine(arguments.first())
    arguments.drop(1).forEach { argument ->
        command.addArgument(argument, false)
    }
    return command
}

private fun installerUrl(type: ModLoaderType, loaderVersion: String): String = when (type) {
    ModLoaderType.NEO_FORGE ->
        "https://maven.neoforged.net/releases/net/neoforged/neoforge/$loaderVersion/neoforge-$loaderVersion-installer.jar"
    ModLoaderType.FABRIC ->
        "https://maven.fabricmc.net/net/fabricmc/fabric-installer/$FABRIC_INSTALLER_VERSION/fabric-installer-$FABRIC_INSTALLER_VERSION.jar"
}

private fun profileVersionId(
    type: ModLoaderType,
    loaderVersion: String,
    minecraftVersion: String,
): String = when (type) {
    ModLoaderType.NEO_FORGE -> "neoforge-$loaderVersion"
    ModLoaderType.FABRIC -> "fabric-loader-$loaderVersion-$minecraftVersion"
}

private fun installerArguments(
    type: ModLoaderType,
    loaderVersion: String,
    minecraftVersion: String,
    minecraftDirectory: Path,
    installOptions: List<String>,
): List<String> = when (type) {
    // --install-client 의 선택 인자. 빠지면 설치기는 OS 기본 .minecraft 에 넣고,
    // 설치 여부 확인은 minecraft.directory 를 본다.
    ModLoaderType.NEO_FORGE -> installOptions + minecraftDirectory.toString()
    // Fabric client 설치는 -dir 로 디렉터리 하나를 받는다. loader 버전은 -loader 에만 쓴다.
    ModLoaderType.FABRIC -> listOf(
        "client",
        "-dir",
        minecraftDirectory.toString(),
        "-mcversion",
        minecraftVersion,
        "-loader",
        loaderVersion,
    )
}
