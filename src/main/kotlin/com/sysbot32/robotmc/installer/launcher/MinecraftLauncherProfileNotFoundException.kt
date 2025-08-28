package com.sysbot32.robotmc.installer.launcher

import com.sysbot32.robotmc.installer.exception.UserException
import java.awt.Desktop
import java.net.URI

class MinecraftLauncherProfileNotFoundException(
    val downloadUrl: String,
) : UserException(
    message = """
        Minecraft 런처 프로필을 찾을 수 없어요.
        ${downloadUrl}에 방문하여 Minecraft 런처를 설치해 주세요.
        이미 설치되어 있다면 최초 한 번은 런처를 실행해 주세요.
    """.trimIndent(),
) {
    override fun afterThrow() {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI.create(this.downloadUrl))
        }
    }
}
