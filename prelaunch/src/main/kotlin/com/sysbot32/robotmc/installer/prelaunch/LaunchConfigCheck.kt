package com.sysbot32.robotmc.installer.prelaunch

import java.nio.file.Files
import java.nio.file.Path

private val FABRIC_LOADER_ID = Regex("fabric-loader-.+-\\d+(?:\\.\\d+)+")

/**
 * 원격 application.yml 과 프로필이 가리키는 로더, gameDir/mods 의 JAR 이름이 같은지 본다.
 * lastVersionId 가 이미 로더 id 면 그 값을 쓰고, 아니면 version JSON 의 inheritsFrom 을 쓴다.
 * RobotMC 같은 별칭 문자열은 로더 버전이 아니다.
 */
fun checkLaunchConfig(minecraftDirectory: Path, profileKey: String, applicationYaml: String): LaunchCheck {
    val remote = InstallerYaml.parse(applicationYaml)
    val profilesText = Files.readString(minecraftDirectory.resolve("launcher_profiles.json"))
    val profile = readProfile(profilesText, profileKey)
    val launched = launchedLoaderId(minecraftDirectory, profile.lastVersionId)
    val localJars = localModJarNames(profile.gameDir)
    val match = launched != null && launched == remote.loaderId && localJars == remote.modJarNames
    return LaunchCheck(
        match = match,
        launchedLoaderId = launched,
        remoteLoaderId = remote.loaderId,
        localJarNames = localJars,
        remoteJarNames = remote.modJarNames.toSet(),
    )
}

/**
 * 이미 neoforge- 또는 fabric-loader- 이면 lastVersionId 자체다.
 * 아니면 versions 아래 그 id 의 JSON 에 있는 inheritsFrom 이다.
 */
fun launchedLoaderId(minecraftDirectory: Path, lastVersionId: String?): String? {
    if (lastVersionId.isNullOrBlank()) {
        return null
    }
    if (isDirectLoaderVersionId(lastVersionId)) {
        return lastVersionId
    }
    if (!isSafeSegment(lastVersionId)) {
        return null
    }
    val versionJson = minecraftDirectory.resolve("versions").resolve(lastVersionId).resolve("$lastVersionId.json")
    if (!Files.isRegularFile(versionJson)) {
        return null
    }
    return inheritsFrom(Files.readString(versionJson))
}

fun isDirectLoaderVersionId(id: String): Boolean {
    if (id.startsWith("neoforge-") && id.length > "neoforge-".length) {
        return true
    }
    return FABRIC_LOADER_ID.matches(id)
}

data class LaunchCheck(
    val match: Boolean,
    val launchedLoaderId: String?,
    val remoteLoaderId: String?,
    val localJarNames: Set<String>,
    val remoteJarNames: Set<String>,
)

private fun inheritsFrom(versionJson: String): String? {
    val inherits = Json.parse(versionJson).obj()["inheritsFrom"] ?: return null
    if (inherits.isNull()) {
        return null
    }
    val value = inherits.stringOrNull()
    if (value.isNullOrBlank()) {
        return null
    }
    return value
}

private fun localModJarNames(gameDir: String?): Set<String> {
    if (gameDir.isNullOrBlank()) {
        return emptySet()
    }
    val mods = Path.of(gameDir).resolve("mods")
    if (!Files.isDirectory(mods)) {
        return emptySet()
    }
    val names = linkedSetOf<String>()
    Files.list(mods).use { children ->
        for (child in children) {
            if (!Files.isRegularFile(child)) {
                continue
            }
            val name = child.fileName.toString()
            if (name.endsWith(".jar", ignoreCase = true)) {
                names += name
            }
        }
    }
    return names
}

private data class ProfileFields(
    val lastVersionId: String?,
    val gameDir: String?,
)

private fun readProfile(profilesText: String, profileKey: String): ProfileFields {
    val profiles = Json.parse(profilesText).obj()["profiles"] ?: return ProfileFields(null, null)
    if (profiles.isNull()) {
        return ProfileFields(null, null)
    }
    val profile = profiles.obj()[profileKey] ?: return ProfileFields(null, null)
    if (profile.isNull()) {
        return ProfileFields(null, null)
    }
    val fields = profile.obj()
    return ProfileFields(text(fields["lastVersionId"]), text(fields["gameDir"]))
}

private fun text(value: Json.JsonValue?): String? {
    if (value == null || value.isNull()) {
        return null
    }
    return value.stringOrNull()
}

private fun isSafeSegment(value: String): Boolean {
    return value != "." &&
        value != ".." &&
        '/' !in value &&
        '\\' !in value &&
        '\u0000' !in value
}
