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
    }
}
