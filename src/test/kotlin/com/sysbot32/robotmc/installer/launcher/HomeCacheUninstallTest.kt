package com.sysbot32.robotmc.installer.launcher

import com.sysbot32.robotmc.installer.config.DEFAULT_PROFILE_KEY
import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.config.gameDirectory
import com.sysbot32.robotmc.installer.exception.UserException
import com.sysbot32.robotmc.installer.mod.loader.ModLoaderInstallService
import com.sysbot32.robotmc.installer.mod.loader.ModLoaderType
import com.sysbot32.robotmc.installer.mod.loader.decideModLoaderInstall
import com.sysbot32.robotmc.installer.prelaunch.installerJavaExecutable
import com.sysbot32.robotmc.installer.progress.ProgressService
import org.springframework.http.HttpMethod
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.web.client.RestClient
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class HomeCacheUninstallTest {
    @Test
    fun finishedUninstallRemovesTheHomeCacheAndNothingOutsideIt() {
        val world = world()
        val existed = realHomeExisted()
        seedHome(world.home, world.linkTarget)
        val readonly = world.home.resolve("readonly.txt")
        assertFalse(readonly.toFile().canWrite())
        val record = gameDirectory(world.minecraft).resolve(".robotmc-installer").resolve("installed.txt")
        prepareRemovedInstall(world.minecraft, record)
        val otherCache = world.root.resolve("not-the-home-cache").resolve(".robotmc-installer")
        Files.createDirectories(otherCache)
        Files.writeString(otherCache.resolve("note.txt"), "not-home")
        val service = service(world.minecraft, world.home)

        service.uninstall()

        assertHomeGone(world.home)
        assertEquals("target", Files.readString(world.linkTarget.resolve("keep.txt")))
        assertEquals("sibling", Files.readString(world.sibling.resolve("keep.txt")))
        assertEquals("not-home", Files.readString(otherCache.resolve("note.txt")))
        assertFalse(Files.exists(record))
        assertRemovedOnlyThisInstall(world.minecraft)
        assertRealHomeUnchanged(existed)
    }

    @Test
    fun finishedUninstallRemovesAJunctionWithoutItsTarget() {
        val world = world()
        val existed = realHomeExisted()
        seedHome(world.home, world.linkTarget)
        val junctionTarget = createWindowsJunction(world.home, world.root)
        val record = gameDirectory(world.minecraft).resolve(".robotmc-installer").resolve("installed.txt")
        prepareRemovedInstall(world.minecraft, record)
        val service = service(world.minecraft, world.home)
        service.homeCacheRunningFile = { null }

        service.uninstall()

        assertHomeGone(world.home)
        assertEquals("target", Files.readString(world.linkTarget.resolve("keep.txt")))
        assertJunctionTarget(junctionTarget)
        assertFalse(Files.exists(record, LinkOption.NOFOLLOW_LINKS))
        assertRealHomeUnchanged(existed)
    }

    @Test
    fun finishedUninstallSucceedsWhenTheHomeCacheIsAlreadyGone() {
        val world = world()
        val existed = realHomeExisted()
        prepareRemovedInstall(world.minecraft, gameDirectory(world.minecraft).resolve(".robotmc-installer").resolve("installed.txt"))
        Files.delete(world.home)
        assertFalse(Files.exists(world.home))
        val service = service(world.minecraft, world.home)

        service.uninstall()

        assertFalse(Files.exists(world.home))
        assertRemovedOnlyThisInstall(world.minecraft)
        assertRealHomeUnchanged(existed)
    }

    @Test
    fun runningFileInsideTheHomeCacheSurvivesUntilThatProcessExits() {
        val world = world()
        val existed = realHomeExisted()
        seedHome(world.home, world.linkTarget)
        val jar = sleepingJar(world.home.resolve("robotmc-installer.jar"), world.root.resolve("classes"))
        val child = startJar(jar)
        try {
            Thread.sleep(150)
            assertTrue(child.isAlive, "installer jar exited before uninstall")
            prepareRemovedInstall(
                world.minecraft,
                gameDirectory(world.minecraft).resolve(".robotmc-installer").resolve("installed.txt"),
            )
            val service = service(world.minecraft, world.home)
            service.homeCacheRunningFile = { jar }
            service.homeCacheWaitPid = { child.pid() }

            service.uninstall()

            val until = System.nanoTime() + 400_000_000L
            while (System.nanoTime() < until) {
                assertTrue(child.isAlive)
                assertTrue(Files.isRegularFile(jar))
                assertTrue(Files.isDirectory(world.home))
                assertEquals("target", Files.readString(world.linkTarget.resolve("keep.txt")))
                Thread.sleep(40)
            }
            assertFalse(Files.exists(loaderVersionAliasPath(world.minecraft)))
            assertFalse(Files.exists(gameDirectory(world.minecraft).resolve("mods")))
            child.destroyForcibly()
            assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            awaitAbsent(world.home)
            assertFalse(Files.exists(jar))
            assertFalse(Files.exists(world.home.resolve("readonly.txt")))
            assertEquals("target", Files.readString(world.linkTarget.resolve("keep.txt")))
            assertEquals("sibling", Files.readString(world.sibling.resolve("keep.txt")))
            assertRealHomeUnchanged(existed)
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    fun installLeavesTheHomeCacheInPlace() {
        val world = world()
        val existed = realHomeExisted()
        seedHome(world.home, world.linkTarget)
        Files.createDirectories(world.minecraft)
        Files.writeString(world.minecraft.resolve("launcher_profiles.json"), """{"profiles":{}}""")
        writeLoaderVersionAlias(world.minecraft, "neoforge-21.11.6-beta")
        val service = service(world.minecraft, world.home)
        service.installerDirectory = world.home
        val decision = decideModLoaderInstall(
            type = ModLoaderType.NEO_FORGE,
            loaderVersion = "21.11.6-beta",
            minecraftVersion = "1.21.11",
            minecraftDirectory = world.minecraft,
            installOptions = emptyList(),
            profiles = LauncherProfilesJson(profiles = emptyMap()),
            installerDirectory = world.home,
            aliasInheritsFrom = "neoforge-21.11.6-beta",
        )
        Files.write(decision.installerJar, byteArrayOf(9, 9, 9))

        service.install()

        assertTrue(Files.isDirectory(world.home))
        assertEquals("manifest: true\n", Files.readString(world.home.resolve("manifest.yml")))
        assertTrue(byteArrayOf(9, 9, 9).contentEquals(Files.readAllBytes(decision.installerJar)))
        assertTrue(Files.isRegularFile(world.home.resolve("robotmc-prelaunch-agent.jar")))
        assertEquals("target", Files.readString(world.linkTarget.resolve("keep.txt")))
        val written = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(Files.readString(world.minecraft.resolve("launcher_profiles.json")))
        assertEquals("RobotMC", written.get("profiles").get(DEFAULT_PROFILE_KEY).get("name").asText())
        assertRealHomeUnchanged(existed)
    }

    @Test
    fun uninstallThatStopsBeforeTheGameDirectoryIsRemovedLeavesTheHomeCache() {
        val world = world()
        val existed = realHomeExisted()
        seedHome(world.home, world.linkTarget)
        val record = gameDirectory(world.minecraft).resolve(".robotmc-installer").resolve("installed.txt")
        prepareRemovedInstall(world.minecraft, record)
        val mods = gameDirectory(world.minecraft).resolve("mods")
        assertTrue(mods.toFile().setWritable(false, false))
        val service = service(world.minecraft, world.home)
        try {
            val error = kotlin.test.assertFailsWith<UserException> {
                service.uninstall()
            }
            assertTrue(error.message.orEmpty().contains("삭제하지 못했어요"))
            assertTrue(Files.isDirectory(world.home))
            assertEquals("manifest: true\n", Files.readString(world.home.resolve("manifest.yml")))
            assertTrue(Files.isRegularFile(world.home.resolve("robotmc-installer.jar")))
            assertTrue(Files.isRegularFile(world.home.resolve("logs/application.log")))
            assertEquals("target", Files.readString(world.linkTarget.resolve("keep.txt")))
            assertFalse(Files.exists(loaderVersionAliasPath(world.minecraft)))
            assertTrue(Files.isRegularFile(mods.resolve("user.jar")))
            assertRealHomeUnchanged(existed)
        } finally {
            mods.toFile().setWritable(true, false)
        }
    }

    @Test
    fun detachedJanitorRemovesTheCacheOnlyAfterTheProcessExits() {
        val parent = Files.createTempDirectory("robotmc-janitor")
        val home = parent.resolve(".robotmc-installer")
        Files.createDirectories(home)
        assertStandIn(home)
        val existed = realHomeExisted()
        val target = parent.resolve("link-target")
        Files.createDirectories(target)
        Files.writeString(target.resolve("keep.txt"), "target")
        Files.createSymbolicLink(home.resolve("outside-link"), target)
        val nestedTarget = parent.resolve("nested-target")
        Files.createDirectories(nestedTarget)
        Files.writeString(nestedTarget.resolve("keep.txt"), "nested")
        Files.createDirectories(home.resolve("nested"))
        Files.createSymbolicLink(home.resolve("nested").resolve("dir-link"), nestedTarget)
        val junctionTarget = createWindowsJunction(home, parent)
        val readonly = home.resolve("readonly.txt")
        Files.writeString(readonly, "locked")
        readonly.toFile().setReadOnly()
        val jar = sleepingJar(home.resolve("robotmc-installer.jar"), parent.resolve("classes"))
        val child = startJar(jar)
        val errors = parent.resolve("janitor.err")
        try {
            Thread.sleep(150)
            assertTrue(child.isAlive)
            spawnDetachedHomeCacheJanitor(home, child.pid(), errors)
            val until = System.nanoTime() + 400_000_000L
            while (System.nanoTime() < until) {
                assertTrue(child.isAlive, janitorErrors(errors))
                assertTrue(Files.isRegularFile(jar), janitorErrors(errors))
                assertEquals("target", Files.readString(target.resolve("keep.txt")))
                assertEquals("nested", Files.readString(nestedTarget.resolve("keep.txt")))
                assertJunctionTarget(junctionTarget)
                Thread.sleep(40)
            }
            child.destroyForcibly()
            assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            awaitAbsent(home, errors)
            assertEquals("target", Files.readString(target.resolve("keep.txt")))
            assertEquals("nested", Files.readString(nestedTarget.resolve("keep.txt")))
            assertTrue(Files.isDirectory(nestedTarget))
            assertJunctionTarget(junctionTarget)
            assertRealHomeUnchanged(existed)
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    fun runningInstallerFileIsTheCopiedJarOrTheAppImageLauncher() {
        val root = Files.createTempDirectory("robotmc-running-file")
        val home = root.resolve("home")
        Files.createDirectories(home)
        val jar = home.resolve("robotmc-installer.jar")
        Files.write(jar, byteArrayOf(1))
        val javaBin = home.resolve("java")
        Files.write(javaBin, byteArrayOf(1))
        assertEquals(
            jar.toAbsolutePath().normalize(),
            runningInstallerFile(listOf(javaBin.toString(), "-jar", jar.toString())),
        )
        val launcher = home.resolve("RobotMC Installer.app").resolve("Contents").resolve("MacOS").resolve("RobotMC Installer")
        Files.createDirectories(launcher.parent)
        Files.write(launcher, byteArrayOf(1))
        assertEquals(
            launcher.toAbsolutePath().normalize(),
            runningInstallerFile(listOf(launcher.toString())),
        )
        assertNull(runningInstallerFile(listOf(javaBin.toString(), "-cp", "classes", "com.example.Main")))
        val outside = root.resolve("outside.jar")
        Files.write(outside, byteArrayOf(1))
        assertTrue(pathIsInside(home, jar))
        assertFalse(pathIsInside(home, outside))
        val target = root.resolve("target.txt")
        Files.writeString(target, "target")
        val link = home.resolve("link")
        Files.createSymbolicLink(link, target)
        assertTrue(pathIsInside(home, link))
        assertFalse(pathIsInside(home, target))
    }

    private fun world(): World {
        val root = Files.createTempDirectory("robotmc-home-uninstall")
        val minecraft = root.resolve("My Minecraft")
        Files.createDirectories(minecraft)
        val home = root.resolve("home-cache")
        Files.createDirectories(home)
        val sibling = root.resolve("sibling")
        Files.createDirectories(sibling)
        Files.writeString(sibling.resolve("keep.txt"), "sibling")
        val linkTarget = root.resolve("link-target")
        val world = World(root, minecraft, home, sibling, linkTarget)
        assertStandIn(home)
        return world
    }

    private fun seedHome(home: Path, linkTarget: Path) {
        Files.createDirectories(home.resolve("logs"))
        Files.createDirectories(home.resolve("replace"))
        Files.write(home.resolve("robotmc-installer.jar"), byteArrayOf(1, 2, 3))
        Files.write(home.resolve("robotmc-prelaunch-agent.jar"), byteArrayOf(4))
        Files.writeString(home.resolve("manifest.yml"), "manifest: true\n")
        Files.write(home.resolve("neoforge-21.11.6-beta-installer.jar"), byteArrayOf(5))
        Files.writeString(home.resolve("logs/application.log"), "log-line\n")
        Files.write(home.resolve("replace/installer-replace.jar"), byteArrayOf(6))
        val readonly = home.resolve("readonly.txt")
        Files.writeString(readonly, "locked")
        readonly.toFile().setReadOnly()
        val dos = Files.getFileAttributeView(readonly, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        if (dos != null && !dos.readAttributes().isReadOnly) {
            dos.setReadOnly(true)
        }
        Files.createDirectories(linkTarget)
        Files.writeString(linkTarget.resolve("keep.txt"), "target")
        Files.createSymbolicLink(home.resolve("outside-link"), linkTarget)
    }

    private fun prepareRemovedInstall(minecraft: Path, record: Path) {
        Files.createDirectories(minecraft)
        Files.writeString(
            minecraft.resolve("launcher_profiles.json"),
            """
            {
              "settings": {"keepLauncherOpen": false},
              "profiles": {
                "NeoForge": {"name":"NeoForge","type":"custom","lastVersionId":"neoforge-21.11.6-beta"},
                "$DEFAULT_PROFILE_KEY": {"name":"RobotMC","type":"custom","lastVersionId":"RobotMC","gameDir":"/old"}
              }
            }
            """.trimIndent(),
        )
        val gameDir = gameDirectory(minecraft)
        Files.createDirectories(gameDir.resolve("saves/World"))
        Files.writeString(gameDir.resolve("saves/World/level.dat"), "save")
        Files.createDirectories(gameDir.resolve("mods"))
        Files.writeString(gameDir.resolve("mods/user.jar"), "user")
        Files.createDirectories(record.parent)
        Files.writeString(record, "record")
        Files.createDirectories(minecraft.resolve("mods"))
        Files.writeString(minecraft.resolve("mods/keep.jar"), "vanilla-mod")
        Files.createDirectories(minecraft.resolve("versions/neoforge-21.11.6-beta"))
        Files.writeString(
            minecraft.resolve("versions/neoforge-21.11.6-beta/neoforge-21.11.6-beta.json"),
            "loader",
        )
        writeLoaderVersionAlias(minecraft, "neoforge-21.11.6-beta")
    }

    private fun assertRemovedOnlyThisInstall(minecraft: Path) {
        val gameDir = gameDirectory(minecraft)
        assertEquals("save", Files.readString(gameDir.resolve("saves/World/level.dat")))
        assertFalse(Files.exists(gameDir.resolve("mods")))
        assertFalse(Files.exists(loaderVersionAliasPath(minecraft)))
        assertEquals(
            "loader",
            Files.readString(minecraft.resolve("versions/neoforge-21.11.6-beta/neoforge-21.11.6-beta.json")),
        )
        assertEquals("vanilla-mod", Files.readString(minecraft.resolve("mods/keep.jar")))
        val profiles = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(Files.readString(minecraft.resolve("launcher_profiles.json")))
        assertEquals("NeoForge", profiles.get("profiles").get("NeoForge").get("name").asText())
        assertTrue(profiles.get("profiles").get(DEFAULT_PROFILE_KEY) == null || profiles.get("profiles").get(DEFAULT_PROFILE_KEY).isNull)
    }

    private fun assertHomeGone(home: Path) {
        assertFalse(Files.exists(home, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(home.resolve("robotmc-installer.jar")))
        assertFalse(Files.exists(home.resolve("robotmc-prelaunch-agent.jar")))
        assertFalse(Files.exists(home.resolve("manifest.yml")))
        assertFalse(Files.exists(home.resolve("neoforge-21.11.6-beta-installer.jar")))
        assertFalse(Files.exists(home.resolve("logs/application.log")))
        assertFalse(Files.exists(home.resolve("readonly.txt")))
        assertFalse(Files.exists(home.resolve("outside-link")))
    }

    private fun service(minecraft: Path, home: Path): ModLoaderInstallService {
        val properties = InstallerProperties(
            minecraft = InstallerProperties.Minecraft(version = "1.21.11", directory = minecraft),
            mod = InstallerProperties.Mod(
                loader = InstallerProperties.Mod.Loader(
                    type = ModLoaderType.NEO_FORGE,
                    version = "21.11.6-beta",
                ),
            ),
        )
        val created = ModLoaderInstallService(
            LauncherService(properties, Jackson2ObjectMapperBuilder.json().build()),
            RestClient.builder().requestFactory(RefusingHomeCacheRequests()).build(),
            properties,
            ProgressService(),
        )
        created.homeCacheDirectory = home
        created.installerDirectory = home.resolve("loader-jars")
        return created
    }

    private fun sleepingJar(destination: Path, classDir: Path): Path {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("javac is required")
        Files.createDirectories(classDir)
        val source = classDir.resolve("Hold.java")
        Files.writeString(
            source,
            """
            public class Hold {
                public static void main(String[] args) throws Exception {
                    Thread.sleep(Long.parseLong(args[0]));
                }
            }
            """.trimIndent(),
        )
        val errors = java.io.ByteArrayOutputStream()
        val exit = compiler.run(null, null, errors, "-d", classDir.toString(), source.toString())
        if (exit != 0) {
            fail(errors.toString())
        }
        destination.parent?.let { Files.createDirectories(it) }
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name.MAIN_CLASS] = "Hold"
        JarOutputStream(Files.newOutputStream(destination), manifest).use { output ->
            output.putNextEntry(JarEntry("Hold.class"))
            output.write(Files.readAllBytes(classDir.resolve("Hold.class")))
            output.closeEntry()
        }
        return destination
    }

    private fun startJar(jar: Path): Process {
        val javaBin = installerJavaExecutable()
        return ProcessBuilder(javaBin, "-jar", jar.toString(), "120000")
            .directory(jar.parent?.parent?.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    private fun awaitAbsent(path: Path, errorLog: Path? = null) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return
            }
            Thread.sleep(50)
        }
        fail("$path was not removed\n${janitorErrors(errorLog)}")
    }

    /**
     * Windows에서는 junction을 만든다. 그 외에는 junction이 없고, 디렉터리 심볼릭 링크가 같은 경우다.
     */
    private fun createWindowsJunction(home: Path, parent: Path): Path? {
        if (!System.getProperty("os.name", "").lowercase().startsWith("windows")) {
            return null
        }
        val junctionTarget = parent.resolve("junction-target")
        Files.createDirectories(junctionTarget)
        Files.writeString(junctionTarget.resolve("keep.txt"), "junction")
        val link = home.resolve("junction-link")
        val result = ProcessBuilder(
            "cmd.exe",
            "/c",
            "mklink",
            "/J",
            link.toString(),
            junctionTarget.toString(),
        ).redirectErrorStream(true).start()
        val output = result.inputStream.bufferedReader().readText()
        assertEquals(0, result.waitFor(), output)
        return junctionTarget
    }

    private fun assertJunctionTarget(junctionTarget: Path?) {
        if (junctionTarget == null) {
            return
        }
        assertEquals("junction", Files.readString(junctionTarget.resolve("keep.txt")))
        assertTrue(Files.isDirectory(junctionTarget))
    }

    private fun janitorErrors(errorLog: Path?): String {
        if (errorLog == null || !Files.isRegularFile(errorLog)) {
            return ""
        }
        return Files.readString(errorLog)
    }

    private fun realHome(): Path {
        return Path.of(System.getProperty("user.home"), ".robotmc-installer").toAbsolutePath().normalize()
    }

    private fun realHomeExisted(): Boolean = Files.exists(realHome(), LinkOption.NOFOLLOW_LINKS)

    private fun assertRealHomeUnchanged(existed: Boolean) {
        assertEquals(existed, realHomeExisted())
    }

    private fun assertStandIn(home: Path) {
        val path = home.toAbsolutePath().normalize()
        val real = realHome()
        assertNotEquals(real, path)
        assertFalse(path.startsWith(real), path.toString())
    }

    private data class World(
        val root: Path,
        val minecraft: Path,
        val home: Path,
        val sibling: Path,
        val linkTarget: Path,
    )
}

private class RefusingHomeCacheRequests : ClientHttpRequestFactory {
    override fun createRequest(uri: URI, httpMethod: HttpMethod): ClientHttpRequest {
        throw IllegalStateException("unexpected download $uri")
    }
}
