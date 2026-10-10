package com.sysbot32.robotmc.installer

import java.io.File

/**
 * jpackage `--icon` 에 넘길 파일.
 * macOS 는 icns, Windows 는 ico, Linux 는 png 다.
 * 세 파일은 같은 그림이고, 런처 프로필 아이콘(Redstone_Block)과는 별개다.
 */
object InstallerAppIcon {
    fun fileFor(os: String, root: File): File {
        val relative = when (os) {
            "mac" -> "src/main/icon/installer-app-icon.icns"
            "windows" -> "src/main/icon/installer-app-icon.ico"
            "linux" -> "src/main/resources/installer-app-icon.png"
            else -> throw IllegalArgumentException("지원하지 않는 OS입니다: $os")
        }
        return File(root, relative)
    }
}
