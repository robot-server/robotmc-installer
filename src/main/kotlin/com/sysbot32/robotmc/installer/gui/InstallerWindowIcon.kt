package com.sysbot32.robotmc.installer.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPainter
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
