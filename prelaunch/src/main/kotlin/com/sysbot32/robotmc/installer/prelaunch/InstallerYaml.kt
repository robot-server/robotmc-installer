package com.sysbot32.robotmc.installer.prelaunch

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.util.Locale

/**
 * application.yml 의 installer.mod 만 읽는다.
 * 로더 id 형식은 설치기가 프로필에 쓰는 neoforge- 와 fabric-loader- 와 같다.
 */
/** 설치기가 모드 파일로 쓰는 이름과 같다. 쿼리도 이름에 남긴다. */
fun downloadFileName(url: String?): String {
    if (url.isNullOrEmpty()) {
        return ""
    }
    val slash = url.lastIndexOf('/')
    return if (slash >= 0) url.substring(slash + 1) else url
}

internal object InstallerYaml {
    data class Parsed(
        val loaderId: String?,
        val modJarNames: Set<String>,
    )

    fun parse(yamlText: String): Parsed {
        val options = LoaderOptions()
        options.maxAliasesForCollections = 10
        options.codePointLimit = 256 * 1024
        options.nestingDepthLimit = 30
        val loaded = Yaml(options).load<Any?>(yamlText)
        val root = loaded as? Map<*, *> ?: throw IllegalArgumentException("Manifest must be a map")
        val installer = map(root["installer"])
        val minecraft = map(installer["minecraft"])
        val mod = map(installer["mod"])
        val loader = map(mod["loader"])
        val minecraftVersion = text(minecraft["version"])
        val loaderVersion = text(loader["version"])
        val loaderId = remoteLoaderId(text(loader["type"]), loaderVersion, minecraftVersion)
        val jars = linkedSetOf<String>()
        val mods = mod["mods"]
        if (mods is List<*>) {
            for (item in mods) {
                val entry = item as? Map<*, *> ?: continue
                val url = text(entry["download-url"]).ifEmpty { text(entry["downloadUrl"]) }
                val name = downloadFileName(url)
                if (name.isNotEmpty()) {
                    jars += name
                }
            }
        }
        return Parsed(loaderId, jars)
    }

    /**
     * NeoForge 는 neoforge- 에 로더 버전을 붙인다.
     * Fabric 은 fabric-loader- 에 로더 버전과 마인크래프트 버전을 붙인다.
     */
    fun remoteLoaderId(type: String?, loaderVersion: String?, minecraftVersion: String?): String? {
        if (loaderVersion.isNullOrBlank() || minecraftVersion.isNullOrBlank()) {
            return null
        }
        val normalized = type?.lowercase(Locale.ROOT)?.replace("-", "")?.replace("_", "").orEmpty()
        return when (normalized) {
            "neoforge" -> "neoforge-$loaderVersion"
            "fabric" -> "fabric-loader-$loaderVersion-$minecraftVersion"
            else -> null
        }
    }



    private fun map(value: Any?): Map<*, *> {
        return value as? Map<*, *> ?: throw IllegalArgumentException("Manifest is missing a map")
    }

    private fun text(value: Any?): String {
        return value?.toString()?.trim().orEmpty()
    }
}
