package com.sysbot32.robotmc.installer.config

import com.sysbot32.robotmc.installer.gui.applicationVersion
import com.sysbot32.robotmc.installer.gui.installerUpdateNotice
import com.sysbot32.robotmc.installer.gui.settingsRows
import com.sysbot32.robotmc.installer.startupArguments
import com.sysbot32.robotmc.installer.mod.loader.ModLoaderType
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.yaml.snakeyaml.Yaml
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteInstallerConfigTest {
    @Test
    fun profileNameUsesTheConfiguredValueAndFallsBackWhenBlank() {
        assertEquals(
            "Pack From Config",
            bindInstaller(
                """
                installer:
                  profile-name: "  Pack From Config  "
                  minecraft:
                    version: "1.21.11"
                """.trimIndent(),
            ).profileDisplayName(),
        )
        assertEquals(
            DEFAULT_PROFILE_NAME,
            bindInstaller(
                """
                installer:
                  profile-name: " "
                  minecraft:
                    version: "1.21.11"
                """.trimIndent(),
            ).profileDisplayName(),
        )
        assertEquals(
            DEFAULT_PROFILE_NAME,
            bindInstaller(
                """
                installer:
                  minecraft:
                    version: "1.21.11"
                """.trimIndent(),
            ).profileDisplayName(),
        )
        val bundled = readBundledYaml()
        val installer = (Yaml().load(bundled) as Map<*, *>)["installer"] as Map<*, *>
        val bound = bindInstaller(bundled)
        assertEquals(installer["profile-name"].toString(), bound.profileName)
        assertEquals(DEFAULT_PROFILE_NAME, bound.profileDisplayName())
        assertEquals(installer["profile-key"].toString(), bound.profileKey)
        assertEquals(DEFAULT_PROFILE_KEY, bound.launcherProfileKey())
        assertEquals(installer["version-id"].toString(), bound.versionId)
        assertEquals(DEFAULT_VERSION_ID, bound.launcherVersionId())
        assertEquals(installer["game-directory-name"].toString(), bound.gameDirectoryName)
        assertEquals(DEFAULT_GAME_DIRECTORY_NAME, bound.gameDirectory().fileName.toString())
        assertEquals(installer["profile-icon"].toString(), bound.profileIcon)
        assertEquals(DEFAULT_PROFILE_ICON, bound.profileDisplayIcon())
        val custom = bindInstaller(
            """
            installer:
              profile-name: My Pack
              profile-key: mypack
              version-id: "../Nope"
              game-directory-name: " "
              profile-icon: "  "
              minecraft:
                version: "1.21.11"
            """.trimIndent(),
        )
        assertEquals("My Pack", custom.profileDisplayName())
        assertEquals("mypack", custom.launcherProfileKey())
        assertEquals(DEFAULT_VERSION_ID, custom.launcherVersionId())
        assertEquals(DEFAULT_GAME_DIRECTORY_NAME, custom.gameDirectory().fileName.toString())
        assertEquals(DEFAULT_PROFILE_ICON, custom.profileDisplayIcon())
    }

    @Test
    fun bundledManifestPointsAtMainApplicationYml() {
        assertEquals(MAIN_MANIFEST, manifestUrl(readBundledYaml()))
    }

    @Test
    fun startupResolveReplacesTheInstallerSectionAndDoesNotFetchTheAppJar() {
        val directory = Files.createTempDirectory("remote-config")
        val cache = directory.resolve("manifest.yml")
        val seen = mutableListOf<URI>()
        val first = RemoteInstallerConfig.fromBundled(cache) { uri ->
            seen += uri
            REMOTE
        }.resolve()
        val second = RemoteInstallerConfig.fromBundled(cache) { uri ->
            seen += uri
            REMOTE
        }.resolve()

        assertEquals(listOf(URI(MAIN_MANIFEST), URI(MAIN_MANIFEST)), seen)
        assertEquals(RemoteInstallerConfig.SOURCE_ONLINE, first?.source)
        assertEquals(RemoteInstallerConfig.SOURCE_ONLINE, second?.source)
        assertEquals(emptyList(), jarsUnder(directory))
        val bundled = bindInstaller(readBundledYaml())
        withStartup(first!!) { context ->
            val properties = context.getBean(InstallerProperties::class.java)
            assertEquals("1.21.12", properties.minecraft.version)
            assertEquals("Remote Pack", properties.profileDisplayName())
            assertEquals(ModLoaderType.FABRIC, properties.mod?.loader?.type)
            assertEquals("0.16.0", properties.mod?.loader?.version)
            assertEquals(listOf("https://cdn.modrinth.com/data/only/one.jar"), properties.mod?.mods?.map { it.downloadUrl })
            assertNotEquals(bundled.mod?.mods?.map { it.downloadUrl }, properties.mod?.mods?.map { it.downloadUrl })
            assertTrue(bundled.servers.isNotEmpty())
            assertEquals(emptyList(), properties.servers)
            assertTrue(bundled.resourcePacks.isNotEmpty())
            assertEquals(emptyList(), properties.resourcePacks)
            assertEquals(MAIN_MANIFEST, properties.update.manifestUrl)
            assertEquals("1.2.0", properties.pendingAppUpdate()?.version)
            assertEquals("https://example.com/robotmc-installer.jar", properties.pendingAppUpdate()?.url)
            assertEquals("온라인", properties.settingsRows().first { it.label == "구성" }.value)
            assertEquals("none", context.environment.getProperty("spring.main.web-application-type"))
            assertNotEquals("off", context.environment.getProperty("spring.main.banner-mode"))
        }
        val text = Files.readString(cache)
        assertFalse(text.contains("banner-mode"))
        assertFalse(text.contains("evil.example"))
    }

    @Test
    fun mainShapedDocumentWithoutUpdateReplacesBundledLists() {
        val directory = Files.createTempDirectory("remote-config")
        val cache = directory.resolve("manifest.yml")
        val resolved = RemoteInstallerConfig.fromBundled(cache) { _ -> MAIN_SHAPE }.resolve()
        val bundled = bindInstaller(readBundledYaml())

        withStartup(resolved!!) { context ->
            val properties = context.getBean(InstallerProperties::class.java)
            assertEquals("1.21.11", properties.minecraft.version)
            assertEquals("21.11.7-beta", properties.mod?.loader?.version)
            assertEquals(listOf("https://example.com/from-main-shape.jar"), properties.mod?.mods?.map { it.downloadUrl })
            assertNotEquals(bundled.mod?.mods?.map { it.downloadUrl }, properties.mod?.mods?.map { it.downloadUrl })
            assertEquals(listOf("Other Server"), properties.servers.map { it.name })
            assertNotEquals(bundled.servers.map { it.name }, properties.servers.map { it.name })
            assertEquals(listOf("https://example.com/pack-from-main.zip"), properties.resourcePacks.map { it.downloadUrl })
            assertEquals(MAIN_MANIFEST, properties.update.manifestUrl)
            assertNull(properties.pendingAppUpdate())
            assertEquals("none", context.environment.getProperty("spring.main.web-application-type"))
        }
    }

    @Test
    fun incompleteAppFieldsAreNotAnUpdateCandidate() {
        val directory = Files.createTempDirectory("remote-config")
        val seen = mutableListOf<URI>()
        val resolved = RemoteInstallerConfig.fromBundled(directory.resolve("manifest.yml")) { uri ->
            seen += uri
            """
            installer:
              minecraft:
                version: "1.21.12"
              update:
                app:
                  version: "1.2.0"
                  url: http://example.com/robotmc-installer.jar
                  sha256: abc
            """.trimIndent()
        }.resolve()
        val properties = bindInstaller(Files.readString(resolved!!.location))

        assertEquals(listOf(URI(MAIN_MANIFEST)), seen)
        assertNull(properties.pendingAppUpdate())
        assertEquals("", properties.update.app.url)
        assertEquals("", properties.update.app.sha256)
        assertEquals("1.2.0", properties.update.app.version)
        assertEquals(emptyList(), jarsUnder(directory))
    }

    @Test
    fun blankUrlIgnoresTheCache() {
        val directory = Files.createTempDirectory("remote-config")
        val cache = directory.resolve("manifest.yml")
        Files.writeString(cache, "kept")
        val resolved = RemoteInstallerConfig(
            manifestUrl = "  ",
            bundledYaml = readBundledYaml(),
            cacheFile = cache,
            fetch = { _ -> error("fetch should not run") },
        ).resolve()

        assertNull(resolved)
        assertEquals("kept", Files.readString(cache))
    }

    @Test
    fun httpUrlDoesNotFetchAndUsesAValidCache() {
        val directory = Files.createTempDirectory("remote-config")
        val cache = directory.resolve("manifest.yml")
        RemoteInstallerConfig.fromBundled(cache) { _ -> REMOTE }.resolve()
        val before = Files.readString(cache)
        val resolved = config(cache, "http://example.com/installer.yml") { _ -> error("fetch should not run") }.resolve()

        assertEquals(RemoteInstallerConfig.SOURCE_CACHE, resolved?.source)
        assertEquals(before, Files.readString(cache))
        assertEquals("1.21.12", bindInstaller(before).minecraft.version)
    }

    @Test
    fun failedFetchKeepsThePreviousCacheForTheNextLaunch() {
        val directory = Files.createTempDirectory("remote-config")
        val cache = directory.resolve("manifest.yml")
        RemoteInstallerConfig.fromBundled(cache) { _ -> REMOTE }.resolve()
        val before = Files.readString(cache)
        val resolved = RemoteInstallerConfig.fromBundled(cache) { _ ->
            throw IllegalStateException("offline")
        }.resolve()

        assertEquals(RemoteInstallerConfig.SOURCE_CACHE, resolved?.source)
        assertEquals(before, Files.readString(cache))
        withStartup(resolved!!) { context ->
            val properties = context.getBean(InstallerProperties::class.java)
            assertEquals(listOf("https://cdn.modrinth.com/data/only/one.jar"), properties.mod?.mods?.map { it.downloadUrl })
            assertEquals("저장된 온라인 구성", properties.settingsRows().first { it.label == "구성" }.value)
        }
    }

    @Test
    fun invalidManifestDoesNotReplaceTheCache() {
        val directory = Files.createTempDirectory("remote-config")
        val cache = directory.resolve("manifest.yml")
        RemoteInstallerConfig.fromBundled(cache) { _ -> REMOTE }.resolve()
        val before = Files.readString(cache)
        val unresolved = RemoteInstallerConfig.fromBundled(cache) { _ ->
            "installer: { minecraft: { } }"
        }.resolve()
        val unbound = RemoteInstallerConfig.fromBundled(cache) { _ ->
            UNBINDABLE
        }.resolve()

        assertEquals(RemoteInstallerConfig.SOURCE_CACHE, unresolved?.source)
        assertEquals(RemoteInstallerConfig.SOURCE_CACHE, unbound?.source)
        assertEquals(before, Files.readString(cache))
    }

    @Test
    fun missingCacheAfterFailureUsesTheBundledConfig() {
        val directory = Files.createTempDirectory("remote-config")
        val resolved = config(directory.resolve("manifest.yml"), "https://example.com/installer.yml") { _ ->
            throw IllegalStateException("offline")
        }.resolve()

        assertNull(resolved)
        assertEquals("설치 파일", bindInstaller(readBundledYaml()).settingsRows().first { it.label == "구성" }.value)
    }

    @Test
    fun configArgumentsPointSpringAtTheCache() {
        val location = Files.createTempDirectory("remote-config").resolve("manifest.yml")
        val arguments = RemoteInstallerConfig.Resolved(location, RemoteInstallerConfig.SOURCE_ONLINE).arguments()

        assertEquals(
            arrayOf(
                "--spring.config.location=optional:${location.toUri()}",
                "--installer.update.source=online",
            ).toList(),
            arguments.toList(),
        )
        val resolved = RemoteInstallerConfig.Resolved(location, RemoteInstallerConfig.SOURCE_ONLINE)
        assertEquals(listOf("--nogui") + arguments.toList(), startupArguments(arrayOf("--nogui"), resolved).toList())
        assertEquals(listOf("--nogui"), startupArguments(arrayOf("--nogui"), null).toList())
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ROBOTMC_LIVE_OUT", matches = ".+")
    fun liveResolveTwiceMatchesTheFetchedInstallerSection() {
        val report = Path.of(System.getenv("ROBOTMC_LIVE_OUT"))
        val directory = Files.createTempDirectory("live-resolve")
        val lines = mutableListOf<String>()
        try {
            repeat(2) { index ->
                var body = ""
                var failure: Exception? = null
                val resolved = RemoteInstallerConfig.fromBundled(directory.resolve("run-$index.yml")) { uri ->
                    try {
                        RemoteInstallerConfig.fetchHttps(uri).also { body = it }
                    } catch (exception: Exception) {
                        failure = exception
                        throw exception
                    }
                }.resolve()
                val error = failure
                if (error != null) {
                    lines += "run ${index + 1}: network failure: ${error::class.java.name}: ${error.message}"
                    return@repeat
                }
                check(resolved != null) { "run ${index + 1} did not resolve" }
                check(resolved.source == RemoteInstallerConfig.SOURCE_ONLINE) {
                    "run ${index + 1} source was ${resolved.source}"
                }
                val bound = bindInstaller(Files.readString(resolved.location))
                val section = fetchedInstaller(body)
                val fetchedVersion = (section["minecraft"] as Map<*, *>)["version"].toString()
                val fetchedMods = downloadUrls(section["mod"] as Map<*, *>, "mods")
                val boundMods = bound.mod?.mods?.map { it.downloadUrl }.orEmpty()
                lines += "run ${index + 1}: source=${resolved.source} version=$fetchedVersion mods=$fetchedMods"
                check(bound.minecraft.version == fetchedVersion) {
                    "run ${index + 1} version ${bound.minecraft.version} != $fetchedVersion"
                }
                check(boundMods == fetchedMods) {
                    "run ${index + 1} mods $boundMods != $fetchedMods"
                }
            }
        } catch (exception: Exception) {
            lines += "error: ${exception::class.java.name}: ${exception.message}"
            Files.writeString(report, lines.joinToString("\n"))
            throw exception
        }
        Files.writeString(report, lines.joinToString("\n"))
        if (lines.any { it.contains("network failure:") }) {
            return
        }
        assertEquals(2, lines.count { it.contains("source=online") })
    }

    @Test
    fun packagedVersionIsTheGradleProjectVersion() {
        assertEquals("1.0.0", applicationVersion())
        assertNull(applicationVersion("/missing-installer-version.txt"))
    }

    @Test
    fun noticeNamesBothVersionsOnlyWhenTheRemoteInstallerDiffers() {
        val internal = checkNotNull(applicationVersion())
        val remote = "9.9.9"
        assertNotEquals(internal, remote)
        val directory = Files.createTempDirectory("remote-config")
        val seen = mutableListOf<URI>()
        val different = resolveApp(directory.resolve("different.yml"), seen, version = remote, sha = SHA)
        val notice = installerUpdateNotice(different)

        assertNotNull(notice)
        assertTrue(notice.contains(remote))
        assertTrue(notice.contains(internal))
        assertTrue(notice.contains("이에요"))
        assertFalse(notice.contains("입니다"))
        assertEquals(listOf(URI(MAIN_MANIFEST)), seen.toList())
        assertEquals(emptyList(), jarsUnder(directory))

        val sameSeen = mutableListOf<URI>()
        val same = resolveApp(directory.resolve("same.yml"), sameSeen, version = internal, sha = SHA)
        assertNull(installerUpdateNotice(same))
        assertEquals(listOf(URI(MAIN_MANIFEST)), sameSeen)

        val httpSeen = mutableListOf<URI>()
        val http = resolveApp(
            directory.resolve("http.yml"),
            httpSeen,
            version = remote,
            sha = SHA,
            url = "http://example.com/robotmc-installer.jar",
        )
        assertNull(http.pendingAppUpdate())
        assertNull(installerUpdateNotice(http))
        assertEquals(listOf(URI(MAIN_MANIFEST)), httpSeen)

        val shortSeen = mutableListOf<URI>()
        val shortSha = resolveApp(
            directory.resolve("sha.yml"),
            shortSeen,
            version = remote,
            sha = "abc",
        )
        assertNull(shortSha.pendingAppUpdate())
        assertNull(installerUpdateNotice(shortSha))
        assertEquals(remote, shortSha.update.app.version)
        assertEquals("", shortSha.update.app.sha256)
        assertEquals(listOf(URI(MAIN_MANIFEST)), shortSeen)

        assertNull(installerUpdateNotice(different, internalVersion = null))
        assertNull(installerUpdateNotice(different, internalVersion = ""))
        assertNull(installerUpdateNotice(different, internalVersion = " "))
        assertNull(installerUpdateNotice(different, applicationVersion("/missing-installer-version.txt")))
    }

    @Test
    fun redirectsStayOnHttps() {
        assertEquals(
            URI("https://example.com/installer.yml"),
            httpsRedirect(URI("https://example.com/old.yml"), "/installer.yml"),
        )
        assertFailsWith<IllegalArgumentException> {
            httpsRedirect(URI("https://example.com/old.yml"), "http://example.com/installer.yml")
        }
    }

    @Test
    fun fetchHttpsRejectsHttp() {
        assertFailsWith<IllegalArgumentException> {
            RemoteInstallerConfig.fetchHttps(URI("http://example.com/installer.yml"))
        }
    }

    @Test
    fun readLimitedStopsWhenTheBodyStalls() {
        val input = PipedInputStream()
        val output = PipedOutputStream(input)
        output.write(byteArrayOf(1, 2))
        val started = System.nanoTime()
        assertFailsWith<IllegalStateException> {
            readLimited(input, 100, Duration.ofMillis(200))
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 2_000, "elapsed $elapsedMs")
        output.close()
    }

    @Test
    fun readLimitedRejectsALargeBody() {
        val max = 8
        assertFailsWith<IllegalArgumentException> {
            readLimited("123456789".byteInputStream(), max)
        }
        assertEquals("12345678", readLimited("12345678".byteInputStream(), max))
    }

    private fun withStartup(resolved: RemoteInstallerConfig.Resolved, check: (ConfigurableApplicationContext) -> Unit) {
        val context = SpringApplicationBuilder(RemoteInstallerConfigTestApplication::class.java)
            .web(WebApplicationType.NONE)
            .registerShutdownHook(false)
            .run(*startupArguments(emptyArray(), resolved))
        try {
            check(context)
        } finally {
            context.close()
        }
    }

    private fun jarsUnder(directory: Path): List<String> {
        return Files.list(directory).use { paths ->
            paths.map { it.fileName.toString() }.filter { it.endsWith(".jar") }.toList()
        }
    }

    private fun resolveApp(
        cache: Path,
        seen: MutableList<URI>,
        version: String,
        sha: String,
        url: String = "https://example.com/robotmc-installer.jar",
    ): InstallerProperties {
        val resolved = RemoteInstallerConfig.fromBundled(cache) { uri ->
            seen += uri
            """
            installer:
              minecraft:
                version: "1.21.11"
              update:
                app:
                  version: "$version"
                  url: $url
                  sha256: $sha
            """.trimIndent()
        }.resolve()
        return bindInstaller(Files.readString(resolved!!.location))
    }

    private fun fetchedInstaller(yamlText: String): Map<*, *> {
        val root = Yaml().load<Map<*, *>>(yamlText)
        return root["installer"] as Map<*, *>
    }

    private fun downloadUrls(parent: Map<*, *>, key: String): List<String> {
        val items = parent[key] as? List<*> ?: return emptyList()
        return items.map { (it as Map<*, *>)["download-url"].toString() }
    }

    private fun config(
        cache: Path,
        url: String,
        fetch: (URI) -> String,
    ): RemoteInstallerConfig {
        return RemoteInstallerConfig(
            manifestUrl = url,
            bundledYaml = readBundledYaml(),
            cacheFile = cache,
            fetch = fetch,
        )
    }
}

private const val MAIN_MANIFEST =
    "https://raw.githubusercontent.com/robot-server/robotmc-installer/refs/heads/main/src/main/resources/application.yml"

private const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

private const val UNBINDABLE = """
installer:
  minecraft:
    version: "1.21.11"
  mod:
    loader:
      type: not_a_loader
      version: "1"
"""

private const val MAIN_SHAPE = """
spring:
  application:
    name: robotmc-installer
  main:
    web-application-type: servlet
installer:
  minecraft:
    version: "1.21.11"
  mod:
    loader:
      type: neo_forge
      version: 21.11.7-beta
      install-options:
        - '--install-client'
    mods:
      - download-url: 'https://example.com/from-main-shape.jar'
  servers:
    - ip: minecraft.o-r.cc
      name: Other Server
  resource-packs:
    - download-url: 'https://example.com/pack-from-main.zip'
"""

private const val REMOTE = """
installer:
  update:
    manifest-url: https://evil.example/other.yml
    source: forged
    app:
      version: "1.2.0"
      url: https://example.com/robotmc-installer.jar
      sha256: $SHA
  profile-name: Remote Pack
  minecraft:
    version: "1.21.12"
  mod:
    loader:
      type: fabric
      version: "0.16.0"
    mods:
      - download-url: https://cdn.modrinth.com/data/only/one.jar
  servers: []
  resource-packs: []
spring:
  main:
    banner-mode: "off"
"""

@SpringBootConfiguration
@EnableConfigurationProperties(InstallerProperties::class)
class RemoteInstallerConfigTestApplication
