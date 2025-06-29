package com.sysbot32.robotmc.installer.server

import com.sysbot32.robotmc.installer.config.InstallerProperties
import dev.dewy.nbt.Nbt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Path

private val log = KotlinLogging.logger { }

@Service
class ServerService(
    private val installerProperties: InstallerProperties,
) {
    init {
        this.getServers()
    }

    fun getServers(path: Path = installerProperties.minecraft.directory.resolve("servers.dat")): ServersDat {
        return ServersDat(Nbt().fromFile(path.toFile()).also { log.info { "$path: $it" } })
    }

    fun addServer(
        path: Path = installerProperties.minecraft.directory.resolve("servers.dat"),
        server: ServersDat.Server,
    ) {
        Nbt().toFile(this.getServers(path).run { copy(servers = servers + listOf(server)) }.toNbt(), path.toFile())
            .also { "$path: $it" }
    }
}
