package dev.yume.packer

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** 从同一份上游数据生成查找表，避免首次绘制仓库行或代码时读取和解析 JSON。 */
@CacheableTask
abstract class GenerateLanguageTables : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val colors: RegularFileProperty
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val grammars: RegularFileProperty
    @get:OutputDirectory abstract val output: DirectoryProperty

    private fun literal(value: String): String = JsonOutput.toJson(value).replace("$", "\\$")

    @TaskAction
    fun generate() {
        val dir = output.get().asFile.apply { mkdirs() }
        val parser = JsonSlurper()
        val palette = parser.parse(colors.get().asFile, "UTF-8") as Map<*, *>
        dir.resolve("GithubLanguageArgb.kt").bufferedWriter().use { out ->
            out.appendLine("package moe.yumeyuka.yumehub.ui.theme")
            out.appendLine("internal fun githubLanguageArgb(name: String): Int = when (name) {")
            for ((name, rgb) in palette) {
                require(rgb is String && rgb.matches(Rgb))
                out.appendLine("    ${literal(name as String)} -> 0xff${rgb.drop(1)}.toInt()")
            }
            out.appendLine("    else -> 0\n}")
        }
        val index = parser.parse(grammars.get().asFile, "UTF-8") as Map<*, *>
        dir.resolve("GithubGrammarTable.kt").bufferedWriter().use { out ->
            out.appendLine("package moe.yumeyuka.yumehub.ui.component")
            out.appendLine("internal fun loadGrammarTable(")
            for (map in listOf("byScope", "byAlias", "bySuffix", "byFilename")) {
                out.appendLine("    $map: MutableMap<String, GithubGrammar>,")
            }
            out.appendLine(") {")
            for ((i, entry) in (index["languages"] as List<*>).withIndex()) {
                val row = entry as Map<*, *>
                val name = row["name"] as String?
                val scope = literal(row["scopeName"] as String)
                val path = literal(row["grammar"] as String)
                out.appendLine(
                    "    val g$i = GithubGrammar(${name?.let(::literal)}, $scope, $path)",
                )
                out.appendLine("    byScope[$scope] = g$i")
                if (name == null) continue
                out.appendLine("    byAlias[${literal(name)}] = g$i")
                for ((key, map) in
                    listOf(
                        "aliases" to "byAlias",
                        "extensions" to "bySuffix",
                        "filenames" to "byFilename",
                    )) {
                    for (value in (row[key] as List<*>?).orEmpty()) {
                        out.appendLine(
                            "    $map.putIfAbsent(${literal((value as String).lowercase())}, g$i)",
                        )
                    }
                }
            }
            out.appendLine("}")
        }
    }

    private companion object {
        val Rgb = Regex("#[0-9a-fA-F]{6}")
    }
}
