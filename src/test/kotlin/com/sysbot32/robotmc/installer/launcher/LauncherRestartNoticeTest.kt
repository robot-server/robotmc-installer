package com.sysbot32.robotmc.installer.launcher

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
    }
}
