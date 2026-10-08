package com.sysbot32.robotmc.installer.mod.loader

import com.sysbot32.robotmc.installer.InstallService
import com.sysbot32.robotmc.installer.config.InstallerProperties
import com.sysbot32.robotmc.installer.launcher.LauncherService
import com.sysbot32.robotmc.installer.progress.ProgressService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.exec.DefaultExecutor
import org.apache.commons.exec.ExecuteWatchdog
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

private val log = KotlinLogging.logger { }

/**
 * 설치기가 라이브러리를 받는 동안 끝날 수 있게 여유를 둔다.
 * 이 시간이 지나도 프로세스가 살아 있으면 watchdog가 끝낸다.
 */
val MOD_LOADER_PROCESS_TIMEOUT: Duration = Duration.ofMinutes(30)

fun executeModLoaderCommand(
    arguments: List<String>,
    timeout: Duration = MOD_LOADER_PROCESS_TIMEOUT,
    javaHome: String = System.getProperty("java.home"),
    osName: String = System.getProperty("os.name"),
) {
    val command = modLoaderProcessCommand(arguments, javaHome, osName)
    log.info { "${modLoaderCommandLine(command)}" }
    val executor = DefaultExecutor.builder().get()
    executor.watchdog = ExecuteWatchdog.builder().setTimeout(timeout).get()
    executor.execute(modLoaderCommandLine(command))
}

/**
 * 결정의 첫 자리는 실행 파일 자리다. 그 문자열은 보지 않고, 이 프로세스를 띄운 JVM으로 바꾼다.
 */
fun modLoaderProcessCommand(
    arguments: List<String>,
    javaHome: String = System.getProperty("java.home"),
    osName: String = System.getProperty("os.name"),
): List<String> {
    return listOf(modLoaderJavaExecutable(javaHome, osName)) + arguments.drop(1)
}

fun modLoaderJavaExecutable(javaHome: String, osName: String): String {
    val windows = osName.lowercase().startsWith("windows")
    val separator = if (windows) "\\" else "/"
    val fileName = if (windows) "java.exe" else "java"
    return javaHome.trimEnd('\\', '/') + separator + "bin" + separator + fileName
}

@Service
class ModLoaderInstallService(
    private val launcherService: LauncherService,
    private val restClient: RestClient,
    private val installerProperties: InstallerProperties,
    private val progressService: ProgressService,
) : InstallService {
    /**
     * installer JAR를 두는 디렉터리. 기본값은 홈 캐시이고, 테스트는 임시 디렉터리를 넣는다.
     */
    internal var installerDirectory: Path = defaultModLoaderInstallerDirectory()

    override val order: Int
        get() = 10

    override fun install() {
        val loader = this.installerProperties.mod?.loader
        log.info { "Minecraft ${this.installerProperties.minecraft.version}" }
        log.info { "${loader?.type?.displayName} ${loader?.version}" }
        val decision = decideModLoaderInstall(
            type = loader?.type,
            loaderVersion = loader?.version,
            minecraftVersion = this.installerProperties.minecraft.version,
            minecraftDirectory = this.installerProperties.minecraft.directory,
            installOptions = loader?.installOptions.orEmpty(),
            profiles = this.launcherService.getProfiles(),
            installerDirectory = this.installerDirectory,
        )
        val installerPath = decision.installerJar
        if (!Files.exists(installerPath)) {
            this.progressService.setStatus("모드 로더 설치 프로그램 다운로드 중...")
            this.restClient.get()
                .uri(decision.installerUrl)
                .retrieve()
                .toEntity(ByteArray::class.java)
                .body?.let { bytes ->
                    installerPath.parent?.let { parent -> Files.createDirectories(parent) }
                    Files.write(installerPath, bytes)
                }
        }
        this.progressService.step("모드 로더 설치 중...")
        if (decision.runInstaller) {
            executeModLoaderCommand(decision.arguments)
        }
        this.progressService.step()
    }

    override fun uninstall() {
        this.progressService.setStatus("모드 로더 제거 중...")
        this.progressService.step(-2)
    }
}
