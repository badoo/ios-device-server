package com.badoo.graalvm

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.TypeDeclaration
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.*
import java.io.File

/**
 * Gradle task that parses Kotlin and Java source files using AST parsing
 * and generates GraalVM reflection metadata.
 */
abstract class GenerateMetadataTask : DefaultTask() {

  @get:InputDirectory
  abstract val sourceDir: DirectoryProperty

  @get:OutputFile
  abstract val outputFile: RegularFileProperty

  @get:OutputFile
  abstract val reportFile: RegularFileProperty

  @TaskAction
  fun generate() {
    val srcDir = sourceDir.get().asFile
    val metadataFile = outputFile.get().asFile
    val backupFile = File(metadataFile.parentFile, "${metadataFile.name}.backup")
    val report = reportFile.get().asFile

    println("\n${"=".repeat(70)}")
    println("GraalVM Reflection Metadata Generation (AST-based)")
    println("=".repeat(70))

    val discoveredTypes = mutableSetOf<String>()

    // Setup Kotlin environment for PSI parsing
    val disposable = Disposer.newDisposable()
    try {
      val configuration = CompilerConfiguration()
      val environment = KotlinCoreEnvironment.createForProduction(
        disposable,
        configuration,
        EnvironmentConfigFiles.JVM_CONFIG_FILES
      )
      val project = environment.project

      // Process all source files
      srcDir.walkTopDown()
        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
        .forEach { file ->
          try {
            when (file.extension) {
              "kt" -> parseKotlinFile(file, project, discoveredTypes)
              "java" -> parseJavaFile(file, discoveredTypes)
            }
          } catch (e: Exception) {
            println("⚠️  Error parsing ${file.name}: ${e.message}")
          }
        }
    } finally {
      Disposer.dispose(disposable)
    }

    // Merge with existing metadata
    val (existingTypes, newTypes) = mergeWithExistingMetadata(metadataFile, backupFile, discoveredTypes)

    // Write report
    writeReport(report, newTypes)

    // Print summary
    printSummary(discoveredTypes.size, existingTypes.size, newTypes.size, metadataFile, backupFile, report)
  }

  private fun parseKotlinFile(
    file: File,
    project: org.jetbrains.kotlin.com.intellij.openapi.project.Project,
    types: MutableSet<String>
  ) {
    val content = file.readText()
    val virtualFile = LightVirtualFile(file.name, KotlinFileType.INSTANCE, content)
    val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: return

    val packageName = psiFile.packageFqName.asString()
    if (packageName.isEmpty()) {
      println("⚠️  No package in ${file.name}")
      return
    }

    psiFile.declarations.forEach { declaration ->
      extractKotlinTypes(declaration, packageName, types)
    }
  }

  private fun extractKotlinTypes(
    declaration: KtDeclaration,
    packageName: String,
    types: MutableSet<String>,
    parentFqn: String = ""
  ) {
    when (declaration) {
      is KtClassOrObject -> {
        val name = declaration.name ?: return
        val fqn = if (parentFqn.isEmpty()) {
          "$packageName.$name"
        } else {
          "$parentFqn\$$name"
        }
        types.add(fqn)

        // Process nested declarations
        declaration.declarations.forEach { nested ->
          extractKotlinTypes(nested, packageName, types, fqn)
        }

        // Process companion object
        if (declaration is KtClass) {
          declaration.companionObjects.forEach { companion ->
            val companionName = companion.name ?: "Companion"
            types.add("$fqn\$$companionName")

            // Process companion object's nested declarations
            companion.declarations.forEach { nested ->
              extractKotlinTypes(nested, packageName, types, "$fqn\$$companionName")
            }
          }
        }
      }

      is KtObjectDeclaration -> {
        if (!declaration.isCompanion()) {
          val name = declaration.name ?: return
          val fqn = if (parentFqn.isEmpty()) {
            "$packageName.$name"
          } else {
            "$parentFqn\$$name"
          }
          types.add(fqn)

          // Process nested declarations
          declaration.declarations.forEach { nested ->
            extractKotlinTypes(nested, packageName, types, fqn)
          }
        }
      }
    }
  }

  private fun parseJavaFile(file: File, types: MutableSet<String>) {
    val compilationUnit = StaticJavaParser.parse(file)
    val packageName = compilationUnit.packageDeclaration.map { it.nameAsString }.orElse("")

    if (packageName.isEmpty()) {
      println("⚠️  No package in ${file.name}")
      return
    }

    compilationUnit.types.forEach { typeDecl ->
      extractJavaTypes(typeDecl, packageName, types)
    }
  }

  private fun extractJavaTypes(
    typeDecl: TypeDeclaration<*>,
    packageName: String,
    types: MutableSet<String>,
    parentFqn: String = ""
  ) {
    val typeName = typeDecl.nameAsString
    val fqn = if (parentFqn.isEmpty()) {
      "$packageName.$typeName"
    } else {
      "$parentFqn\$$typeName"
    }
    types.add(fqn)

    // Process nested types
    typeDecl.members.forEach { member ->
      if (member is TypeDeclaration<*>) {
        extractJavaTypes(member, packageName, types, fqn)
      }
    }
  }

