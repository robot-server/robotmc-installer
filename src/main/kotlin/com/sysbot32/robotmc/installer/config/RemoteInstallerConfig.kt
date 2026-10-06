package com.sysbot32.robotmc.installer.config

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ByteArrayResource
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

private val log = KotlinLogging.logger { }

private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

/**
 * 시작 시 원격 YAML로 installer 구성을 덮어쓴다.
 * [InstallerProperties.Update.manifestUrl] 은 이 JAR의 값만 쓰고, 원격 값으로 바꾸지 않는다.
 * [InstallerProperties.Update.App] 은 나중 설치기 교체용으로 실어 나르기만 한다.
 */
class RemoteInstallerConfig(
    private val manifestUrl: String,
    private val bundledYaml: String,
    private val cacheFile: Path,
    private val fetch: (URI) -> String = ::fetchHttps,
) {
    fun resolve(): Resolved? {
        val url = this.manifestUrl.trim()
        if (url.isEmpty()) {
            return null
        }
        val uri = try {
            URI(url)
        } catch (exception: Exception) {
            log.warn(exception) { "Manifest URL is invalid" }
            return this.validCache()
        }
        if (!isHttps(uri)) {
            log.warn { "Manifest URL must use https" }
            return this.validCache()
        }
        return try {
            val merged = mergeOverBundle(this.bundledYaml, this.fetch(uri), url, SOURCE_ONLINE)
            bindInstaller(merged)
            writeAtomically(this.cacheFile, merged)
            Resolved(this.cacheFile, SOURCE_ONLINE)
        } catch (exception: Exception) {
            log.warn(exception) { "Remote config was not applied" }
            this.validCache()
        }
    }

    private fun validCache(): Resolved? {
        if (!Files.isRegularFile(this.cacheFile)) {
            return null
        }
        return try {
            bindInstaller(Files.readString(this.cacheFile))
            Resolved(this.cacheFile, SOURCE_CACHE)
        } catch (exception: Exception) {
            log.warn(exception) { "Ignoring unreadable manifest cache" }
            null
        }
    }

    data class Resolved(
        val location: Path,
        val source: String,
    ) {
        fun arguments(): Array<String> {
            return arrayOf(
                "--spring.config.location=optional:${this.location.toUri()}",
                "--installer.update.source=${this.source}",
            )
        }
    }

    companion object {
        const val SOURCE_ONLINE = "online"
        const val SOURCE_CACHE = "cache"
        const val MAX_MANIFEST_BYTES = 256 * 1024
        private const val MAX_REDIRECTS = 3

        fun fromBundled(
            cacheFile: Path = defaultCacheFile(),
            fetch: (URI) -> String = ::fetchHttps,
        ): RemoteInstallerConfig {
            val bundled = readBundledYaml()
            return RemoteInstallerConfig(
                manifestUrl = manifestUrl(bundled),
                bundledYaml = bundled,
                cacheFile = cacheFile,
                fetch = fetch,
            )
        }

        fun defaultCacheFile(): Path {
            return Path.of(System.getProperty("user.home"), ".robotmc-installer", "manifest.yml")
        }

        internal fun fetchHttps(uri: URI): String {
            if (!isHttps(uri)) {
                throw IllegalArgumentException("Manifest URL must use https")
            }
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
            var current = uri
            repeat(MAX_REDIRECTS + 1) {
                val request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/yaml, text/yaml, text/plain, */*")
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                response.body().use { body ->
                    when (response.statusCode()) {
                        in 200..299 -> return readLimited(body, MAX_MANIFEST_BYTES)
                        in 300..399 -> {
                            val location = response.headers().firstValue("location")
                                .orElseThrow { IllegalStateException("Redirect is missing location") }
                            current = httpsRedirect(current, location)
                        }
                        else -> throw IllegalStateException("Manifest request failed: ${response.statusCode()}")
                    }
                }
            }
            throw IllegalStateException("Manifest redirected too many times")
        }
    }
}

internal fun readBundledYaml(): String {
    val stream = RemoteInstallerConfig::class.java.classLoader.getResourceAsStream("application.yml")
        ?: throw IllegalStateException("application.yml is missing")
    return stream.use { readLimited(it, RemoteInstallerConfig.MAX_MANIFEST_BYTES) }
}

internal fun manifestUrl(bundledYaml: String): String {
    val installer = loadMap(bundledYaml)["installer"] as? Map<*, *> ?: return ""
    val update = installer["update"] as? Map<*, *> ?: return ""
    return update["manifest-url"]?.toString()?.trim().orEmpty()
}

/**
 * 원격 문서의 installer 가 번들 installer 를 통째로 대체한다.
 * manifest-url 과 source 는 호출하는 쪽이 정한 값만 남긴다.
 */
internal fun mergeOverBundle(
    bundledYaml: String,
    remoteYaml: String,
    manifestUrl: String,
    source: String,
): String {
    val root = linkedMap(loadMap(bundledYaml))
    val remoteRoot = loadMap(remoteYaml)
    val remoteInstaller = remoteRoot["installer"] as? Map<*, *>
        ?: throw IllegalArgumentException("Manifest is missing installer")
    val installer = linkedMap(remoteInstaller)
    val minecraft = installer["minecraft"] as? Map<*, *>
        ?: throw IllegalArgumentException("Manifest is missing installer.minecraft")
    if (minecraft["version"]?.toString()?.trim().isNullOrEmpty()) {
        throw IllegalArgumentException("Manifest is missing installer.minecraft.version")
    }
    val remoteUpdate = installer["update"] as? Map<*, *>
    val update = LinkedHashMap<String, Any?>()
    update["manifest-url"] = manifestUrl
    update["source"] = source
    if (remoteUpdate != null) {
        appFields(remoteUpdate)?.let { update["app"] = it }
    }
    installer["update"] = update
    root["installer"] = installer
    return yaml().dump(root)
}

internal fun bindInstaller(yamlText: String): InstallerProperties {
    val sources = YamlPropertySourceLoader().load(
        "manifest",
        ByteArrayResource(yamlText.encodeToByteArray()),
    )
    val environment = StandardEnvironment()
    sources.forEach { environment.propertySources.addFirst(it) }
    return Binder.get(environment)
        .bind("installer", Bindable.of(InstallerProperties::class.java))
        .orElseThrow { IllegalArgumentException("Manifest could not be bound") }
}

internal fun httpsRedirect(current: URI, location: String): URI {
    val next = current.resolve(location.trim())
    if (!isHttps(next)) {
        throw IllegalArgumentException("Redirect left https")
    }
    return next
}

internal fun readLimited(input: InputStream, maxBytes: Int): String {
    val bytes = input.readNBytes(maxBytes + 1)
    if (bytes.size > maxBytes) {
        throw IllegalArgumentException("Manifest is too large")
    }
    return bytes.decodeToString().removePrefix("\uFEFF")
}

private fun isHttps(uri: URI): Boolean {
    return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}

private fun writeAtomically(file: Path, text: String) {
    Files.createDirectories(file.parent)
    val temporary = file.resolveSibling("${file.fileName}.tmp")
    Files.writeString(temporary, text)
    try {
        Files.move(
            temporary,
            file,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun loadMap(yamlText: String): LinkedHashMap<String, Any?> {
    val loaded = yaml().load<Any?>(yamlText)
        ?: throw IllegalArgumentException("Manifest is empty")
    if (loaded !is Map<*, *>) {
        throw IllegalArgumentException("Manifest must be a map")
    }
    return linkedMap(loaded)
}

private fun linkedMap(source: Map<*, *>): LinkedHashMap<String, Any?> {
    val copy = LinkedHashMap<String, Any?>()
    source.forEach { (key, value) ->
        if (key is String) {
            copy[key] = value
        }
    }
    return copy
}

private fun appFields(update: Map<*, *>): LinkedHashMap<String, String>? {
    val app = update["app"] as? Map<*, *> ?: return null
    val kept = LinkedHashMap<String, String>()
    for (key in listOf("version", "url", "sha256")) {
        val value = app[key]?.toString()?.trim().orEmpty()
        if (value.isEmpty() || !isAppField(key, value)) {
            continue
        }
        kept[key] = value
    }
    return kept.takeIf { it.isNotEmpty() }
}

private fun isAppField(key: String, value: String): Boolean {
    return when (key) {
        "url" -> value.startsWith("https://")
        "sha256" -> value.matches(SHA256)
        else -> true
    }
}

private fun yaml(): Yaml {
    val loader = LoaderOptions()
    loader.maxAliasesForCollections = 10
    loader.codePointLimit = RemoteInstallerConfig.MAX_MANIFEST_BYTES
    loader.nestingDepthLimit = 30
    val dumper = DumperOptions()
    dumper.width = 10000
    dumper.setSplitLines(false)
    dumper.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
    dumper.setAllowUnicode(true)
    return Yaml(loader, dumper)
}
