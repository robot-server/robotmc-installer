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
        @JvmStatic
        fun premain(agentArgs: String?, instrumentation: Instrumentation) {
            val options = try {
                AgentOptions.read(Path.of(agentArgs ?: ""))
            } catch (exception: Exception) {
                System.err.println("실행 전 검사를 읽지 못해서 게임을 시작하지 않아요.")
                System.err.flush()
                Runtime.getRuntime().halt(2)
                return
            }
            val match = try {
                val yaml = loadRemoteConfig(options.manifestUrl, options.cacheFile, options.bundledFile)
                checkLaunchConfig(options.minecraftDirectory, options.profileKey, yaml).match
            } catch (exception: Exception) {
                System.err.println("실행 전 검사를 끝내지 못해서 설치기를 실행해요.")
                false
            }
            if (match) {
                return
            }
            System.err.println("로더 또는 모드가 원격 구성과 달라서 설치기를 실행해요.")
            try {
                val process = ProcessBuilder(options.installerCommand)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                process.waitFor(5, TimeUnit.SECONDS)
            } catch (exception: Exception) {
                System.err.println("설치기를 실행하지 못했어요.")
            }
            System.err.flush()
            Runtime.getRuntime().halt(2)
        }
    }
}