  private fun mergeWithExistingMetadata(
    metadataFile: File,
    backupFile: File,
    discoveredTypes: Set<String>
  ): Pair<Set<String>, Set<String>> {
    val existingTypes = mutableSetOf<String>()
    val existingMetadata: JsonObject

    if (metadataFile.exists()) {
      // Backup existing file
      metadataFile.copyTo(backupFile, overwrite = true)
      println("📦 Backed up: ${backupFile.name}")

      // Parse existing JSON
      existingMetadata = JsonParser.parseReader(metadataFile.reader()).asJsonObject

      // Extract existing reflection types
      val reflectionArray = existingMetadata.getAsJsonArray("reflection") ?: JsonArray()
      reflectionArray.forEach { entry ->
        val type = entry.asJsonObject.get("type")?.asString
        if (type != null) {
          existingTypes.add(type)
        }
      }
    } else {
      // Create new metadata structure
      existingMetadata = JsonObject()
      existingMetadata.add("reflection", JsonArray())
      existingMetadata.add("resources", JsonArray())
      println("📝 Creating new metadata file")
    }

    // Normalize existing entries and add new types
    val reflectionArray = JsonArray()
    val processedTypes = mutableSetOf<String>()

    // Process existing entries
    val existingReflection = existingMetadata.getAsJsonArray("reflection") ?: JsonArray()
    existingReflection.forEach { element ->
      val entry = element.asJsonObject
      val type = entry.get("type")?.asString

      if (type != null) {
        processedTypes.add(type)

        // Normalize com.badoo.automation.deviceserver types to use allPublic*
        // This exposes only public APIs (not private implementation details)
        if (type.startsWith("com.badoo.automation.deviceserver")) {
          val normalizedEntry = JsonObject()
          normalizedEntry.addProperty("type", type)
          normalizedEntry.addProperty("allPublicConstructors", true)
          normalizedEntry.addProperty("allPublicMethods", true)
          normalizedEntry.addProperty("allPublicFields", true)
          normalizedEntry.addProperty("queryAllPublicConstructors", true)
          normalizedEntry.addProperty("queryAllPublicMethods", true)
          reflectionArray.add(normalizedEntry)
        } else {
          // Keep other types as-is (libraries, frameworks)
          reflectionArray.add(entry)
        }
      }
    }

    // Add new types (public-only for application code)
    val newTypes = discoveredTypes - processedTypes
    newTypes.sorted().forEach { type ->
      val entry = JsonObject()
      entry.addProperty("type", type)
      entry.addProperty("allPublicConstructors", true)
      entry.addProperty("allPublicMethods", true)
      entry.addProperty("allPublicFields", true)
      entry.addProperty("queryAllPublicConstructors", true)
      entry.addProperty("queryAllPublicMethods", true)
      reflectionArray.add(entry)
    }

    existingMetadata.add("reflection", reflectionArray)

    // Write with 2-space indentation
    metadataFile.parentFile.mkdirs()
    val gson = GsonBuilder()
      .setPrettyPrinting()
      .disableHtmlEscaping()  // Don't escape < and > to \u003c and \u003e
      .create()
    val json = gson.toJson(existingMetadata)
    // Gson uses 2-space indentation by default
    metadataFile.writeText(json)

    return Pair(existingTypes, newTypes)
  }

  private fun writeReport(reportFile: File, newTypes: Set<String>) {
    val reportArray = JsonArray()
    newTypes.sorted().forEach { type ->
      val entry = JsonObject()
      entry.addProperty("type", type)
      entry.addProperty("allPublicConstructors", true)
      entry.addProperty("allPublicMethods", true)
      entry.addProperty("allPublicFields", true)
      entry.addProperty("queryAllPublicConstructors", true)
      entry.addProperty("queryAllPublicMethods", true)
      reportArray.add(entry)
    }

    reportFile.parentFile.mkdirs()
    val gson = GsonBuilder()
      .setPrettyPrinting()
      .disableHtmlEscaping()
      .create()
    reportFile.writeText(gson.toJson(reportArray))
  }

  private fun printSummary(
    totalDiscovered: Int,
    existingCount: Int,
    newCount: Int,
    metadataFile: File,
    backupFile: File,
    reportFile: File
  ) {
    println()
    println("📊 Summary:")
    println("  Total types discovered: $totalDiscovered")
    println("  Existing types:         $existingCount")
    println("  New types added:        $newCount")
    println()
    println("📁 Files:")
    println("  Updated:  ${metadataFile.absolutePath}")
    println("  Backup:   ${backupFile.absolutePath}")
    println("  Report:   ${reportFile.absolutePath}")
    println()

    if (newCount > 0) {
      println("✅ Added $newCount new type(s) to reflection metadata")
    } else {
      println("✅ Metadata is up to date - no new types found")
    }

    println()
    println("Next steps:")
    println("  1. Review: diff ${backupFile.name} ${metadataFile.name}")
    println("  2. Rebuild: ./gradlew nativeCompile")
    println("=".repeat(70))
  }
}
