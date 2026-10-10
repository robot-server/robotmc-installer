plugins {
    kotlin("jvm") version "2.3.21"
}

// 설치기가 끝난 뒤에 뜨는 프로세스. Spring, Compose, 설치기 로거는 이 모듈에 없다.
// 게임 JVM이 아니라 설치기를 띄운 JVM에서 실행한다.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
}

tasks.register<Jar>("replaceJar") {
    group = "build"
    description = "설치기가 끝난 뒤에 파일을 바꾸는 JAR. 설치기 JAR을 클래스패스로 쓰지 않는다."
    dependsOn(tasks.classes)
    archiveFileName.set("installer-replace.jar")
    destinationDirectory.set(layout.buildDirectory.dir("replace"))
    manifest {
        attributes(
            "Main-Class" to "com.sysbot32.robotmc.installer.replace.InstallerReplace",
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
