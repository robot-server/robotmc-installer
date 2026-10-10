package com.sysbot32.robotmc.installer

import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 릴리스 bootJar는 여섯 Skiko 타깃을 담고, 앱 이미지가 넣는 jar는 이 호스트의 타깃만 담는다.
 * desktop-jvm-* 좌표는 pom이라 jar에 파일이 없고, 네이티브는 skiko-awt-runtime-* 로 들어간다.
 */
class BootJarSkikoTest {

    @Test
    fun releaseBootJarContainsAllSixSkikoTargets() {
        val jar = File(System.getProperty("robotmc.boot.jar") ?: error("robotmc.boot.jar 가 없습니다."))
        val entries = skikoRuntimeEntries(jar)
        println("release skiko:\n${entries.sorted().joinToString("\n")}")
        assertEquals(SKIKO_TARGETS.sorted(), entries.map { it.target }.sorted())
        assertEquals(entries.size, entries.map { it.target }.toSet().size)
        assertEquals(bootJarMainClass, mainClass(jar))
    }

    @Test
    fun appImageBootJarKeepsOnlyTheHostSkiko() {
        val releaseJar = File(System.getProperty("robotmc.boot.jar") ?: error("robotmc.boot.jar 가 없습니다."))
        val hostJar = File(System.getProperty("robotmc.host.boot.jar") ?: error("robotmc.host.boot.jar 가 없습니다."))
        assertTrue(hostJar.isFile, hostJar.absolutePath)
        val host = currentOsTarget()
        val entries = skikoRuntimeEntries(hostJar)
        println("host skiko ($host):\n${entries.sorted().joinToString("\n")}")
        assertEquals(listOf(host), entries.map { it.target }.sorted())
        val absent = SKIKO_TARGETS - host
        assertTrue(absent.none { target -> entries.any { it.target == target } })
        assertEquals(bootJarMainClass, mainClass(hostJar))
        assertEquals(startClass(releaseJar), startClass(hostJar))
        assertNotEquals(sha256(releaseJar), sha256(hostJar))
        assertTrue(hostJar.length() < releaseJar.length())
    }
}

private const val bootJarMainClass = "org.springframework.boot.loader.launch.JarLauncher"

private val SKIKO_TARGETS = setOf(
    "linux-arm64",
    "linux-x64",
    "macos-arm64",
    "macos-x64",
    "windows-arm64",
    "windows-x64",
)

private val skikoRuntimeName = Regex("""skiko-awt-runtime-(linux|macos|windows)-(arm64|x64)-.+\.jar""")

private data class SkikoRuntimeEntry(
    val name: String,
    val target: String,
) : Comparable<SkikoRuntimeEntry> {
    override fun compareTo(other: SkikoRuntimeEntry): Int = name.compareTo(other.name)
}

private fun skikoRuntimeEntries(jar: File): List<SkikoRuntimeEntry> {
    return JarFile(jar).use { archive ->
        archive.entries().asSequence()
            .map { it.name }
            .filter { it.startsWith("BOOT-INF/lib/") && it.endsWith(".jar") }
            .map { it.substringAfterLast('/') }
            .mapNotNull { name ->
                val match = skikoRuntimeName.matchEntire(name) ?: return@mapNotNull null
                SkikoRuntimeEntry(name, "${match.groupValues[1]}-${match.groupValues[2]}")
            }
            .toList()
    }
}

/**
 * Compose `currentTarget`과 같다. Mac OS X, Win*, Linux* 와 aarch64/x86_64.
 * windows-latest(amd64)에서는 windows-x64, 이 맥에서는 macos-arm64가 된다.
 */
private fun currentOsTarget(): String {
    val os = System.getProperty("os.name")
    val osId = when {
        os.equals("Mac OS X", ignoreCase = true) -> "macos"
        os.startsWith("Win", ignoreCase = true) -> "windows"
        os.startsWith("Linux", ignoreCase = true) -> "linux"
        else -> error("Unknown OS name: $os")
    }
    val archId = when (val arch = System.getProperty("os.arch")) {
        "x86_64", "amd64" -> "x64"
        "aarch64" -> "arm64"
        else -> error("Unsupported OS arch: $arch")
    }
    return "$osId-$archId"
}

private fun mainClass(jar: File): String? {
    return JarFile(jar).use { it.manifest?.mainAttributes?.getValue("Main-Class") }
}

private fun startClass(jar: File): String? {
    return JarFile(jar).use { it.manifest?.mainAttributes?.getValue("Start-Class") }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
