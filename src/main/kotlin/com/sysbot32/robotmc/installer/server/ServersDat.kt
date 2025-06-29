package com.sysbot32.robotmc.installer.server

import dev.dewy.nbt.tags.collection.CompoundTag

/**
 * https://minecraft.fandom.com/wiki/Servers.dat_format
 */
data class ServersDat(
    /**
     * List of compound tags, one for each saved server.
     */
    val servers: List<Server>,
) {
    constructor(nbt: CompoundTag) : this(
        servers = nbt.getList<CompoundTag>("servers").map { Server(it) },
    )

    fun toNbt(): CompoundTag {
        return CompoundTag().apply {
            putList("servers", servers.map { it.toNbt() })
        }
    }

    /**
     * Information about the server.
     */
    data class Server(
        /**
         * The IP address of the server.
         */
        val ip: String,
        /**
         * The name of the server as defined by the player.
         */
        val name: String,
    ) {
        constructor(nbt: CompoundTag) : this(
            ip = nbt.getString("ip").value,
            name = nbt.getString("name").value,
        )

        fun toNbt(): CompoundTag {
            return CompoundTag().apply {
                putString("ip", ip)
                putString("name", this@Server.name)
            }
        }
    }
}
