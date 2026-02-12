import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.graalvm.buildtools)
    id("com.github.ben-manes.versions") version "0.53.0"
}

group = "com.badoo.automation"
version = "2.0-SNAPSHOT-" + System.currentTimeMillis().toString()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

application {
    mainClass = "com.badoo.automation.deviceserver.ProgramKt"

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ktor.serialization.jackson)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    implementation("org.apache.commons:commons-configuration2:2.13.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")

    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.0")

    implementation("net.java.dev.jna:jna:5.18.1")
    implementation("com.zaxxer:nuprocess:3.0.0")


    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.ktor.server.test.host)

    testImplementation("org.mockito:mockito-core:5.21.0")

    testImplementation("org.mockito.kotlin:mockito-kotlin:6.0.0")
    testImplementation("org.hamcrest:hamcrest-junit:2.0.0.0")
}

tasks.jar {
    manifest {
        attributes["Implementation-Version"] = project.version
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName = "device-server"
            mainClass = "com.badoo.automation.deviceserver.ProgramKt"

            buildArgs.addAll(
                "--verbose",
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
                "-H:IncludeResources=logback.*\\.xml",
                "-H:IncludeResources=.*\\.properties",
                "-H:IncludeResources=.*\\.yml",
                "-H:IncludeResources=.*\\.yaml",
                "-H:+AddAllCharsets",
                "--enable-url-protocols=http,https",
                "--initialize-at-run-time=io.netty",
                "--initialize-at-run-time=io.ktor.network.selector.InterestSuspensionsMap",
                "--initialize-at-run-time=io.ktor.network.selector.SelectableBase",
                "-H:+AllowVMInspection",
                "-H:+ReportUnsupportedElementsAtRuntime"
            )
        }
    }
}

///**
// * For tests only
// */
//run {
//    systemProperty 'wda.bundle.path', '../ios/facebook/simulators/WebDriverAgentRunner-Runner.app'
//    systemProperty 'wda.device.bundle.path', '../ios/facebook/devices/WebDriverAgentRunner-Runner.app'
//    systemProperty 'device.server.config.path', ''
//    systemProperty 'logback.configurationFile', 'logback-test.xml'
//}
//
//test {
//    maxParallelForks = 4
//}
