//buildscript {
//    ext.kotlin_version = '2.1.20' // '1.3.72' // '1.2.41'
//    ext.kotlinx_version = '1.10.1' // '1.3.6' // '0.22.3'
//    ext.ktor_version = '3.1.2' // '1.0.1'    // '0.9.1'
//}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
}

group = "com.badoo.automation"
version = "2.0-SNAPSHOT"

application {
//    mainClass = "io.ktor.server.netty.EngineMain"
//    mainClassName = "com.badoo.automation.deviceserver.ProgramKt"
    mainClass = "com.badoo.automation.deviceserver.Program"

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

//application {
//    mainClassName = 'com.badoo.automation.deviceserver.ProgramKt'
//}

//plugins {
//    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
//    alias(libs.plugins.kotlin.jvm)
//
//    // Apply the application plugin to add support for building a CLI application in Java.
//    id 'application'
//}

repositories {
    mavenCentral()
}

//
//java {
//    toolchain {
//        languageVersion = JavaLanguageVersion.of(21)
//    }
//}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.config.yaml)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}


//dependencies {
//    implementation group: 'org.jetbrains.kotlin', name: 'kotlin-stdlib-jdk8', version: kotlin_version
//    implementation group: 'org.jetbrains.kotlin', name: 'kotlin-reflect', version: kotlin_version
//    implementation group: 'org.jetbrains.kotlinx', name: 'kotlinx-coroutines-core', version: kotlinx_version
//    implementation group: 'org.jetbrains.kotlinx', name: 'kotlinx-coroutines-jdk8', version: kotlinx_version
//    implementation group: 'org.apache.commons', name: 'commons-configuration2', version: '2.7'
//    implementation group: 'com.squareup.okhttp3', name: 'okhttp', version: '4.2.0'
//
//    //region ktor dependencies
//    implementation("io.ktor:ktor-server-netty:$ktor_version")
////            {
////        exclude group: "org.jetbrains.kotlin", module: "kotlin-stdlib-jre8"
////        exclude group: "org.jetbrains.kotlin", module: "kotlin-stdlib-jre7"
////        exclude group: "org.jetbrains.kotlinx", module: "kotlinx-coroutines-jdk8"
//////        exclude group: "org.jetbrains.kotlinx", module: "kotlinx-coroutines-io"
////    }
//    implementation("io.ktor:ktor-features:$ktor_version")
////            {
////        exclude group: "org.jetbrains.kotlin", module: "kotlin-stdlib-jre8"
////        exclude group: "org.jetbrains.kotlin", module: "kotlin-stdlib-jre7"
////        exclude group: "org.jetbrains.kotlinx", module: "kotlinx-coroutines-jdk8"
//////        exclude group: "org.jetbrains.kotlinx", module: "kotlinx-coroutines-io"
////    }
//    implementation("io.ktor:ktor-jackson:$ktor_version")
////            {
////        exclude group: "org.jetbrains.kotlin", module: "kotlin-stdlib-jre8"
////        exclude group: "org.jetbrains.kotlin", module: "kotlin-stdlib-jre7"
////        exclude group: "org.jetbrains.kotlinx", module: "kotlinx-coroutines-jdk8"
//////        exclude group: "org.jetbrains.kotlinx", module: "kotlinx-coroutines-io"
////    }
//    implementation group: 'io.ktor', name: 'ktor-auth', version: ktor_version
//    implementation group: 'org.apache.commons', name: 'commons-pool2', version: '2.8.0'
//    //endregion
//
//    //region log dependencies
//
//    implementation group: 'org.slf4j', name: 'slf4j-api', version: '1.7.25'
//    implementation group: 'ch.qos.logback', name: 'logback-classic', version: '1.2.3'
//    implementation group: 'net.logstash.logback', name: 'logstash-logback-encoder', version: '4.11'
//    //endregion
//
//    //region json dependencies
//    implementation 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.9.2'
//    //endregion
//
//    //region process management dependencies
//    implementation group: 'net.java.dev.jna', name: 'jna', version: "5.13.0"
//    implementation group: 'com.zaxxer', name: 'nuprocess', version: "1.1.3"
//    //region
//
//    //region test dependencies
//    testImplementation group: "org.jetbrains.kotlin", name: "kotlin-test"
//    testImplementation group: "org.jetbrains.kotlin", name: "kotlin-test-junit"
//    testImplementation group: 'junit', name: 'junit', version: '4.12'
//    testImplementation group: "com.nhaarman", name: "mockito-kotlin", version: "1.5.0"
//    testImplementation group: 'org.mockito', name: 'mockito-core', version: '2.18.0'
//    testImplementation group: 'org.mockito', name: 'mockito-inline', version: '2.18.0'
//    testImplementation group: 'org.hamcrest', name: 'hamcrest-junit', version: '2.0.0.0'
//    //endregion
//}

//java {
//    toolchain {
//        languageVersion = JavaLanguageVersion.of(21)
//    }
//}
//
//jar {
//    manifest {
//        attributes 'Main-Class': mainClassName
//    }
//
//    from {
//        configurations.compile.collect { it.isDirectory() ? it : zipTree(it) }
//    }
//}

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
