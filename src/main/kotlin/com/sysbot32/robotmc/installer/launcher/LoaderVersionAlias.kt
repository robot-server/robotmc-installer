package com.sysbot32.robotmc.installer.launcher

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

const val ROBOTMC_VERSION_ID = "RobotMC"

private val versionTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

/**
 * 런처가 버전을 찾는 곳. gameDir 이 아니다.
 */
fun loaderVersionAliasPath(minecraftDirectory: Path): Path {
    return minecraftDirectory.resolve("versions").resolve(ROBOTMC_VERSION_ID).resolve("$ROBOTMC_VERSION_ID.json")
}

fun readLoaderVersionAliasInherits(minecraftDirectory: Path): String? {
    val path = loaderVersionAliasPath(minecraftDirectory)
    if (!Files.isRegularFile(path)) {
        return null
    }
    return try {
        loaderVersionAliasInherits(Files.readString(path))
    } catch (exception: Exception) {
        null
    }
}

fun loaderVersionAliasInherits(document: String): String? {
    val inherits = ObjectMapper().readTree(document).get("inheritsFrom") ?: return null
    if (!inherits.isTextual || inherits.asText().isBlank()) {
        return null
    }
    return inherits.asText()
}

/**
 * [inheritsFrom] 은 로더가 설치한 버전 id 다.
 * id 는 [ROBOTMC_VERSION_ID] 로 고정한다. 없는 time 만 채우고, 다른 필드는 둔다.
 */
fun editLoaderVersionAlias(document: String?, inheritsFrom: String): String {
    val mapper = ObjectMapper()
    val parsed = if (document.isNullOrBlank()) {
        null
    } else {
        try {
            mapper.readTree(document)
        } catch (exception: Exception) {
            null
        }
    }
    val root = if (parsed is ObjectNode) parsed else mapper.createObjectNode()
    var changed = parsed !is ObjectNode
    if (root.putAliasText("id", ROBOTMC_VERSION_ID)) {
        changed = true
    }
    if (root.putAliasText("inheritsFrom", inheritsFrom)) {
        changed = true
    }
    if (root.get("type") == null || !root.get("type").isTextual) {
        root.put("type", "release")
        changed = true
    }
    val now = OffsetDateTime.now(ZoneOffset.UTC).format(versionTime)
    if (root.get("time") == null || !root.get("time").isTextual) {
        root.put("time", now)
        changed = true
    }
    if (root.get("releaseTime") == null || !root.get("releaseTime").isTextual) {
        root.put("releaseTime", now)
        changed = true
    }
    if (!changed && document != null) {
        return document
    }
    return mapper.writeValueAsString(root)
}

fun writeLoaderVersionAlias(minecraftDirectory: Path, inheritsFrom: String) {
    val path = loaderVersionAliasPath(minecraftDirectory)
    Files.createDirectories(path.parent)
    val current = if (Files.isRegularFile(path)) Files.readString(path) else null
    val edited = editLoaderVersionAlias(current, inheritsFrom)
    if (edited != current) {
        Files.writeString(path, edited)
    }
}

private fun ObjectNode.putAliasText(field: String, value: String): Boolean {
    val current = this.get(field)
    if (current != null && current.isTextual && current.asText() == value) {
        return false
    }
    this.put(field, value)
    return true
}
