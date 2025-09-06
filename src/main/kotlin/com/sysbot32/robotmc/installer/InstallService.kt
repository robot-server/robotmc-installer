package com.sysbot32.robotmc.installer

interface InstallService {
    val order: Int
    fun install()
    fun uninstall()
}
