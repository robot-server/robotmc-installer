package com.sysbot32.robotmc.installer

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 커밋된 워크플로가 정확한 릴리스 태그에서만 Windows SFX를 jar와 같은 릴리스에 올리는지 읽는다.
 */
class WindowsSfxWorkflowTest {

    @Test
    fun exactReleaseTagPublishesOneWindowsSfxBesideTheJar() {
        val root = File(System.getProperty("user.dir"))
        check(File(root, "settings.gradle.kts").isFile) {
            "테스트 작업 디렉터리가 저장소 루트가 아닙니다: ${root.absolutePath}"
        }
        val document = Yaml().load<Map<String, Any>>(
            File(root, ".github/workflows/build.yml").readText(),
        )
        @Suppress("UNCHECKED_CAST")
        val jobs = document.getValue("jobs") as Map<String, Map<String, Any?>>
        assertEquals(setOf("test", "release", "release-windows"), jobs.keys)

        val testJob = jobs.getValue("test")
        val releaseJob = jobs.getValue("release")
        val windowsJob = jobs.getValue("release-windows")
        assertEquals("release-windows", windowsJob["name"])
        assertEquals("ubuntu-latest", testJob["runs-on"])
        assertEquals("ubuntu-latest", releaseJob["runs-on"])
        assertEquals("windows-latest", windowsJob["runs-on"])
        assertEquals(releaseJob["if"], windowsJob["if"])
        val gate = windowsJob["if"].toString()
        assertTrue(gate.contains("github.event_name == 'push'"))
        assertTrue(gate.contains("github.event_name == 'workflow_dispatch'"))
        assertTrue(gate.contains("github.ref_type == 'tag'"))
        assertFalse(gate.contains("pull_request"))
        assertTrue(needs(windowsJob).contains("release"))
        assertEquals("write", (windowsJob["permissions"] as Map<*, *>)["contents"])

        val exactTag = """v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"""
        val testScripts = runScripts(testJob)
        assertTrue(testScripts.any { it.contains("./gradlew test") })
        assertFalse(testScripts.any { it.contains("packageInstallerAppImage") || it.contains("packageWindowsSfx") || it.contains("jpackage") })

        val releaseScripts = runScripts(releaseJob)
        assertTrue(releaseScripts.any { it.contains(exactTag) })
        val releaseBoot = releaseScripts.single { it.contains("./gradlew bootJar") }
        assertTrue(releaseBoot.contains("-PreleaseRef="))
        assertFalse(releaseBoot.contains("hostOnlyBootJar"))
        assertFalse(releaseBoot.contains("currentOs"))
        assertFalse(releaseScripts.joinToString("\n").contains("hostOnlyBootJar"))
        val jarUpload = releaseScripts.single { it.contains("gh release create") }
        assertTrue(jarUpload.contains("robotmc-installer-"))
        assertTrue(jarUpload.contains(".jar"))
        assertFalse(jarUpload.contains("packageInstallerAppImage"))
        assertFalse(jarUpload.contains("packageWindowsSfx"))

        assertEquals("jdk+jmods", setupJavaPackage(windowsJob))
        assertEquals(null, setupJavaPackage(testJob))
        assertEquals(null, setupJavaPackage(releaseJob))

        val windowsScripts = runScripts(windowsJob)
        assertTrue(windowsScripts.any { it.contains(exactTag) })
        assertEquals("bash", shellOfRunSteps(windowsJob).toSet().single())
        val pack = windowsScripts.single { it.contains("packageWindowsSfx") }
        assertTrue(pack.contains("hostOnlyBootJar"))
        assertTrue(pack.contains("packageInstallerAppImage"))
        assertTrue(pack.contains("-PreleaseRef="))
        assertFalse(pack.contains("-PsfxModule="))
        assertFalse(pack.contains("7-zip.org"))
        assertFalse(pack.contains("--type exe"))
        assertFalse(pack.contains("msi"))
        assertFalse(windowsScripts.joinToString("\n").contains("7-zip.org"))
        val upload = windowsScripts.single { it.contains("gh release upload") }
        assertTrue(upload.contains("build/windows-sfx/robotmc-installer-"))
        assertTrue(upload.contains(".exe"))
        assertFalse(upload.contains("gh release create"))
        assertFalse(upload.contains(".zip"))
        assertFalse(upload.contains(".msi"))
        assertFalse(upload.contains(".jar"))

        for ((name, job) in jobs) {
            assertFalse(job["runs-on"].toString().contains("macos"), name)
            if (name == "release-windows") {
                continue
            }
            val scripts = runScripts(job).joinToString("\n")
            assertFalse(scripts.contains("packageInstallerAppImage"), name)
            assertFalse(scripts.contains("packageWindowsSfx"), name)
            assertFalse(scripts.contains("jpackage"), name)
            assertFalse(scripts.contains("hostOnlyBootJar"), name)
        }
        val buildScript = File(root, "build.gradle.kts").readText()
        assertTrue(buildScript.contains("compose.desktop.currentOs"))
        val imageTask = buildScript.substringAfter("tasks.register(\"packageInstallerAppImage\")")
            .substringBefore("tasks.register(\"packageWindowsSfx\")")
        assertTrue(imageTask.contains("copiedJar = installerHostBootJar.get().asFile"))
        assertFalse(imageTask.contains("installerBootJar"))
        val checkTask = buildScript.substringAfter("tasks.register(\"checkInstallerAppImage\")")
            .substringBefore("fun buildInstallerAppImage")
        assertTrue(checkTask.contains("copiedJar = installerHostBootJar.get().asFile"))
        assertFalse(checkTask.contains("installerBootJar"))
    }
}

private fun needs(job: Map<String, Any?>): Set<String> {
    return when (val value = job["needs"]) {
        is String -> setOf(value)
        is List<*> -> value.map { it.toString() }.toSet()
        else -> error("needs 가 없습니다: $value")
    }
}

@Suppress("UNCHECKED_CAST")
private fun setupJavaPackage(job: Map<String, Any?>): String? {
    val steps = job["steps"] as List<Map<String, Any?>>
    val setup = steps.single { it["uses"]?.toString()?.startsWith("actions/setup-java@") == true }
    val options = setup["with"] as Map<*, *>
    return options["java-package"]?.toString()
}

@Suppress("UNCHECKED_CAST")
private fun runScripts(job: Map<String, Any?>): List<String> {
    val steps = job["steps"] as List<Map<String, Any?>>
    return steps.mapNotNull { it["run"]?.toString() }
}

@Suppress("UNCHECKED_CAST")
private fun shellOfRunSteps(job: Map<String, Any?>): List<String> {
    val steps = job["steps"] as List<Map<String, Any?>>
    return steps.filter { it["run"] != null }.map { it["shell"]?.toString() ?: "" }
}
