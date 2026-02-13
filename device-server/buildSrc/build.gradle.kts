plugins {
  `kotlin-dsl`
  `java-gradle-plugin`
}

group = "com.badoo.graalvm"
version = "1.0.0"

repositories {
  mavenCentral()
}

dependencies {
  implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.10")
  implementation("com.github.javaparser:javaparser-core:3.26.3")
  implementation("com.google.code.gson:gson:2.11.0")
}

gradlePlugin {
  plugins {
    create("graalvmMetadataGenerator") {
      id = "com.badoo.graalvm.metadata-generator"
      implementationClass = "com.badoo.graalvm.GraalVMMetadataGeneratorPlugin"
      displayName = "GraalVM Reflection Metadata Generator"
      description = "Generates GraalVM native-image reflection metadata by parsing Kotlin and Java source files"
    }
  }
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

kotlin {
  jvmToolchain(25)
}
