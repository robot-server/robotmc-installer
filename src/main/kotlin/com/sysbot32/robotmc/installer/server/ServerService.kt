package com.sysbot32.robotmc.installer.server

import com.sysbot32.robotmc.installer.InstallService
import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.progress.ProgressService
import dev.dewy.nbt.Nbt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Path
import kotlin.io.path.exists

private val log = KotlinLogging.logger { }

@Service
class ServerService(
    private val installerProperties: InstallerProperties,
    private val progressService: ProgressService,
) : InstallService {
    fun getServers(path: Path = installerProperties.minecraft.directory.resolve("servers.dat")): ServersDat {
        if (!path.exists()) {
            return ServersDat(
                servers = listOf(),
            )
        }
        return ServersDat(Nbt().fromFile(path.toFile()).also { log.info { "$path: $it" } })
    }

    fun addServer(
        path: Path = installerProperties.minecraft.directory.resolve("servers.dat"),
        server: ServersDat.Server,
    ) {
        Nbt().toFile(this.getServers(path).run {
            if (servers.none { it.ip == server.ip }) copy(servers = servers + listOf(server)) else this
        }.toNbt(), path.toFile())
            .also { log.info { "$path: $it" } }
    }

    fun addServers(servers: List<ServersDat.Server> = installerProperties.servers) {
        this.getServers().also { log.info { it } }
        for (server in servers) {
            this.addServer(server = server)
            this.progressService.step()
        }
    }

    override val order: Int
        get() = 30

    override fun install() = this.addServers()

    override fun uninstall() {
        this.progressService.step(-installerProperties.servers.size)
    }
}
