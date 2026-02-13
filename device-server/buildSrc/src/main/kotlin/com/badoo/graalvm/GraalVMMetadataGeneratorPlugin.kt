package com.badoo.graalvm

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Gradle plugin that generates GraalVM native-image reflection metadata
 * by parsing Kotlin and Java source files using AST parsing.
 */
class GraalVMMetadataGeneratorPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    // Register the task
    val generateTask = project.tasks.register("generateGraalVMMetadata", GenerateMetadataTask::class.java) {
      group = "graalvm"
      description = "Generate reflection metadata for GraalVM native-image by parsing source files"

      // Set default values
      sourceDir.convention(project.layout.projectDirectory.dir("src/main"))
      outputFile.convention(
        project.layout.projectDirectory.file("src/main/resources/META-INF/native-image/reachability-metadata.json")
      )
      reportFile.convention(project.layout.buildDirectory.file("graalvm-reflection-types.json"))
    }

    // Automatically run before tasks that need the metadata
    project.tasks.configureEach {
      when (name) {
        "nativeCompile", "processResources" -> {
          dependsOn(generateTask)
        }
      }
    }
  }
}
