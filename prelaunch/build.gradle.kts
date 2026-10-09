plugins {
    kotlin("jvm") version "2.3.21"
}

// 게임 JVM 에 올리는 에이전트. Spring, Compose, 설치기 로거는 이 모듈에 없다.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.yaml:snakeyaml:2.4")
}

tasks.register<Jar>("agentJar") {
    group = "build"
    description = "게임 JVM 에 넣는 에이전트 JAR. Premain-Class 만 있다."
    dependsOn(tasks.classes)
    archiveFileName.set("robotmc-prelaunch-agent.jar")
    destinationDirectory.set(layout.buildDirectory.dir("agent"))
    manifest {
        attributes(
            "Premain-Class" to "com.sysbot32.robotmc.installer.prelaunch.PrelaunchAgent",
        )
    }
    from(sourceSets.named("main").get().output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.isFile && it.extension == "jar" }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
        exclude("module-info.class")
    }
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
}
