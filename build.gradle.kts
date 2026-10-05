plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.spring") version "2.2.20"
    kotlin("plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.12.1"
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("edu.sc.seis.launch4j") version "3.0.6"
}

group = "com.sysbot32"
/**
 * 새로운 시작 - 메이저
 * 기존 사용자 업데이트 필요 - 마이너
 * 기존 사용자 업데이트 필요 없음 - 패치
 */
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
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

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.7")
    implementation("org.apache.commons:commons-exec:1.4.0")
    implementation("dev.dewy:nbt:1.5.1")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

compose.desktop {
    application {
        mainClass = "com.sysbot32.robotmc.installer.RobotmcInstallerApplicationKt"
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
