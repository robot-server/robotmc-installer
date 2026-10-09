package com.sysbot32.robotmc.installer.launcher

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sysbot32.robotmc.installer.config.DEFAULT_PROFILE_ICON
import com.sysbot32.robotmc.installer.config.DEFAULT_PROFILE_KEY
import com.sysbot32.robotmc.installer.config.DEFAULT_VERSION_ID
import com.sysbot32.robotmc.installer.config.pathSegment
import com.sysbot32.robotmc.installer.prelaunch.mergeJavaAgent
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * https://minecraft.fandom.com/wiki/Launcher_profiles.json
 */
data class LauncherProfilesJson(
    /**
     * All the launcher profiles and their configurations.
     */
    val profiles: Map<String, Profile>,
) {
    /**
     * Profiles are saved in a map in the profiles section.
     */
    data class Profile(
        /**
         * The profile name. Can include characters, numbers, punctuation, and whitespace
         */
        val name: String,
        /**
         * The profile type. Types are custom (manually created by the user), latest-release (uses the latest stable release), and latest-snapshot (uses the latest build of Minecraft).
         */
        val type: String,
        /**
         * An ISO 8601 formatted date which represents the time the profile was created.
         */
        val created: OffsetDateTime?,
        /**
         * An ISO 8601 formatted date which represents the last time the profile was used.
         */
        val lastUsed: OffsetDateTime?,
        /**
         * A Base64-encoded image which represents the icon of the profile in the profiles menu.
         */
        val icon: String,
        /**
         * The version ID that the profile targets. Version IDs are determined in the version.json in every directory in ~/versions
         */
        val lastVersionId: String,
        /**
         * The directory that this profile should use to save its content.
         */
        val gameDir: String?,
        /**
         * The Java directory that the game will run on. This is by default the system's Java directory.
         */
        val javaDir: String?,
        /**
         * The start-up arguments for the profile. Those can have tangible experience in the game performance.
         */
        val javaArgs: String?,
        /**
         * The path to the logging configuration for the profile. This can be a XML file if the below setting is true
         */
        val logConfig: String?,
        /**
         * Whether the logging configuration is a XML file or not.
         */
        val logConfigIsXml: Boolean?,
        /**
         * The start-up resolution of the game window
         */
        val resolution: Resolution?,
    ) {
        /**
         * Resolution info is saved in profile resolution map
         */
        data class Resolution(
            /**
             * Height of the game window
             */
            val height: Int,
            /**
             * Width of the game window
             */
            val width: Int,
        )
    }
}

/**
 * [profileKey] 프로필만 만들거나 고친다.
 * lastVersionId 는 [versionId] 다. 로더 버전은 그 id 의 version JSON 이 가리킨다.
 * 다른 프로필은 건드리지 않는다.
 * 데이터 클래스로 다시 쓰면 settings, 계정, 모르는 프로필 필드가 빠진다.
 */
fun editRobotMcLauncherProfile(
    document: String,
    gameDirectory: Path,
    profileName: String,
    profileKey: String = DEFAULT_PROFILE_KEY,
    versionId: String = DEFAULT_VERSION_ID,
    profileIcon: String = DEFAULT_PROFILE_ICON,
    javaAgentJar: Path? = null,
    javaAgentOptions: Path? = null,
): String {
    val mapper = ObjectMapper()
    val root = mapper.readTree(document)
    if (root !is ObjectNode) {
        return document
    }
    val profilesNode = root.get("profiles")
    val profiles = when {
        profilesNode is ObjectNode -> profilesNode
        profilesNode == null || profilesNode.isNull -> root.putObject("profiles")
        else -> return document
    }
    val gameDir = gameDirectory.toAbsolutePath().normalize().toString()
    val key = pathSegment(profileKey, DEFAULT_PROFILE_KEY)
    val version = pathSegment(versionId, DEFAULT_VERSION_ID)
    val existing = profiles.get(key)
    val profile = if (existing is ObjectNode) existing else profiles.putObject(key)
    var changed = existing !is ObjectNode
    if (existing !is ObjectNode) {
        profile.put("type", "custom")
        profile.put("created", OffsetDateTime.now(ZoneOffset.UTC).toString())
    }
    if (profile.putTextIfDifferent("name", profileName)) {
        changed = true
    }
    if (profile.putTextIfDifferent("icon", profileIcon)) {
        changed = true
    }
    if (profile.putTextIfDifferent("lastVersionId", version)) {
        changed = true
    }
    if (profile.putTextIfDifferent("gameDir", gameDir)) {
        changed = true
    }
    if (javaAgentJar != null && javaAgentOptions != null) {
        val current = profile.get("javaArgs")
        val currentText = if (current != null && current.isTextual) current.asText() else null
        val merged = mergeJavaAgent(currentText, javaAgentJar, javaAgentOptions)
        if (profile.putTextIfDifferent("javaArgs", merged)) {
            changed = true
        }
    }
    if (!changed) {
        return document
    }
    return mapper.writeValueAsString(root)
}

/**
 * [profileKey] 항목만 뺀다. 그 키가 선택된 프로필이면 선택도 뺀다.
 * 다른 프로필과 최상위 필드는 둔다.
 */
fun removeLauncherProfile(document: String, profileKey: String): String {
    val mapper = ObjectMapper()
    val root = mapper.readTree(document)
    val profiles = root.get("profiles")
    if (root !is ObjectNode || profiles !is ObjectNode) {
        return document
    }
    val key = pathSegment(profileKey, DEFAULT_PROFILE_KEY)
    var changed = false
    if (profiles.has(key)) {
        profiles.remove(key)
        changed = true
    }
    val selected = root.get("selectedProfile")
    if (selected != null && selected.isTextual && selected.asText() == key) {
        root.remove("selectedProfile")
        changed = true
    }
    if (!changed) {
        return document
    }
    return mapper.writeValueAsString(root)
}

private fun ObjectNode.putTextIfDifferent(field: String, value: String): Boolean {
    val current = this.get(field)
    if (current != null && current.isTextual && current.asText() == value) {
        return false
    }
    this.put(field, value)
    return true
}
