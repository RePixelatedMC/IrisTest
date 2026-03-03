import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.GradleException
import org.gradle.api.tasks.GradleBuild
import org.gradle.api.tasks.testing.Test
import xyz.jpenilla.runpaper.task.RunServer
import java.io.File
import java.util.ArrayDeque
import kotlin.system.exitProcess

/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2021 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

buildscript {
    repositories.maven("https://jitpack.io")
    dependencies.classpath("com.github.VolmitSoftware:NMSTools:c88961416f")
}

plugins {
    java
    `java-library`
    alias(libs.plugins.download)
    alias(libs.plugins.runPaper)
}

group = "com.volmit"
version = "3.8.0-1.20.1-1.21.10"

apply<ApiGenerator>()

// ADD YOURSELF AS A NEW LINE IF YOU WANT YOUR OWN BUILD TASK GENERATED
// ======================== WINDOWS =============================
registerCustomOutputTask("Cyberpwn", "C://Users/cyberpwn/Documents/development/server/plugins")
registerCustomOutputTask("Psycho", "C://Dan/MinecraftDevelopment/Server/plugins")
registerCustomOutputTask("ArcaneArts", "C://Users/arcane/Documents/development/server/plugins")
registerCustomOutputTask("Coco", "D://mcsm/plugins")
registerCustomOutputTask("Strange", "D://Servers/1.17 Test Server/plugins")
registerCustomOutputTask("Vatuu", "D://Minecraft/Servers/1.19.4/plugins")
registerCustomOutputTask("CrazyDev22", "C://Users/Julian/Desktop/server/plugins")
registerCustomOutputTask("PixelFury", "C://Users/repix/workplace/Iris/1.21.3 - Development-Public-v3/plugins")
registerCustomOutputTask("PixelFuryDev", "C://Users/repix/workplace/Iris/1.21 - Development-v3/plugins")
// ========================== UNIX ==============================
registerCustomOutputTaskUnix("CyberpwnLT", "/Users/danielmills/development/server/plugins")
registerCustomOutputTaskUnix("PsychoLT", "/Users/brianfopiano/Developer/RemoteGit/Server/plugins")
registerCustomOutputTaskUnix("PixelMac", "/Users/test/Desktop/mcserver/plugins")
registerCustomOutputTaskUnix("CrazyDev22LT", "/home/julian/Desktop/server/plugins")
// ==============================================================

val serverMinHeap = "10G"
val serverMaxHeap = "10G"
val additionalFlags = "-XX:+AlwaysPreTouch"
//Valid values are: none, truecolor, indexed256, indexed16, indexed8
val color = "truecolor"
val errorReporting = "true" == findProperty("errorReporting")

val nmsBindings = mapOf(
        "v1_21_R6" to "1.21.10-R0.1-SNAPSHOT",
        "v1_21_R5" to "1.21.8-R0.1-SNAPSHOT",
        "v1_21_R4" to "1.21.5-R0.1-SNAPSHOT",
        "v1_21_R3" to "1.21.4-R0.1-SNAPSHOT",
        "v1_21_R2" to "1.21.3-R0.1-SNAPSHOT",
        "v1_21_R1" to "1.21.1-R0.1-SNAPSHOT",
        "v1_20_R4" to "1.20.6-R0.1-SNAPSHOT",
        "v1_20_R3" to "1.20.4-R0.1-SNAPSHOT",
        "v1_20_R2" to "1.20.2-R0.1-SNAPSHOT",
        "v1_20_R1" to "1.20.1-R0.1-SNAPSHOT",
)
val jvmVersion = mapOf<String, Int>()
nmsBindings.forEach { (key, value) ->
    project(":nms:$key") {
        apply<JavaPlugin>()

        nmsBinding {
            jvm = jvmVersion.getOrDefault(key, 21)
            version = value
            type = NMSBinding.Type.DIRECT
        }

        dependencies {
            compileOnly(project(":core"))
            compileOnly(rootProject.libs.annotations)
            compileOnly(rootProject.libs.byteBuddy.core)
        }
    }

    tasks.register<RunServer>("runServer-$key") {
        group = "servers"
        minecraftVersion(value.split("-")[0])
        minHeapSize = serverMinHeap
        maxHeapSize = serverMaxHeap
        pluginJars(tasks.jar.flatMap { it.archiveFile })
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(jvmVersion.getOrDefault(key, 21))}
        runDirectory.convention(layout.buildDirectory.dir("run/$key"))
        systemProperty("disable.watchdog", "true")
        systemProperty("net.kyori.ansi.colorLevel", color)
        systemProperty("com.mojang.eula.agree", true)
        systemProperty("iris.suppressReporting", !errorReporting)
        jvmArgs("-javaagent:${project(":core:agent").tasks.jar.flatMap { it.archiveFile }.get().asFile.absolutePath}")
        jvmArgs(additionalFlags.split(' '))
    }
}

