package com.sysbot32.robotmc.installer.launcher

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sysbot32.robotmc.installer.config.DEFAULT_VERSION_ID
import com.sysbot32.robotmc.installer.config.gameDirectory
import com.sysbot32.robotmc.installer.config.gameDirectoryNameOrThrow
import com.sysbot32.robotmc.installer.config.pathSegment
import com.sysbot32.robotmc.installer.exception.UserException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val versionTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

/**
 * 런처가 버전을 찾는 곳. gameDir 이 아니다.
 */
fun loaderVersionAliasPath(
    minecraftDirectory: Path,
    versionId: String = DEFAULT_VERSION_ID,
): Path {
    val id = pathSegment(versionId, DEFAULT_VERSION_ID)
    return minecraftDirectory.resolve("versions").resolve(id).resolve("$id.json")
}

fun readLoaderVersionAliasInherits(
    minecraftDirectory: Path,
    versionId: String = DEFAULT_VERSION_ID,
): String? {
    val path = loaderVersionAliasPath(minecraftDirectory, versionId)
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
 * id 는 [versionId] 다. 없는 time 만 채우고, 다른 필드는 둔다.
 */
fun editLoaderVersionAlias(
    document: String?,
    inheritsFrom: String,
    versionId: String = DEFAULT_VERSION_ID,
): String {
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
    val id = pathSegment(versionId, DEFAULT_VERSION_ID)
    if (root.putAliasText("id", id)) {
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

fun writeLoaderVersionAlias(
    minecraftDirectory: Path,
    inheritsFrom: String,
    versionId: String = DEFAULT_VERSION_ID,
) {
    val id = pathSegment(versionId, DEFAULT_VERSION_ID)
    if (id == inheritsFrom) {
        throw UserException("버전 id \"$id\" 는 로더 버전과 같아요.")
    }
    val path = loaderVersionAliasPath(minecraftDirectory, id)
    val current = if (Files.isRegularFile(path)) Files.readString(path) else null
    if (current != null && !isLoaderVersionAlias(current, id)) {
        throw UserException("버전 id \"$id\" 에는 이미 다른 버전 파일이 있어요.")
    }
    Files.createDirectories(path.parent)
    val edited = editLoaderVersionAlias(current, inheritsFrom, id)
    if (edited != current) {
        Files.writeString(path, edited)
    }
}

/**
 * 이 구성의 게임 폴더를 지운다. saves 는 남긴다.
 * 마인크래프트 디렉터리와 그 옆의 폴더는 건드리지 않는다.
 */
fun deleteGameDirectory(minecraftDirectory: Path, directoryName: String) {
    val name = gameDirectoryNameOrThrow(directoryName)
    val gameDir = gameDirectory(minecraftDirectory, name)
    val minecraft = minecraftDirectory.toAbsolutePath().normalize()
    val target = gameDir.toAbsolutePath().normalize()
    if (target.parent != minecraft) {
        return
    }
    if (Files.isSymbolicLink(target) || !Files.isDirectory(target)) {
        deleteTreeIfExists(target)
        return
    }
    Files.list(target).use { children ->
        children.forEach { child ->
            if (child.fileName?.toString() == "saves") {
                return@forEach
            }
            if (child.toAbsolutePath().normalize().parent != target) {
                return@forEach
            }
            deleteTreeIfExists(child)
        }
    }
    val saves = target.resolve("saves")
    if (!Files.exists(saves) && !Files.isSymbolicLink(saves)) {
        deleteTreeIfExists(target)
    }
}

/**
 * 이 설치가 쓴 별칭 폴더만 지운다.
 * [loaderVersionId] 와 같거나, JSON 이 그 로더를 가리키는 별칭이 아니면 원본 버전은 둔다.
 */
fun deleteVersionAlias(minecraftDirectory: Path, versionId: String, loaderVersionId: String) {
    val id = pathSegment(versionId, DEFAULT_VERSION_ID)
    if (id == loaderVersionId) {
        return
    }
    val json = loaderVersionAliasPath(minecraftDirectory, id)
    if (!Files.isRegularFile(json)) {
        return
    }
    val document = Files.readString(json)
    if (!isLoaderVersionAlias(document, id) || loaderVersionAliasInherits(document) != loaderVersionId) {
        return
    }
    val versionDir = json.parent ?: return
    val versions = minecraftDirectory.toAbsolutePath().normalize().resolve("versions")
    val target = versionDir.toAbsolutePath().normalize()
    if (target.parent != versions) {
        return
    }
    deleteTreeIfExists(target)
}

private fun isLoaderVersionAlias(document: String, versionId: String): Boolean {
    return try {
        val id = ObjectMapper().readTree(document).get("id")
        val inherits = loaderVersionAliasInherits(document)
        id != null && id.isTextual && id.asText() == versionId && !inherits.isNullOrBlank() && inherits != versionId
    } catch (exception: Exception) {
        false
    }
}

private fun deleteTreeIfExists(path: Path) {
    try {
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
            return
        }
        if (!Files.exists(path)) {
            return
        }
        if (Files.isDirectory(path)) {
            val directory = path.toAbsolutePath().normalize()
            Files.list(path).use { children ->
                children.forEach { child ->
                    if (child.toAbsolutePath().normalize().parent != directory) {
                        return@forEach
                    }
                    deleteTreeIfExists(child)
                }
            }
        }
        Files.deleteIfExists(path)
    } catch (exception: IOException) {
        throw UserException("삭제하지 못했어요: $path", exception)
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
