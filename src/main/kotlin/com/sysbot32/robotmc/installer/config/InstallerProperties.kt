package com.sysbot32.robotmc.installer.config

import com.sysbot32.robotmc.installer.mod.loader.ModLoaderType
import com.sysbot32.robotmc.installer.server.ServersDat
import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.nio.file.Paths

@ConfigurationProperties(prefix = "installer")
data class InstallerProperties(
    val minecraft: Minecraft,
    val mod: Mod?,
    val servers: List<ServersDat.Server> = listOf(),
) {
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
        )
    }
}