val included: Configuration by configurations.creating
val jarJar: Configuration by configurations.creating
dependencies {
    for (key in nmsBindings.keys) {
        included(project(":nms:$key", "reobf"))
    }
    included(project(":core", "shadow"))
    jarJar(project(":core:agent"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

fun extractQuotedStrings(json: String): Sequence<String> =
    Regex(""""((?:\\\\.|[^"\\\\])*)"""")
        .findAll(json)
        .map { match -> match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }

fun resolvePackReferences(packRoot: File, reference: String): List<File> {
    val normalized = reference.trim().removePrefix("./")
    if (normalized.isBlank() || ':' in normalized || normalized.startsWith("minecraft/")) {
        return emptyList()
    }

    val candidates = linkedSetOf(
        normalized,
        "$normalized.json",
        "$normalized.iob",
        "$normalized.iobs",
        "dimensions/$normalized.json",
        "regions/$normalized.json",
        "biomes/$normalized.json",
        "generators/$normalized.json",
        "expressions/$normalized.json",
        "objects/$normalized.json",
        "objects/$normalized.iob",
        "objects/$normalized.iobs",
        "snippet/$normalized.json"
    )

    val matches = linkedSetOf<File>()
    candidates.map(packRoot::resolve).filter(File::isFile).forEach(matches::add)

    if (normalized.startsWith("snippet/")) {
        val snippetName = normalized.removePrefix("snippet/").substringAfterLast('/')
        val snippetRoot = packRoot.resolve("snippet")
        if (snippetRoot.isDirectory) {
            snippetRoot.walkTopDown()
                .filter { it.isFile && it.extension == "json" && it.nameWithoutExtension == snippetName }
                .forEach(matches::add)
        }
    }

    return matches.toList()
}

fun collectPackFiles(packRoot: File, sourceDimension: String): Set<File> {
    val collected = linkedSetOf<File>()
    val pending = ArrayDeque<File>()

    fun include(file: File) {
        val normalized = file.normalize()
        if (normalized.isFile && collected.add(normalized)) {
            pending += normalized
            if (normalized.extension == "iob") {
                val siblingIobs = File(normalized.parentFile, "${normalized.nameWithoutExtension}.iobs")
                if (siblingIobs.isFile) {
                    include(siblingIobs)
                }
            }
        }
    }

    include(packRoot.resolve("dimensions/$sourceDimension.json"))
    listOf(
        packRoot.resolve("biomes/empty.json"),
        packRoot.resolve("regions/empty.json"),
        packRoot.resolve("generators/empty.json")
    ).filter(File::isFile).forEach(::include)

    while (pending.isNotEmpty()) {
        val file = pending.removeFirst()
        if (file.extension != "json") {
            continue
        }

        extractQuotedStrings(file.readText()).forEach { reference ->
            resolvePackReferences(packRoot, reference).forEach(::include)
        }
    }

    return collected
}

val mantleRaceResultsRoot = layout.buildDirectory.dir("integration-results/mantle-race/v1_21_R6")
val mantleRaceResultFile = mantleRaceResultsRoot.map { it.file("latest-report.properties") }
val mantleRaceRadius = providers.systemProperty("iris.integration.mantleRace.radius").orElse("5000")
val mantleRaceMinChunks = providers.systemProperty("iris.integration.mantleRace.minChunks").orElse("15000")
val mantleRaceMaxMissingStableRate = providers.systemProperty("iris.integration.mantleRace.maxMissingStableRate").orElse("0.0002")
val mantleRaceMaxStableMismatchRate = providers.systemProperty("iris.integration.mantleRace.maxStableMismatchRate").orElse("0.0005")
val mantleRaceScanThreads = providers.systemProperty("iris.integration.mantleRace.scanThreads").orElse("4")
val mantleRaceSourceDimension = "frontier"
val mantleRacePersistentSourcePackDir = layout.projectDirectory.dir("mantle-race-source/frontier")
val mantleRaceSourcePackDir = providers.systemProperty("iris.integration.mantleRace.sourcePackDir")
    .orElse(providers.provider { mantleRacePersistentSourcePackDir.asFile.absolutePath })
val mantleRacePreparedPack = providers.systemProperty("iris.integration.mantleRace.dimension").orElse("frontier")
val mantleRaceWorldName = providers.systemProperty("iris.integration.mantleRace.worldName").orNull
val mantleRaceServerPort = providers.systemProperty("iris.integration.mantleRace.serverPort").orElse("25575")
val mantleRaceRunDirectory = layout.buildDirectory.dir("integration-tests/mantle-race/v1_21_R6/server")

val prepareMantleRacePackV1_21_R6 = tasks.register("prepareMantleRacePack-v1_21_R6") {
    group = "verification"
    description = "Creates a stripped, rebranded mantle race pack copy for the integration test."

    outputs.upToDateWhen { false }

    doLast {
        val sourcePackDir = file(mantleRaceSourcePackDir.get())
        if (!sourcePackDir.isDirectory) {
            throw GradleException("Mantle race source pack is missing: ${sourcePackDir.absolutePath}")
        }

        val sourceDimension = mantleRaceSourceDimension
        val targetPackName = mantleRacePreparedPack.get()
        val targetPackDir = mantleRaceRunDirectory.get().asFile.resolve("plugins/Iris/packs/$targetPackName")
        val collectedFiles = collectPackFiles(sourcePackDir, sourceDimension)
        val sourceDimensionFile = sourcePackDir.resolve("dimensions/$sourceDimension.json").normalize()

        targetPackDir.deleteRecursively()
        targetPackDir.mkdirs()

        collectedFiles.forEach { source ->
            val relativePath = source.relativeTo(sourcePackDir).path.replace(File.separatorChar, '/')
            val target = if (source.normalize() == sourceDimensionFile) {
                targetPackDir.resolve("dimensions/$targetPackName.json")
            } else {
                targetPackDir.resolve(relativePath)
            }

            target.parentFile.mkdirs()
            if (source.normalize() == sourceDimensionFile) {
                source.copyTo(target, overwrite = true)
            } else {
                source.copyTo(target, overwrite = true)
            }
        }

        logger.lifecycle(
            "Prepared mantle race pack '{}' with {} files from '{}'",
            targetPackName,
            collectedFiles.size,
            sourcePackDir.absolutePath
        )
    }
}

val runMantleRaceServerV1_21_R6 = tasks.register<RunServer>("runMantleRaceServer-v1_21_R6") {
    group = "verification"
    description = "Launches Paper and runs the mantle/world parity integration harness."

    dependsOn("jar")
    dependsOn(prepareMantleRacePackV1_21_R6)

    minecraftVersion(nmsBindings.getValue("v1_21_R6").split("-")[0])
    minHeapSize = serverMinHeap
    maxHeapSize = serverMaxHeap
    pluginJars(tasks.jar.flatMap { it.archiveFile })
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    runDirectory.convention(mantleRaceRunDirectory)
    systemProperty("disable.watchdog", "true")
    systemProperty("net.kyori.ansi.colorLevel", color)
    systemProperty("com.mojang.eula.agree", true)
    systemProperty("iris.suppressReporting", !errorReporting)
    systemProperty("iris.integration.mantleRace.enabled", true)
    systemProperty("iris.integration.mantleRace.radius", mantleRaceRadius.get())
    systemProperty("iris.integration.mantleRace.minChunks", mantleRaceMinChunks.get())
    systemProperty("iris.integration.mantleRace.maxMissingStableRate", mantleRaceMaxMissingStableRate.get())
    systemProperty("iris.integration.mantleRace.maxStableMismatchRate", mantleRaceMaxStableMismatchRate.get())
    systemProperty("iris.integration.mantleRace.scanThreads", mantleRaceScanThreads.get())
    systemProperty("iris.integration.mantleRace.dimension", mantleRacePreparedPack.get())
    systemProperty("iris.integration.mantleRace.resultFile", mantleRaceResultFile.map { it.asFile.absolutePath }.get())
    jvmArgs("-javaagent:${project(":core:agent").tasks.jar.flatMap { it.archiveFile }.get().asFile.absolutePath}")
    jvmArgs(additionalFlags.split(' '))

    if (mantleRaceWorldName != null) {
        systemProperty("iris.integration.mantleRace.worldName", mantleRaceWorldName)
    }

    outputs.upToDateWhen { false }

    doFirst {
        val file = mantleRaceResultFile.get().asFile
        file.parentFile.mkdirs()
        file.delete()

        val runDir = runDirectory.get().asFile
        runDir.mkdirs()
        runDir.resolve("server.properties").writeText(
            """
            server-port=${mantleRaceServerPort.get()}
            motd=Mantle Race Integration
            online-mode=false
            """.trimIndent() + System.lineSeparator()
        )
    }
}

val warmupMantleRaceServerV1_21_R6 = tasks.register<RunServer>("warmupMantleRaceServer-v1_21_R6") {
    group = "verification"
    description = "Boots Paper once so Iris can install any required datapack entries before the real mantle race test."

    dependsOn("jar")
    dependsOn(prepareMantleRacePackV1_21_R6)

    minecraftVersion(nmsBindings.getValue("v1_21_R6").split("-")[0])
    minHeapSize = serverMinHeap
    maxHeapSize = serverMaxHeap
    pluginJars(tasks.jar.flatMap { it.archiveFile })
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    runDirectory.convention(mantleRaceRunDirectory)
    systemProperty("disable.watchdog", "true")
    systemProperty("net.kyori.ansi.colorLevel", color)
    systemProperty("com.mojang.eula.agree", true)
    systemProperty("iris.suppressReporting", !errorReporting)
    systemProperty("iris.integration.shutdownAfterStartup", true)
    systemProperty("iris.integration.mantleRace.dimension", mantleRacePreparedPack.get())
    jvmArgs("-javaagent:${project(":core:agent").tasks.jar.flatMap { it.archiveFile }.get().asFile.absolutePath}")
    jvmArgs(additionalFlags.split(' '))

    outputs.upToDateWhen { false }

    doFirst {
        val runDir = runDirectory.get().asFile
        runDir.mkdirs()
        runDir.resolve("server.properties").writeText(
            """
            server-port=${mantleRaceServerPort.get()}
            motd=Mantle Race Warmup
            online-mode=false
            """.trimIndent() + System.lineSeparator()
        )
    }
}

runMantleRaceServerV1_21_R6.configure {
    dependsOn(warmupMantleRaceServerV1_21_R6)
    mustRunAfter(warmupMantleRaceServerV1_21_R6)
}

tasks.register<Test>("mantleRaceIntegrationTest") {
    group = "verification"
    description = "Runs the mantle/world parity regression test against a live Paper server."

    dependsOn(runMantleRaceServerV1_21_R6)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("mantle-race")
    }
    systemProperty("iris.test.mantleRace.reportFile", mantleRaceResultFile.map { it.asFile.absolutePath }.get())
    outputs.upToDateWhen { false }
}

tasks.register<GradleBuild>("mantleRaceIntegrationTestMini") {
    group = "verification"
    description = "Runs mantleRaceIntegrationTest with iris.integration.mantleRace.radius=2500 and a smaller minimum sample gate."
    tasks = listOf("mantleRaceIntegrationTest")
    startParameter.systemPropertiesArgs.putAll(
        mapOf(
            "iris.integration.mantleRace.radius" to "2500",
            "iris.integration.mantleRace.minChunks" to "2500"
        )
    )
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("mantle-race")
    }
}

tasks {
    jar {
        inputs.files(included)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(jarJar, provider { included.resolve().map(::zipTree) })
        archiveFileName.set("Iris-${project.version}.jar")
    }

    register<Copy>("iris") {
        group = "iris"
        dependsOn("jar")
        from(layout.buildDirectory.file("libs/Iris-${project.version}.jar"))
        into(layout.buildDirectory)
    }

    register<Copy>("irisDev") {
        group = "iris"
        from(project(":core").layout.buildDirectory.files("libs/core-javadoc.jar", "libs/core-sources.jar"))
        rename { it.replace("core", "Iris-${project.version}") }
        into(layout.buildDirectory)
        dependsOn(":core:sourcesJar")
        dependsOn(":core:javadocJar")
    }

    val cli = file("sentry-cli.exe")
    register<Download>("downloadCli") {
        group = "io.sentry"
        src("https://release-registry.services.sentry.io/apps/sentry-cli/latest?response=download&arch=x86_64&platform=${System.getProperty("os.name")}&package=sentry-cli")
        dest(cli)

        doLast {
            cli.setExecutable(true)
        }
    }

    register("release") {
        group = "io.sentry"
        dependsOn("downloadCli")
        doLast {
            val url = "http://sentry.volmit.com:8080"
            val authToken = project.findProperty("sentry.auth.token") ?: System.getenv("SENTRY_AUTH_TOKEN")
            val org = "sentry"
            val projectName = "iris"
            exec(cli, "--url", url , "--auth-token", authToken, "releases", "new", "-o", org, "-p", projectName, version)
            exec(cli, "--url", url , "--auth-token", authToken, "releases", "set-commits", "-o", org, "-p", projectName, version, "--auto", "--ignore-missing")
            //exec(cli, "--url", url, "--auth-token", authToken, "releases", "finalize", "-o", org, "-p", projectName, version)
            cli.delete()
        }
    }
}

fun exec(vararg command: Any) {
    val p = ProcessBuilder(command.map { it.toString() })
        .start()
    p.inputStream.reader().useLines { it.forEach(::println) }
    p.errorStream.reader().useLines { it.forEach(::println) }
    p.waitFor()
}

configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(60, "minutes")
    resolutionStrategy.cacheDynamicVersionsFor(60, "minutes")
}

allprojects {
    apply<JavaPlugin>()

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.org/repository/maven-public/")

        maven("https://jitpack.io") // EcoItems, score
        maven("https://repo.nexomc.com/releases/") // nexo
        maven("https://maven.devs.beer/") // itemsadder
        maven("https://repo.extendedclip.com/releases/") // placeholderapi
        maven("https://mvn.lumine.io/repository/maven-public/") // mythic
        maven("https://nexus.phoenixdevt.fr/repository/maven-public/") //MMOItems
        maven("https://repo.onarandombox.com/content/groups/public/") //Multiverse Core
    }

    dependencies {
        // Provided or Classpath
        compileOnly(rootProject.libs.lombok)
        annotationProcessor(rootProject.libs.lombok)
    }

    /**
     * We need parameter meta for the decree command system
     */
    tasks {
        compileJava {
            options.compilerArgs.add("-parameters")
            options.encoding = "UTF-8"
        }

        javadoc {
            options.encoding = "UTF-8"
            options.quiet()
            //options.addStringOption("Xdoclint:none") // TODO: Re-enable this
        }

        register<Jar>("sourcesJar") {
            archiveClassifier.set("sources")
            from(sourceSets.main.map { it.allSource })
        }

        register<Jar>("javadocJar") {
            archiveClassifier.set("javadoc")
            from(javadoc.map { it.destinationDir!! })
        }
    }
}

