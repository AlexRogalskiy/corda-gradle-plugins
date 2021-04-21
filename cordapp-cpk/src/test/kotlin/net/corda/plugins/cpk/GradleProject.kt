@file:JvmName("ProjectConstants")
package net.corda.plugins.cpk

import aQute.bnd.version.MavenVersion.parseMavenString
import aQute.bnd.version.VersionRange
import net.corda.plugins.cpk.xml.CPKDependency
import net.corda.plugins.cpk.xml.DependencyConstraint
import net.corda.plugins.cpk.xml.HashValue
import net.corda.plugins.cpk.xml.loadCPKDependencies
import net.corda.plugins.cpk.xml.loadDependencyConstraints
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.JavaVersion.current
import org.gradle.api.JavaVersion.VERSION_15
import org.gradle.api.plugins.BasePlugin.ASSEMBLE_TASK_NAME
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.BuildTask
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.TestReporter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.stream.Collectors.toList
import kotlin.test.fail

const val expectedCordappContractVersion = 2
const val expectedCordappWorkflowVersion = 3
const val expectedCordappServiceVersion = 4
const val cordaReleaseVersion = "4.6"
const val cordaApiVersion = "5.0.0"
const val annotationsVersion = "1.0.1"
const val commonsCollectionsVersion = "3.2.2"
const val commonsCodecVersion = "1.15"
const val commonsIoVersion = "2.8.0"
const val slf4jVersion = "1.7.30"

private val GRADLE_7 = GradleVersion.version("7.0")

fun toOSGi(version: String): String {
    return parseMavenString(version).osGiVersion.toString()
}

fun toOSGiRange(version: String): String {
    val osgiVersion = parseMavenString(version).osGiVersion
    return VersionRange(true, osgiVersion, osgiVersion.bumpMajor(), false)
        .toString().replace(".0","")
}

val Path.manifest: Manifest get() = JarFile(toFile()).use(JarFile::getManifest)

val List<HashValue>.allSHA256: Boolean get() = isNotEmpty() && all(HashValue::isSHA256)

@Suppress("unused", "MemberVisibilityCanBePrivate")
class GradleProject(private val projectDir: Path, private val reporter: TestReporter) {
    private companion object {
        private const val DEFAULT_TASK_NAME = ASSEMBLE_TASK_NAME
        private const val META_INF_DIR = "META-INF"
        private val testGradleUserHome = systemProperty("test.gradle.user.home")

        private val testProperties: Properties = Properties().also { props ->
            this::class.java.classLoader.getResourceAsStream("gradle.properties")?.use(props::load)
        }

        fun systemProperty(name: String): String = System.getProperty(name) ?: fail("System property '$name' not set.")

        @Throws(IOException::class)
        private fun installResource(folder: Path, resourceName: String): Boolean {
            val buildFile = folder.resolve(resourceName.substring(1 + resourceName.lastIndexOf('/')))
            return copyResourceTo(resourceName, buildFile) >= 0
        }

        @Throws(IOException::class)
        private fun copyResourceTo(resourceName: String, target: Path): Long {
            return GradleProject::class.java.classLoader.getResourceAsStream(resourceName)?.use { input ->
                Files.copy(input, target, REPLACE_EXISTING)
            } ?: -1
        }
    }

    private lateinit var result: BuildResult
    private var gradleVersion: GradleVersion = GradleVersion.current()
    private var buildScript: String = ""
    private var taskName: String = DEFAULT_TASK_NAME
    private var testName: String = "."

    /**
     * We must execute [GradleRunner][org.gradle.testkit.runner.GradleRunner]
     * embedded in the existing Gradle instance in order to debug it. This
     * requires our current JVM toolchain to be compatible with Gradle.
     *
     * Gradle 6.x is compatible with Java 8 <= x <= Java 15.
     * Gradle 7.0 is compatible with Java 8 <= x <= Java 16.
     */
    private val isDebuggable: Boolean
        @Suppress("UnstableApiUsage")
        get() = VERSION_15.isCompatibleWith(current()) || gradleVersion >= GRADLE_7

