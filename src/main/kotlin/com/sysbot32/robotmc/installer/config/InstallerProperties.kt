package com.sysbot32.robotmc.installer.config

import com.sysbot32.robotmc.installer.mod.loader.ModLoaderType
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "installer")
data class InstallerProperties(
    val minecraft: Minecraft,
    val mod: Mod?,
) {
    data class Minecraft(
        val version: String,
    )

    data class Mod(
        val loader: Loader,
    ) {
        data class Loader(
            val type: ModLoaderType,
            val version: String?,
            val installOptions: List<String> = listOf(),
        )
    }
}