if (JavaVersion.current().toString() != "21") {
    System.err.println()
    System.err.println("=========================================================================================================")
    System.err.println("You must run gradle on Java 21. You are using " + JavaVersion.current())
    System.err.println()
    System.err.println("=== For IDEs ===")
    System.err.println("1. Configure the project for Java 21")
    System.err.println("2. Configure the bundled gradle to use Java 21 in settings")
    System.err.println()
    System.err.println("=== For Command Line (gradlew) ===")
    System.err.println("1. Install JDK 21 from https://www.oracle.com/java/technologies/javase/jdk21-archive-downloads.html")
    System.err.println("2. Set JAVA_HOME environment variable to the new jdk installation folder such as C:\\Program Files\\Java\\jdk-21.0.4")
    System.err.println("3. Open a new command prompt window to get the new environment variables if need be.")
    System.err.println("=========================================================================================================")
    System.err.println()
    exitProcess(69)
}


fun registerCustomOutputTask(name: String, path: String) {
    if (!System.getProperty("os.name").lowercase().contains("windows")) {
        return
    }

    tasks.register<Copy>("build$name") {
        group = "development"
        outputs.upToDateWhen { false }
        dependsOn("iris")
        from(layout.buildDirectory.file("Iris-${project.version}.jar"))
        into(file(path))
        rename { "Iris.jar" }
    }
}

fun registerCustomOutputTaskUnix(name: String, path: String) {
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        return
    }

    tasks.register<Copy>("build$name") {
        group = "development"
        outputs.upToDateWhen { false }
        dependsOn("iris")
        from(layout.buildDirectory.file("Iris-${project.version}.jar"))
        into(file(path))
        rename { "Iris.jar" }
    }
}
