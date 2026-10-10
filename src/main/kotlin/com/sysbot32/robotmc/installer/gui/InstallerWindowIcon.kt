package com.sysbot32.robotmc.installer.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPainter
import java.awt.GraphicsEnvironment
import java.awt.Taskbar
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

const val INSTALLER_WINDOW_ICON_RESOURCE = "/installer-app-icon.png"

private object InstallerWindowIconResource

/** 앱 이미지 없이 `java -jar` 로 띄울 때도 쓰는 창 아이콘. */
fun installerWindowIconImage(): BufferedImage {
    val stream = InstallerWindowIconResource::class.java.getResourceAsStream(INSTALLER_WINDOW_ICON_RESOURCE)
        ?: error("설치기 창 아이콘이 없습니다: $INSTALLER_WINDOW_ICON_RESOURCE")
    return stream.use { input ->
        ImageIO.read(input) ?: error("설치기 창 아이콘을 읽지 못했습니다: $INSTALLER_WINDOW_ICON_RESOURCE")
    }
}

fun installerWindowIconPainter(): Painter = installerWindowIconImage().toPainter()

@Composable
fun installerWindowIcon(): Painter = remember { installerWindowIconPainter() }

/** macOS Dock 과 Windows 작업 표시줄은 이 기능을 제공한다. 없으면 창 아이콘만 쓴다. */
fun installerTaskbarIconSupported(): Boolean {
    if (GraphicsEnvironment.isHeadless()) {
        return false
    }
    if (!Taskbar.isTaskbarSupported()) {
        return false
    }
    return Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)
}

/**
 * 창의 icon 은 macOS Dock 을 바꾸지 않는다.
 * java -jar 는 앱 이미지가 없어서 Dock 이 Java 아이콘으로 남는다. 같은 그림을 여기 심는다.
 */
fun installInstallerTaskbarIcon(
    image: BufferedImage = installerWindowIconImage(),
    supported: Boolean = installerTaskbarIconSupported(),
    setIcon: (BufferedImage) -> Unit = ::setInstallerTaskbarIcon,
) {
    if (!supported) {
        return
    }
    setIcon(image)
}

fun setInstallerTaskbarIcon(image: BufferedImage) {
    Taskbar.getTaskbar().iconImage = image
}
