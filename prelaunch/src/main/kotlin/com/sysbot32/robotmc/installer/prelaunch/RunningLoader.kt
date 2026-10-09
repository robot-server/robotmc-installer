package com.sysbot32.robotmc.installer.prelaunch

const val LAUNCHER_RESTART_DETAIL =
    "새로 설치한 내용이 아직 런처에 반영되지 않아 게임을 종료했어요.\n마인크래프트 런처를 완전히 종료한 뒤 다시 열고 플레이해 주세요."
const val LAUNCHER_RESTART_ARGUMENT = "--installer.launcher-restart=true"

private val NEOFORGE_JAR = Regex("""^neoforge-(.+)\.jar$""", RegexOption.IGNORE_CASE)
private val FABRIC_LOADER_JAR = Regex("""^fabric-loader-(.+)\.jar$""", RegexOption.IGNORE_CASE)

/**
 * 이번 게임 JVM 의 클래스패스에 올라온 로더다.
 * 런처가 버전 JSON 을 다시 읽지 않으면, 디스크에 설치된 로더와 달라진다.
 */
fun runningLoaderIds(classPath: String?, arguments: List<String> = emptyList()): List<String> {
    val ids = linkedSetOf<String>()
    if (!classPath.isNullOrBlank()) {
        for (entry in classPath.split(':', ';')) {
            val name = entry.substringAfterLast('/').substringAfterLast('\\')
            val neo = NEOFORGE_JAR.matchEntire(name)
            if (neo != null) {
                val version = neo.groupValues[1].removeSuffix("-universal")
                if (version.isNotEmpty()) {
                    ids += "neoforge-$version"
                }
                continue
            }
            val fabric = FABRIC_LOADER_JAR.matchEntire(name)
            if (fabric != null && fabric.groupValues[1].isNotEmpty()) {
                ids += "fabric-loader-${fabric.groupValues[1]}"
            }
        }
    }
    // 최근 NeoForge 는 universal JAR 을 클래스패스에 올리지 않고 게임 인자로 버전을 넘긴다.
    argumentValue(arguments, "--fml.neoForgeVersion")?.let { version ->
        if (version.isNotBlank()) {
            ids += "neoforge-$version"
        }
    }
    return ids.toList()
}

private fun argumentValue(arguments: List<String>, name: String): String? {
    arguments.forEachIndexed { index, argument ->
        if (argument == name && index + 1 < arguments.size) {
            return arguments[index + 1]
        }
        if (argument.startsWith("$name=")) {
            return argument.substring(name.length + 1)
        }
    }
    return null
}

/**
 * 클래스패스의 로더가 디스크에 설치된 로더와 다르면 true.
 * 클래스패스에서 로더를 못 찾으면 판단하지 않는다.
 */
fun loaderLaunchDiffers(runningLoaderIds: List<String>, installedLoaderId: String?): Boolean {
    if (installedLoaderId.isNullOrBlank() || runningLoaderIds.isEmpty()) {
        return false
    }
    return runningLoaderIds.none { sameLoader(it, installedLoaderId) }
}

private fun sameLoader(running: String, installed: String): Boolean {
    return running == installed || installed.startsWith("$running-")
}
