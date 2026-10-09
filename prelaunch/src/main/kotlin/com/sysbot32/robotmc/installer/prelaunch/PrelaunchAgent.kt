package com.sysbot32.robotmc.installer.prelaunch

import java.lang.instrument.Instrumentation
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 게임 main 보다 먼저 실행된다.
 * 이 JAR 만 게임 JVM 에 올라간다. Spring, Compose, 설치기 Logback 은 여기 두지 않는다.
 * 같으면 반환하고, 다르면 설치기를 실행한 뒤 게임 JVM 을 끝낸다.
 */
class PrelaunchAgent private constructor() {
    companion object {
        /**
         * 런처 충돌 제보에 찍히는 종료 코드.
         * 0은 설치기를 실행한 정상 종료라 제보 창이 뜨지 않는다.
         * 1은 검사 설정을 읽지 못했다.
         * 2는 비교를 끝내지 못했고 설치기도 시작하지 못했다.
         * 3은 구성이 달랐는데 설치기를 시작하지 못했다.
         */
        const val EXIT_INSTALLER_STARTED = 0
        const val EXIT_OPTIONS_UNREADABLE = 1
        const val EXIT_CHECK_FAILED = 2
        const val EXIT_INSTALLER_NOT_STARTED = 3

        @JvmStatic
        fun premain(agentArgs: String?, instrumentation: Instrumentation) {
            val options = try {
                AgentOptions.read(Path.of(agentArgs ?: ""))
            } catch (exception: Exception) {
                System.err.println("실행 전 검사를 읽지 못해서 게임을 시작하지 않아요.")
                System.err.flush()
                Runtime.getRuntime().halt(EXIT_OPTIONS_UNREADABLE)
                return
            }
            val check = try {
                val yaml = loadRemoteConfig(options.manifestUrl, options.cacheFile, options.bundledFile)
                checkLaunchConfig(options.minecraftDirectory, options.profileKey, yaml)
            } catch (exception: Exception) {
                System.err.println("실행 전 검사를 끝내지 못해서 설치기를 실행해요.")
                null
            }
            // 디스크 구성은 맞는데 런처가 예전 로더로 JVM 을 띄운 경우. 설치는 다시 하지 않는다.
            val restartLauncher = check != null &&
                check.match &&
                loaderLaunchDiffers(currentRunningLoaderIds(), check.launchedLoaderId)
            if (check != null && check.match && !restartLauncher) {
                return
            }
            val command = if (restartLauncher) {
                System.err.println(LAUNCHER_RESTART_DETAIL)
                options.installerCommand + LAUNCHER_RESTART_ARGUMENT
            } else {
                if (check != null) {
                    System.err.println("로더 또는 모드가 원격 구성과 달라서 설치기를 실행해요.")
                }
                options.installerCommand
            }
            val installerStarted = try {
                val process = ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                process.waitFor(5, TimeUnit.SECONDS)
                true
            } catch (exception: Exception) {
                System.err.println("설치기를 실행하지 못했어요.")
                false
            }
            System.err.flush()
            val exitCode = when {
                installerStarted -> EXIT_INSTALLER_STARTED
                check == null -> EXIT_CHECK_FAILED
                else -> EXIT_INSTALLER_NOT_STARTED
            }
            Runtime.getRuntime().halt(exitCode)
        }

        private fun currentRunningLoaderIds(): List<String> {
            val arguments = ProcessHandle.current().info().arguments().orElse(emptyArray()).toList()
            return runningLoaderIds(System.getProperty("java.class.path"), arguments)
        }
    }
}
