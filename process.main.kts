#!/usr/bin/env kotlin

@file:Repository("https://repo.maven.apache.org/maven2/")
@file:DependsOn("org.kohsuke:github-api:1.321")
@file:DependsOn("com.lordcodes.turtle:turtle:0.9.0")

import com.lordcodes.turtle.shellRun
import org.kohsuke.github.GHRepository
import org.kohsuke.github.GitHub
import org.kohsuke.github.GitHubBuilder
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import kotlin.io.path.createTempDirectory
import kotlin.math.ceil
import kotlin.math.max

val token = System.getenv()["GITHUB_TOKEN"]
val targetOrganizationName = "unibo-oop-projects"
val surnameRegex = Regex(".*?(\\w)+.*?$")
val projectNameRegex = Regex("""(\w|\.|-)+/[Oo]{2}[Pp]-?\d+-?((\w|-|\.)+)""")
require(!token.isNullOrBlank()) {
    println("GITHUB_TOKEN is not set")
}
require(args.size == 1) {
    println("Usage: process.main.kts <repo>")
}

/**
 * Actual project ame
 */
val acronym = args[0].let {
    projectNameRegex.matchEntire(it)?.groupValues?.get(2) ?: it.substringAfterLast("-")
}
val now: LocalDateTime = LocalDateTime.now()
val month = now.format(DateTimeFormatter.ofPattern("MM")).toInt()
val year = now.format(DateTimeFormatter.ofPattern("yy")).toInt().let {
    when (month) {
        in 1..11 -> it - 1
        else -> it
    }
}

/**
 * Fork
 */
val github: GitHub = GitHubBuilder().withOAuthToken(token).build()
val repo = requireNotNull(github.getRepository(args[0]))
val disallowedRepoChars = Regex("""[^(\w|\-\.)]""")
val knownNotAuthors = listOf(
    "@github.com",
    "@users.noreply.github.com",
    "[bot]@users.noreply.github.com",
    "danilo.pianini@unibo.it",
    "danilo.pianini@gmail.com",
    "nicco.mlt@gmail.com",
    "robyonrails@gmail.com",
)
fun createFork(): GHRepository {
    println("Computing author names")
    val committers = repo.listCommits()
        .asSequence()
        .map { it.commitShortInfo.author }
        .map { it.name to it.email}
        .distinct()
        .filter { (name, email) ->
            knownNotAuthors.none { email.endsWith(it) }.also {
                println("$name -> $email is kept? $it")
            }
        }
        .map { it.first }
        .filter { it.isNotBlank() }
        .map {
            val names = it.split(Regex("(\\s|_|-|\\.)+"))
            names.joinToString(separator = "") { name ->
                name.replace(disallowedRepoChars, "")
                    .replaceRange(0..0, name[0].titlecaseChar().toString())
            }
        }
        .distinct()
        .sorted()
        .toList()
        .takeUnless { it.isEmpty() }
        ?: listOf("Unknown")
    println("Author names: ${committers.joinToString(separator = ", ")}")
    fun makeRepoName(authorNames: String) = "OOP$year-${authorNames}-$acronym"
    val maxCharsForAuthors = 100 - makeRepoName("").length - committers.size
    val charsToDiscard = max(0, committers.sumOf { it.length } - maxCharsForAuthors)
    val charsToDiscardPerEntry = ceil(charsToDiscard.toDouble() / committers.size).toInt()
    val filteredAuthors = committers.map { it.take(it.length - charsToDiscardPerEntry) }
    val authorNames = filteredAuthors.joinToString(separator = "-")
    // Maximum 100 chars allowed in GitHub
    val newName = "OOP$year-${authorNames.take(100)}-$acronym"
    println("Fork name: $newName")
    val targetOrganization = requireNotNull(github.getOrganization(targetOrganizationName))
    require(targetOrganization.getRepository(newName) == null) {
        "Repository $newName already exists in $targetOrganizationName: ${targetOrganization.getRepository(newName)}"
    }
    println("Forking ${repo.fullName} to $targetOrganizationName")
    val fork = repo.forkTo(targetOrganization)
    println("forked to ${fork.name}")
    return when {
        fork.isArchived -> fork
        else -> {
            fork.renameTo(newName)
            println("fork renamed to $newName")
            targetOrganization.getRepository(newName)
        }
    }
}

