package com.sysbot32.robotmc.installer.launcher

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Path
import java.time.OffsetDateTime

const val ROBOTMC_PROFILE_NAME = "RobotMC"

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
 * [profileVersionId] 와 같은 lastVersionId 프로필의 보이는 이름과 gameDir 만 바꾼다.
 * 데이터 클래스로 다시 쓰면 settings, 계정, 모르는 프로필 필드가 빠진다.
 */
fun editRobotMcLauncherProfile(
    document: String,
    profileVersionId: String,
    gameDirectory: Path,
): String {
    val mapper = ObjectMapper()
    val root = mapper.readTree(document)
    val profiles = root.get("profiles")
    if (root !is ObjectNode || profiles !is ObjectNode) {
        return document
    }
    val gameDir = gameDirectory.toAbsolutePath().normalize().toString()
    var changed = false
    for (entry in profiles.properties()) {
        val profile = entry.value
        if (profile !is ObjectNode) {
            continue
        }
        val version = profile.get("lastVersionId")
        if (version == null || !version.isTextual || version.asText() != profileVersionId) {
            continue
        }
        val name = profile.get("name")
        val directory = profile.get("gameDir")
        val alreadyNamed = name != null && name.isTextual && name.asText() == ROBOTMC_PROFILE_NAME
        val alreadyPlaced = directory != null && directory.isTextual && directory.asText() == gameDir
        if (alreadyNamed && alreadyPlaced) {
            continue
        }
        profile.put("name", ROBOTMC_PROFILE_NAME)
        profile.put("gameDir", gameDir)
        changed = true
    }
    if (!changed) {
        return document
    }
    return mapper.writeValueAsString(root)
}
