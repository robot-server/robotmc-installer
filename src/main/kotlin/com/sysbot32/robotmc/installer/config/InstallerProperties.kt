package com.sysbot32.robotmc.installer.config

import com.sysbot32.robotmc.installer.mod.loader.ModLoaderType
import com.sysbot32.robotmc.installer.server.ServersDat
import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.nio.file.Paths

const val DEFAULT_PROFILE_NAME = "RobotMC"
const val DEFAULT_PROFILE_KEY = "robotmc"
const val DEFAULT_VERSION_ID = "RobotMC"
const val DEFAULT_GAME_DIRECTORY_NAME = "robotmc"

@ConfigurationProperties(prefix = "installer")
data class InstallerProperties(
    val mode: Mode = Mode.INSTALL,
    val minecraft: Minecraft,
    val mod: Mod?,
    val servers: List<ServersDat.Server> = listOf(),
    val resourcePacks: List<ResourcePack> = listOf(),
    val update: Update = Update(),
    /**
     * 런처에 보이는 프로필 이름. application.yml 의 profile-name.
     */
    val profileName: String = DEFAULT_PROFILE_NAME,
    /** launcher_profiles.json 의 프로필 키. application.yml 의 profile-key. */
    val profileKey: String = DEFAULT_PROFILE_KEY,
    /** lastVersionId 와 versions 폴더 이름. application.yml 의 version-id. */
    val versionId: String = DEFAULT_VERSION_ID,
    /** 마인크래프트 디렉터리 아래 게임 폴더 이름. application.yml 의 game-directory-name. */
    val gameDirectoryName: String = DEFAULT_GAME_DIRECTORY_NAME,
) {
    enum class Mode {
        INSTALL,
        UNINSTALL,
    }

    data class Minecraft(
        val version: String,
        val directory: Path = System.getProperty("os.name").lowercase().run {
            return@run when {
                // Windows
                contains("win") -> Paths.get(System.getenv("APPDATA")).resolve(".minecraft")
                // macOS
                contains("mac") -> Paths.get(System.getProperty("user.home", "."))
                    .resolve("Library").resolve("Application Support").resolve("minecraft")
                // Linux
                else -> Paths.get(System.getProperty("user.home", ".")).resolve(".minecraft")
            }
        },
        val downloadUrl: String = "https://www.minecraft.net/ko-kr/download",
    )

    data class Mod(
        val loader: Loader,
        val mods: List<Mod> = listOf(),
    ) {
        data class Loader(
            val type: ModLoaderType,
            val version: String?,
            val installOptions: List<String> = listOf(),
        )

        data class Mod(
            val downloadUrl: String,
            val required: Boolean = true,
        )
    }

    data class ResourcePack(
        val downloadUrl: String,
    )

    data class Update(
        val manifestUrl: String = "",
        val source: String = "",
        /**
         * 원격 구성이 설치기 교체 정보를 실어 보내는 자리.
         * 값은 읽어 두기만 하고 JAR는 바꾸지 않는다.
         */
        val app: App = App(),
    ) {
        data class App(
            val version: String = "",
            val url: String = "",
            val sha256: String = "",
        )
    }
}

/**
 * 모드, 리소스 팩, 세이브, servers.dat 를 두는 게임 폴더.
 * 버전과 프로필 목록이 있는 [minecraftDirectory] 와 다르고, 그 경로만으로 정해진다.
 */
fun gameDirectory(
    minecraftDirectory: Path,
    directoryName: String = DEFAULT_GAME_DIRECTORY_NAME,
): Path {
    return minecraftDirectory.toAbsolutePath().normalize()
        .resolve(pathSegment(directoryName, DEFAULT_GAME_DIRECTORY_NAME))
}

fun InstallerProperties.gameDirectory(): Path {
    return gameDirectory(this.minecraft.directory, this.gameDirectoryName)
}

/**
 * 구성의 프로필 이름. 공백만 있으면 [DEFAULT_PROFILE_NAME].
 */
fun InstallerProperties.profileDisplayName(): String {
    return this.profileName.trim().ifBlank { DEFAULT_PROFILE_NAME }
}

/** 구성의 프로필 키. 비어 있거나 경로로 쓰면 [DEFAULT_PROFILE_KEY]. */
fun InstallerProperties.launcherProfileKey(): String {
    return pathSegment(this.profileKey, DEFAULT_PROFILE_KEY)
}

/** 구성의 버전 id. 비어 있거나 경로로 쓰면 [DEFAULT_VERSION_ID]. */
fun InstallerProperties.launcherVersionId(): String {
    return pathSegment(this.versionId, DEFAULT_VERSION_ID)
}

/**
 * 한 경로 조각만 허용한다. `/`, `\`, `.`, `..` 는 [fallback].
 */
fun pathSegment(value: String, fallback: String): String {
    val name = value.trim()
    val invalid = name.isEmpty() ||
        name == "." ||
        name == ".." ||
        name.contains('/') ||
        name.contains('\\') ||
        name.contains('\u0000')
    return if (invalid) fallback else name
}

/**
 * version, url, sha256 이 모두 있을 때만 설치기 교체 후보로 본다.
 */
fun InstallerProperties.pendingAppUpdate(): InstallerProperties.Update.App? {
    val app = this.update.app
    if (app.version.isBlank() || app.url.isBlank() || app.sha256.isBlank()) {
        return null
    }
    return app
}
