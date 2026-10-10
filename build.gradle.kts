import com.sysbot32.robotmc.installer.InstallerAppIcon
import com.sysbot32.robotmc.installer.InstallerVersion
import com.sysbot32.robotmc.installer.WindowsSfxPack
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarFile

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    kotlin("plugin.compose") version "2.3.21"
    id("org.jetbrains.compose") version "1.12.1"
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.sysbot32"
/**
 * 새로운 시작 - 메이저
 * 기존 사용자 업데이트 필요 - 마이너
 * 기존 사용자 업데이트 필요 없음 - 패치
 *
 * 태그 릴리스 잡만 `-PreleaseRef=v1.2.0` 처럼 넘긴다. 없으면 1.0.0 이다.
 */
version = InstallerVersion.versionFor(gradle.startParameter.projectProperties["releaseRef"])

// 설치기 JDK. 루트 툴체인, Kotlin 툴체인, jlink/jpackage, 이미지 검사가 이 값을 같이 쓴다.
// 게임 JVM이 읽는 prelaunch 에이전트는 이 값과 별개로 Java 21이다.
val installerJdkMajor = 25

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(installerJdkMajor)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
    google()
}

lateinit var hostDesktopCoordinate: String

dependencies {
    implementation(project(":prelaunch"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.7")
    implementation("org.apache.commons:commons-exec:1.4.0")
    implementation("dev.dewy:nbt:1.5.1")
    // 릴리스 bootJar는 OS와 상관없이 그대로 쓰는 실행 jar다. currentOs만 넣으면 다른 OS 네이티브가 빠진다.
    // Windows exe가 넣는 jar는 hostOnlyBootJar다. 그쪽만 compose.desktop.currentOs로 좁힌다.
    implementation(compose.desktop.linux_arm64)
    implementation(compose.desktop.linux_x64)
    implementation(compose.desktop.macos_arm64)
    implementation(compose.desktop.macos_x64)
    implementation(compose.desktop.windows_arm64)
    implementation(compose.desktop.windows_x64)
    // dependencies 블록 안에서만 compose.desktop.currentOs 를 볼 수 있다.
    hostDesktopCoordinate = compose.desktop.currentOs
    implementation(compose.material3)
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(files(rootProject.layout.projectDirectory.file("buildSrc/build/libs/buildSrc.jar")))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(installerJdkMajor)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

compose.desktop {
    application {
        mainClass = "com.sysbot32.robotmc.installer.RobotmcInstallerApplicationKt"
    }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    // 이 두 모듈은 클래스가 없는 리다이렉트다. 파일 이름이 androidx의 실제 jar와 같아서
    // bootJar가 실패한다. duplicatesStrategy = EXCLUDE는 runtime-desktop에서 빈 jar가 앞에 있어
    // 클래스 있는 쪽을 버린다.
    val redirects by lazy { runtimeDesktopRedirectJars() }
    classpath = classpath.filter { file -> file !in redirects }
}

// 앱 이미지와 Windows SFX가 넣는 bootJar. 릴리스 잡의 bootJar와 출력이 다르다.
val hostOnlyBootJar = tasks.register<org.springframework.boot.gradle.tasks.bundling.BootJar>("hostOnlyBootJar") {
    group = "build"
    description = "현재 OS의 Skiko만 넣은 bootJar. 앱 이미지가 이 jar를 넣는다."
    val releaseJar = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")
    archiveBaseName.set(releaseJar.flatMap { it.archiveBaseName })
    archiveVersion.set(releaseJar.flatMap { it.archiveVersion })
    destinationDirectory.set(layout.buildDirectory.dir("host-boot-jar"))
    mainClass.set(releaseJar.flatMap { it.mainClass })
    targetJavaVersion.set(releaseJar.flatMap { it.targetJavaVersion })
    val runtimeClasspath = configurations.runtimeClasspath
    resolvedArtifacts(runtimeClasspath.get().incoming.artifacts.resolvedArtifacts)
    val excluded by lazy { runtimeDesktopRedirectJars() + foreignSkikoFiles() }
    classpath = releaseJar.get().classpath.filter { file -> file !in excluded }
    dependsOn(tasks.named("classes"))
}

// Package.implementationVersion 은 테스트의 디렉터리 클래스패스와 bootJar 의 BOOT-INF/classes 에서 비어 있다.
val generateInstallerVersion = tasks.register("generateInstallerVersion") {
    val outputDir = layout.buildDirectory.dir("generated/installer-version")
    inputs.property("version", project.version.toString())
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        dir.resolve("installer-version.txt").writeText(project.version.toString())
    }
}

sourceSets.named("main") {
    resources.srcDir(generateInstallerVersion)
}

val prelaunchAgentJar = project(":prelaunch").layout.buildDirectory.file("agent/robotmc-prelaunch-agent.jar")
val replaceJar = project(":replace").layout.buildDirectory.file("replace/installer-replace.jar")

tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
    dependsOn(":prelaunch:agentJar", ":replace:replaceJar")
    from(prelaunchAgentJar)
    from(replaceJar)
}

tasks.withType<Test> {
    dependsOn(":prelaunch:agentJar")
    systemProperty("robotmc.prelaunch.agent", prelaunchAgentJar.get().asFile.absolutePath)
    systemProperty(
        "robotmc.prelaunch.home",
        layout.buildDirectory.dir("tmp/prelaunch-home").get().asFile.absolutePath,
    )
    providers.gradleProperty("robotmc.prelaunch.log").orNull?.let { logPath ->
        systemProperty("robotmc.prelaunch.log", logPath)
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Implementation-Title" to "RobotMC Installer",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// bootJar의 Main-Class. Kotlin main이 아니다. 중첩된 BOOT-INF를 이 로더가 연다.
val bootJarMainClass = "org.springframework.boot.loader.launch.JarLauncher"
val installerAppName = "RobotMC Installer"

val installerJdk = extensions.getByType(JavaToolchainService::class.java).launcherFor {
    languageVersion.set(JavaLanguageVersion.of(installerJdkMajor))
}
val installerBootJar = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")
    .flatMap { it.archiveFile }

val installerHostBootJar = hostOnlyBootJar.flatMap { it.archiveFile }

tasks.withType<Test> {
    dependsOn(installerBootJar)
    dependsOn(installerHostBootJar)
    systemProperty("robotmc.boot.jar", installerBootJar.get().asFile.absolutePath)
    systemProperty("robotmc.host.boot.jar", installerHostBootJar.get().asFile.absolutePath)
}
val installerAppImageDir = layout.buildDirectory.dir("installer-app-image")
val installerAppImageWork = layout.buildDirectory.dir("tmp/installer-app-image")

tasks.register("packageInstallerAppImage") {
    group = "distribution"
    description = "현재 OS용 앱 이미지에 Java $installerJdkMajor 런타임과 현재 OS Skiko만 넣은 jar를 넣는다."
    dependsOn(installerHostBootJar)
    val appIcon = InstallerAppIcon.fileFor(installerHostOs(), project.rootDir)
    inputs.file(installerHostBootJar)
    inputs.file(appIcon)
    inputs.property("version", providers.provider { project.version.toString() })
    inputs.property("jdk", installerJdk.map { it.metadata.installationPath.asFile.absolutePath })
    outputs.dir(installerAppImageDir)
    doLast {
        buildInstallerAppImage(
            jdkHome = installerJdk.get().metadata.installationPath.asFile,
            copiedJar = installerHostBootJar.get().asFile,
            destination = installerAppImageDir.get().asFile,
            work = installerAppImageWork.get().asFile,
            appVersion = project.version.toString(),
            icon = appIcon,
        )
    }
}

// test에 붙이지 않는다. 이미지를 만들면 단위 테스트가 느려진다.
// Windows 러너에서만 의미 있다. jpackage는 호스트 OS 이미지만 만들고, 수정 SFX 모듈이 InstallPath를 유지한다.
tasks.register("packageWindowsSfx") {
    group = "distribution"
    description = "Windows 앱 이미지를 7-Zip SFX exe 하나로 묶는다."
    dependsOn("packageInstallerAppImage")
    val config = WindowsSfxPack.configFile(project.projectDir)
    val licenseNotice = WindowsSfxPack.licenseNoticeFile(project.projectDir)
    val sfxModule = providers.gradleProperty("sfxModule")
    inputs.file(config)
    inputs.file(licenseNotice)
    inputs.dir(installerAppImageDir)
    inputs.property("version", providers.provider { project.version.toString() })
    sfxModule.orNull?.let { inputs.file(File(it)) }
    val output = layout.buildDirectory.file(
        "${WindowsSfxPack.OUTPUT_DIR}/robotmc-installer-${project.version}.exe",
    )
    outputs.file(output)
    doLast {
        if (installerHostOs() != "windows") {
            throw org.gradle.api.GradleException("packageWindowsSfx는 Windows에서만 앱 이미지를 묶는다.")
        }
        val sevenZip = WindowsSfxPack.findSevenZip()
        val module = sfxModule.orNull?.let { File(it) }
            ?: WindowsSfxPack.downloadModule(
                layout.buildDirectory.dir("${WindowsSfxPack.OUTPUT_DIR}/module").get().asFile,
                sevenZip,
            )
        WindowsSfxPack.pack(
            imageDir = installerImagePaths(installerAppImageDir.get().asFile).image,
            configFile = config,
            moduleFile = module,
            sevenZip = sevenZip,
            destination = output.get().asFile,
            licenseNotice = licenseNotice,
        )
    }
}

tasks.register("checkInstallerAppImage") {
    group = "verification"
    description = "앱 이미지의 런처와 포함된 Java $installerJdkMajor java를 검사한다."
    val appIcon = InstallerAppIcon.fileFor(installerHostOs(), project.rootDir)
    dependsOn("packageInstallerAppImage")
    inputs.file(installerHostBootJar)
    inputs.file(appIcon)
    inputs.dir(installerAppImageDir)
    inputs.property("jdk", installerJdk.map { it.metadata.installationPath.asFile.absolutePath })
    doLast {
        val testTask = tasks.named("test").get()
        val testDependsOnImage = testTask.taskDependencies.getDependencies(testTask)
            .any {
                it.name == "packageInstallerAppImage" ||
                    it.name == "checkInstallerAppImage" ||
                    it.name == "packageWindowsSfx"
            }
        if (testDependsOnImage) {
            throw org.gradle.api.GradleException("test 태스크가 앱 이미지를 만들면 안 된다.")
        }
        val result = verifyInstallerAppImage(
            imageDir = installerAppImageDir.get().asFile,
            copiedJar = installerHostBootJar.get().asFile,
            jdkHome = installerJdk.get().metadata.installationPath.asFile,
            iconFile = appIcon,
        )
        logger.lifecycle("launcher: ${result.launcher.canonicalPath}")
        logger.lifecycle("bundled java: ${result.javaExecutable.canonicalPath}")
        logger.lifecycle(result.versionOutput.trim())
        logger.lifecycle("major version: ${result.major}")
        logger.lifecycle("app icon: ${result.appIcon.canonicalPath}")
        if (installerHostOs() == "mac") {
            logger.lifecycle("CFBundleIconFile: ${result.appIconName}")
        }
        logger.lifecycle("app icon bytes match the committed icon")
    }
}

fun buildInstallerAppImage(
    jdkHome: File,
    copiedJar: File,
    destination: File,
    work: File,
    appVersion: String,
    icon: File,
) {
    val mainClass = JarFile(copiedJar).use { jar ->
        jar.manifest?.mainAttributes?.getValue("Main-Class")
    }
    if (mainClass != bootJarMainClass) {
        throw org.gradle.api.GradleException("앱 이미지에 넣은 jar의 Main-Class가 $bootJarMainClass 이 아닙니다: $mainClass")
    }
    if (destination.exists() && !destination.deleteRecursively()) {
        throw org.gradle.api.GradleException("이전 앱 이미지를 지우지 못했습니다: ${destination.absolutePath}")
    }
    if (work.exists() && !work.deleteRecursively()) {
        throw org.gradle.api.GradleException("작업 디렉터리를 지우지 못했습니다: ${work.absolutePath}")
    }
    val input = File(work, "input")
    val runtime = File(work, "runtime")
    if (!input.mkdirs() || !destination.mkdirs()) {
        throw org.gradle.api.GradleException("앱 이미지 디렉터리를 만들지 못했습니다.")
    }
    copiedJar.copyTo(File(input, copiedJar.name))
    // jpackage 기본 jlink는 --strip-native-commands 라서 java가 빠지고, 모듈도 앱이 직접 쓰는 것만 남긴다.
    // 모드 로더 설치 jar는 이 런타임의 java로 실행하므로 JDK 모듈 전체를 남긴다.
    runCaptured(
        listOf(
            jdkBin(jdkHome, "jlink").absolutePath,
            "--module-path",
            File(jdkHome, "jmods").absolutePath,
            "--add-modules",
            "ALL-MODULE-PATH",
            "--output",
            runtime.absolutePath,
        ),
    )
    val command = mutableListOf(
        jdkBin(jdkHome, "jpackage").absolutePath,
        "--type", "app-image",
        "--dest", destination.absolutePath,
        "--name", installerAppName,
        "--vendor", "RobotMC",
        "--app-version", appVersion,
        "--input", input.absolutePath,
        "--main-jar", copiedJar.name,
        "--main-class", bootJarMainClass,
        "--runtime-image", runtime.absolutePath,
        "--description", installerAppName,
    )
    if (installerHostOs() == "mac") {
        command += listOf("--mac-package-identifier", "com.sysbot32.robotmc.installer")
    }
    if (!icon.isFile) {
        throw org.gradle.api.GradleException("앱 아이콘 파일이 없습니다: ${icon.absolutePath}")
    }
    command += listOf("--icon", icon.absolutePath)
    runCaptured(command)
}

data class InstallerImageCheck(
    val launcher: File,
    val javaExecutable: File,
    val versionOutput: String,
    val major: Int,
    val appIcon: File,
    val appIconName: String,
)

fun verifyInstallerAppImage(imageDir: File, copiedJar: File, jdkHome: File, iconFile: File): InstallerImageCheck {
    val paths = installerImagePaths(imageDir)
    if (!paths.launcher.isFile) {
        throw org.gradle.api.GradleException("런처가 없습니다: ${paths.launcher.absolutePath}")
    }
    if (installerHostOs() != "windows" && !paths.launcher.canExecute()) {
        throw org.gradle.api.GradleException("런처를 실행할 수 없습니다: ${paths.launcher.absolutePath}")
    }
    if (!paths.javaExecutable.isFile) {
        throw org.gradle.api.GradleException("런타임 java가 없습니다: ${paths.javaExecutable.absolutePath}")
    }
    val imageRoot = paths.image.canonicalFile
    val javaCanonical = paths.javaExecutable.canonicalFile
    if (!javaCanonical.isInside(imageRoot) || !paths.launcher.canonicalFile.isInside(imageRoot)) {
        throw org.gradle.api.GradleException("런처 또는 java가 이미지 밖에 있습니다.")
    }
    val launchers = paths.launcher.parentFile.listFiles()?.filter { it.isFile }.orEmpty()
    if (launchers.size != 1 || launchers.single().canonicalFile != paths.launcher.canonicalFile) {
        throw org.gradle.api.GradleException("진입 런처가 하나가 아닙니다: ${launchers.map { it.name }}")
    }
    // 해시는 이 빌드가 이미지에 복사한 jar와 비교한다. Ubuntu에서 올린 여섯 타깃 jar와 같아야 하는 검사가 아니다.
    val jars = paths.appDir.listFiles { file -> file.isFile && file.extension == "jar" }?.toList().orEmpty()
    if (jars.size != 1 || jars.single().name != copiedJar.name || sha256(jars.single()) != sha256(copiedJar)) {
        throw org.gradle.api.GradleException("이미지 안의 jar가 앱 이미지에 넣은 jar와 다릅니다.")
    }
    val cfg = paths.appDir.listFiles { file -> file.isFile && file.extension == "cfg" }?.toList().orEmpty()
    if (cfg.size != 1 || bootJarMainClass !in cfg.single().readText() || copiedJar.name !in cfg.single().readText()) {
        throw org.gradle.api.GradleException("런처 설정이 앱 이미지에 넣은 jar를 가리키지 않습니다.")
    }
    val versionOutput = runCaptured(listOf(javaCanonical.absolutePath, "-version"))
    val major = javaMajor(versionOutput)
    if (major != installerJdkMajor) {
        throw org.gradle.api.GradleException("Java 메이저 버전이 ${installerJdkMajor}가 아닙니다: $versionOutput")
    }
    val bundledModules = moduleNames(javaCanonical)
    val jdkModules = moduleNames(jdkBin(jdkHome, "java"))
    if (bundledModules != jdkModules) {
        throw org.gradle.api.GradleException(
            "런타임 모듈이 JDK와 다릅니다. missing=${jdkModules - bundledModules} extra=${bundledModules - jdkModules}",
        )
    }
    val probe = execProbeJar(javaCanonical)
    val probeHome = probe.lineSequence().first { it.startsWith("probe.java.home=") }
        .removePrefix("probe.java.home=")
    val probeVersion = probe.lineSequence().first { it.startsWith("probe.java.version=") }
        .removePrefix("probe.java.version=")
    if (javaMajor("version \"$probeVersion\"") != installerJdkMajor) {
        throw org.gradle.api.GradleException("jar 실행의 Java 버전이 ${installerJdkMajor}가 아닙니다: $probeVersion")
    }
    val javaName = if (installerHostOs() == "windows") "java.exe" else "java"
    val probeJava = File(probeHome, "bin/$javaName").canonicalFile
    if (probeJava != javaCanonical) {
        throw org.gradle.api.GradleException("java.home의 java가 이미지 런타임과 다릅니다: $probeJava")
    }
    val installedIcon = verifyHostAppIcon(paths.image, iconFile)
    return InstallerImageCheck(
        paths.launcher,
        javaCanonical,
        versionOutput,
        major,
        installedIcon.file,
        installedIcon.name,
    )
}

data class InstalledAppIcon(
    val file: File,
    val name: String,
)

fun verifyHostAppIcon(image: File, iconFile: File): InstalledAppIcon {
    if (!iconFile.isFile) {
        throw org.gradle.api.GradleException("커밋된 앱 아이콘이 없습니다: ${iconFile.absolutePath}")
    }
    val installed = when (installerHostOs()) {
        "mac" -> verifyMacAppIcon(image, iconFile)
        "windows" -> verifyCopiedAppIcon(image, iconFile, "ico")
        else -> verifyCopiedAppIcon(image, iconFile, "png")
    }
    if (!installed.file.canonicalFile.isInside(image.canonicalFile)) {
        throw org.gradle.api.GradleException("앱 아이콘이 이미지 밖에 있습니다: ${installed.file.absolutePath}")
    }
    if (sha256(installed.file) != sha256(iconFile)) {
        throw org.gradle.api.GradleException("앱 아이콘이 커밋된 파일과 다릅니다: ${installed.file.absolutePath}")
    }
    return installed
}

fun verifyMacAppIcon(app: File, iconFile: File): InstalledAppIcon {
    val plist = File(app, "Contents/Info.plist")
    if (!plist.isFile) {
        throw org.gradle.api.GradleException("Info.plist가 없습니다: ${plist.absolutePath}")
    }
    val name = Regex("""<key>CFBundleIconFile</key>\s*<string>([^<]+)</string>""")
        .find(plist.readText())
        ?.groupValues
        ?.get(1)
        ?.trim()
        .orEmpty()
    if (name.isEmpty()) {
        throw org.gradle.api.GradleException("CFBundleIconFile이 없습니다.")
    }
    val resources = File(app, "Contents/Resources")
    val named = when {
        File(resources, name).isFile -> File(resources, name)
        !name.endsWith(".icns") && File(resources, "$name.icns").isFile -> File(resources, "$name.icns")
        else -> File(resources, name)
    }
    if (!named.isFile || named.extension != "icns") {
        throw org.gradle.api.GradleException("CFBundleIconFile이 가리키는 icns가 없습니다: $name")
    }
    if (sha256(named) != sha256(iconFile)) {
        throw org.gradle.api.GradleException("앱 아이콘이 기본 Java 아이콘이거나 커밋된 icns와 다릅니다: ${named.absolutePath}")
    }
    return InstalledAppIcon(named, name)
}

fun verifyCopiedAppIcon(image: File, iconFile: File, extension: String): InstalledAppIcon {
    val expected = sha256(iconFile)
    val matches = image.walkTopDown()
        .filter { it.isFile && it.extension.equals(extension, ignoreCase = true) && sha256(it) == expected }
        .toList()
    if (matches.isEmpty()) {
        throw org.gradle.api.GradleException("이미지 안에 커밋된 앱 아이콘과 같은 $extension 파일이 없습니다.")
    }
    val chosen = matches.firstOrNull { it.name == iconFile.name } ?: matches.first()
    return InstalledAppIcon(chosen, chosen.name)
}

data class InstallerImagePaths(
    val image: File,
    val launcher: File,
    val javaExecutable: File,
    val appDir: File,
)

fun installerImagePaths(destination: File): InstallerImagePaths {
    val name = installerAppName
    return when (installerHostOs()) {
        "mac" -> {
            val image = File(destination, "$name.app")
            InstallerImagePaths(
                image = image,
                launcher = File(image, "Contents/MacOS/$name"),
                javaExecutable = File(image, "Contents/runtime/Contents/Home/bin/java"),
                appDir = File(image, "Contents/app"),
            )
        }
        "windows" -> {
            val image = File(destination, name)
            InstallerImagePaths(
                image = image,
                launcher = File(image, "$name.exe"),
                javaExecutable = File(image, "runtime/bin/java.exe"),
                appDir = File(image, "app"),
            )
        }
        else -> {
            val image = File(destination, name)
            InstallerImagePaths(
                image = image,
                launcher = File(image, "bin/$name"),
                javaExecutable = File(image, "lib/runtime/bin/java"),
                appDir = File(image, "lib/app"),
            )
        }
    }
}

fun runtimeDesktopRedirectJars(): Set<File> {
    return project.configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
        .filter { artifact ->
            val id = artifact.moduleVersion.id
            id.group == "org.jetbrains.compose.runtime" &&
                (id.name == "runtime-desktop" || id.name == "runtime-saveable-desktop")
        }
        .map { it.file }
        .toSet()
}

// compose.desktop.currentOs의 전이 의존성만 남기고, 나머지 desktop-jvm 타깃의 Skiko는 뺀다.
fun foreignSkikoFiles(): Set<File> {
    val desktopDependencies = project.configurations.runtimeClasspath.get()
        .resolvedConfiguration
        .firstLevelModuleDependencies
        .filter { dependency ->
            dependency.moduleGroup == "org.jetbrains.compose.desktop" &&
                dependency.moduleName.startsWith("desktop-jvm-")
        }
    val host = desktopDependencies.singleOrNull { dependency ->
        hostDesktopCoordinate == "${dependency.moduleGroup}:${dependency.moduleName}:${dependency.moduleVersion}"
    } ?: throw org.gradle.api.GradleException(
        "compose.desktop.currentOs($hostDesktopCoordinate)가 릴리스 classpath에 없습니다.",
    )
    val hostFiles = host.allModuleArtifacts.map { it.file }.toSet()
    return desktopDependencies
        .filter { it.moduleName != host.moduleName }
        .flatMap { it.allModuleArtifacts }
        .map { it.file }
        .filter { it !in hostFiles }
        .toSet()
}

fun installerHostOs(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.startsWith("mac") || os.startsWith("darwin") -> "mac"
        os.startsWith("windows") -> "windows"
        else -> "linux"
    }
}

fun jdkBin(jdkHome: File, name: String): File {
    val fileName = if (installerHostOs() == "windows") "$name.exe" else name
    return File(jdkHome, "bin/$fileName")
}

fun File.isInside(root: File): Boolean {
    val child = canonicalPath
    val parent = root.canonicalPath
    return child == parent || child.startsWith(parent + File.separator)
}

fun javaMajor(versionOutput: String): Int {
    val quoted = Regex("""version "([^"]+)"""").find(versionOutput)?.groupValues?.get(1)
        ?: throw org.gradle.api.GradleException("java 버전 문자열을 찾지 못했습니다: $versionOutput")
    val parts = quoted.split('.', '_', '-')
    val first = parts[0].toInt()
    return if (first == 1 && parts.size >= 2) parts[1].toInt() else first
}

fun moduleNames(javaExecutable: File): Set<String> {
    return runCaptured(listOf(javaExecutable.absolutePath, "--list-modules"))
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.substringBefore('@').substringBefore(' ') }
        .toSet()
}

fun execProbeJar(javaExecutable: File): String {
    val javac = File(javaExecutable.parentFile, if (installerHostOs() == "windows") "javac.exe" else "javac")
    val jarTool = File(javaExecutable.parentFile, if (installerHostOs() == "windows") "jar.exe" else "jar")
    if (!javac.isFile || !jarTool.isFile) {
        throw org.gradle.api.GradleException("런타임에 javac 또는 jar가 없습니다: ${javaExecutable.parent}")
    }
    val dir = Files.createTempDirectory("installer-runtime-probe").toFile()
    try {
        val source = File(dir, "RuntimeProbe.java")
        source.writeText(
            """
            public class RuntimeProbe {
                public static void main(String[] args) {
                    System.out.println("probe.java.home=" + System.getProperty("java.home"));
                    System.out.println("probe.java.version=" + System.getProperty("java.version"));
                }
            }
            """.trimIndent() + "\n",
        )
        val classes = File(dir, "classes")
        if (!classes.mkdirs()) {
            throw org.gradle.api.GradleException("probe 디렉터리를 만들지 못했습니다.")
        }
        runCaptured(listOf(javac.absolutePath, "-d", classes.absolutePath, source.absolutePath))
        val jarFile = File(dir, "probe.jar")
        runCaptured(
            listOf(
                jarTool.absolutePath,
                "cfe",
                jarFile.absolutePath,
                "RuntimeProbe",
                "-C",
                classes.absolutePath,
                "RuntimeProbe.class",
            ),
        )
        return runCaptured(listOf(javaExecutable.absolutePath, "-jar", jarFile.absolutePath))
    } finally {
        dir.deleteRecursively()
    }
}

fun sha256(file: File): String {
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

fun runCaptured(command: List<String>): String {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()
    if (code != 0) {
        throw org.gradle.api.GradleException("실패 ($code): ${command.joinToString(" ")}\n$output")
    }
    return output
}
