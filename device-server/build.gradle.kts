plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
}

group = "com.badoo.automation"
version = "2.0-SNAPSHOT"

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
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.logback.classic)


    implementation("org.apache.commons:commons-configuration2:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.3")

    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")

    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("com.zaxxer:nuprocess:2.0.6")


    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.ktor.server.test.host)

    testImplementation("org.mockito:mockito-core:2.18.0")
    testImplementation("org.mockito:mockito-inline:2.18.0")

    testImplementation("com.nhaarman:mockito-kotlin:1.5.0")
    testImplementation("org.hamcrest:hamcrest-junit:2.0.0.0")
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
