package com.sysbot32.robotmc.installer.launcher

import com.sysbot32.robotmc.installer.prelaunch.LAUNCHER_RESTART_DETAIL
import com.sysbot32.robotmc.installer.prelaunch.loaderLaunchDiffers
import com.sysbot32.robotmc.installer.prelaunch.runningLoaderIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LauncherRestartNoticeTest {
    @Test
    fun classpathLoaderIsComparedWithTheInstalledLoader() {
        val oldNeo = runningLoaderIds("/libs/neoforge-21.11.5-universal.jar")
        val currentNeo = runningLoaderIds("/libs/neoforge-21.11.6-beta-universal.jar")
        val fabric = runningLoaderIds("C:\\libraries\\fabric-loader-0.16.14.jar")

        assertEquals(listOf("neoforge-21.11.5"), oldNeo)
        assertEquals(listOf("neoforge-21.11.6-beta"), currentNeo)
        assertEquals(listOf("fabric-loader-0.16.14"), fabric)
        assertTrue(loaderLaunchDiffers(oldNeo, "neoforge-21.11.6-beta"))
        assertFalse(loaderLaunchDiffers(currentNeo, "neoforge-21.11.6-beta"))
        assertFalse(loaderLaunchDiffers(fabric, "fabric-loader-0.16.14-1.21.11"))
        assertFalse(loaderLaunchDiffers(emptyList(), "neoforge-21.11.6-beta"))
        val fromGameArgument = runningLoaderIds(
            "/libs/loader-7.0.10.jar",
            listOf("--fml.neoForgeVersion", "21.5.75", "--fml.mcVersion", "1.21.5"),
        )
        assertEquals(listOf("neoforge-21.5.75"), fromGameArgument)
        assertTrue(loaderLaunchDiffers(fromGameArgument, "neoforge-21.11.6-beta"))
        assertFalse(loaderLaunchDiffers(fromGameArgument, "neoforge-21.5.75"))
        assertTrue(LAUNCHER_RESTART_DETAIL.contains("게임을 종료했어요.\n마인크래프트 런처를 완전히 종료"))
        assertTrue(LAUNCHER_RESTART_DETAIL.contains("아직 런처에 반영되지 않아"))
        assertTrue(LAUNCHER_RESTART_DETAIL.contains("완전히 종료"))
        assertFalse(LAUNCHER_RESTART_DETAIL.contains("모드 로더"))
        assertTrue(LAUNCHER_RESTART_DETAIL.contains("플레이해 주세요"))
        assertFalse(LAUNCHER_RESTART_DETAIL.contains("플레이 버튼"))
        assertFalse(LAUNCHER_RESTART_DETAIL.contains("RobotMC"))
    }
}