    fun withGradleVersion(version: GradleVersion): GradleProject {
        this.gradleVersion = version
        return this
    }

    fun withTestName(testName: String): GradleProject {
        this.testName = testName
        return this
    }

    fun withTaskName(taskName: String): GradleProject {
        this.taskName = taskName
        return this
    }

    fun withSubResource(resourceName: String): GradleProject {
        installResource(subDirectoryFor(resourceName), "$testName/$resourceName")
        return this
    }

    private fun subDirectoryFor(resourceName: String): Path {
        var directory = projectDir
        var startIdx = 0
        while (true) {
            val endIdx = resourceName.indexOf('/', startIdx)
            if (endIdx == -1) {
                break
            }
            directory = Files.createDirectories(directory.resolve(resourceName.substring(startIdx, endIdx)))
            startIdx = endIdx + 1
        }
        return directory
    }

    fun withBuildScript(buildScript: String): GradleProject {
        this.buildScript = buildScript
        return this
    }

    val properties: Properties get() = testProperties

    val buildDir: Path = projectDir.resolve("build")
    val artifactDir: Path = buildDir.resolve("libs")
    val artifacts: List<Path>
        @Throws(IOException::class)
        get() = Files.list(artifactDir).collect(toList())

    val dependencyConstraintsFile: Path = buildDir.resolve("generated-constraints")
        .resolve(META_INF_DIR).resolve("DependencyConstraints")
    val dependencyConstraints: List<DependencyConstraint>
        get() = dependencyConstraintsFile.toFile().inputStream().buffered().use(::loadDependencyConstraints)

    val cpkDependenciesFile: Path = buildDir.resolve("cpk-dependencies")
        .resolve(META_INF_DIR).resolve("CPKDependencies")
    val cpkDependencies: List<CPKDependency>
        get() = cpkDependenciesFile.toFile().inputStream().buffered().use(::loadCPKDependencies)

    var output: String = ""
        private set

    fun resultFor(taskName: String): BuildTask {
        return result.task(":$taskName") ?: fail("No outcome for $taskName task")
    }

    fun outcomeOf(taskName: String): TaskOutcome? {
        return result.task(":$taskName")?.outcome
    }

    private fun configureGradle(builder: (GradleRunner) -> BuildResult, args: Array<out String>) {
        installResource(projectDir, "repositories.gradle")
        installResource(projectDir, "javaTarget.gradle")
        installResource(projectDir, "kotlin.gradle")
        installResource(projectDir, "gradle.properties")
        if (!installResource(projectDir, "$testName/settings.gradle")) {
            installResource(projectDir, "settings.gradle")
        }
        if (!installResource(projectDir, "$testName/build.gradle")) {
            projectDir.resolve("build.gradle").toFile().writeText(buildScript)
        }

        val runner = GradleRunner.create()
            .withGradleVersion(gradleVersion.version)
            .withProjectDir(projectDir.toFile())
            .withArguments(getGradleArgs(args))
            .withDebug(isDebuggable)
            .withPluginClasspath()
        result = builder(runner)

        output = result.output
        reporter.publishEntry("stdout", output)
        println(output)
    }

    fun build(vararg args: String): GradleProject {
        configureGradle(GradleRunner::build, args)
        assertThat(buildDir).isDirectory()
        assertThat(artifactDir).isDirectory()
        assertEquals(SUCCESS, resultFor(taskName).outcome)
        return this
    }

    fun buildAndFail(vararg args: String): GradleProject {
        configureGradle(GradleRunner::buildAndFail, args)
        return this
    }

    private fun getGradleArgs(args: Array<out String>): List<String> {
        return arrayListOf(taskName, "--info", "--stacktrace", "-g", testGradleUserHome, *args)
    }
}