val fork: GHRepository = generateSequence(repo) { it.parent }
    .flatMap { it.listForks() }
    .find { it.ownerName == targetOrganizationName }
    ?.also {
        println("Fork already exists: skipping fork and updating instead")
        it.update()
    }
    ?: createFork()

/*
 * Clone the fork
 */
val workdir: File = createTempDirectory().toFile()

// Detect if we are running in CI
val isInCI = System.getenv("CI")?.toBoolean() == true

println("Working directory: ${workdir.absolutePath}. Cloning...")
val url: String = when {
    isInCI -> fork.httpTransportUrl
    else -> fork.sshUrl
}
println(shellRun { command("git", listOf("clone", url, workdir.absolutePath)) })

/*
* Apply the QA plugin
*/
val catalog = File("gradle/libs.versions.toml").apply { check(exists()) }.readText()
fun <T : Any> T?.notNull(message: String = ""): T = checkNotNull(this) { message }
val (pluginId, pluginVersion) = Regex("""oop\s*=\s*\"((?:\w|\.|-)+):(\d+\.\d+\.\d+(-.*)*)\"\s*$""")
    .find(catalog)
    .notNull("No oop entry in catalog:\n$catalog")
    .destructured
val buildFile = workdir.resolve("build.gradle.kts")
val build = buildFile.readText()
val pluginLine: String = "id(\"$pluginId\") version \"$pluginVersion\""
if (pluginId !in build) {
    val pluginRegex = Regex("""\s*plugins\s*\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
    val pluginMatch = requireNotNull(pluginRegex.find(build)) {
        "No plugins found in $buildFile"
    }
    val pluginContent = pluginMatch.groupValues[1]
    val newPlugins = build.replace(
        pluginRegex,
        "\nplugins {$pluginContent\n    $pluginLine}\n",
    )
    val javaVersion = Properties().apply { load(File("gradle.properties").inputStream()) }["java.version"]
    checkNotNull(javaVersion) { "No java.version in gradle.properties" }
    buildFile.writeText("$newPlugins\n java { toolchain { languageVersion.set(JavaLanguageVersion.of($javaVersion)) } }")
} else {
    val newBuild = build.replace(Regex("""id\s*\(\s*\"$pluginId\"\s*\)\s*version\s*\".*?\""""), pluginLine)
    buildFile.writeText(newBuild)
}

// 3. prepare a settings file
val settings = File("settings.gradle.kts").readText()
val projectSettings = File(workdir, "settings.gradle.kts")
projectSettings.writeText("rootProject.name = \"oop-$year-$acronym\"\n$settings")

// Add the CI process
File("workflows").copyRecursively(workdir.resolve(".github/workflows"), overwrite = true)

// Make sure gradlew is executable
File(workdir, "gradlew").setExecutable(true)

// Push the changes
shellRun {
    fun git(vararg command: String) = command("git", listOf("-C", workdir.absolutePath, *command)).also { println(it) }
    git("shortlog", "-sn", "--all")
    git("add", ".")
    if ("nothing to commit" !in git("status")) {
        git("commit", "-m", "ci: add the OOP machinery")
        if (isInCI) {
            "Push disabled in CI"
        } else {
            git("push")
        }
    } else {
        "No changes to commit"
    }
}

val ideaCommand = "intellij-idea-ultimate-edition ${workdir.absolutePath}"
kotlin.runCatching {
    Runtime.getRuntime().exec(ideaCommand)
}.onFailure { println(ideaCommand) }
