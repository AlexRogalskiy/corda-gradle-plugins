package net.corda.plugins.cpk

import org.gradle.api.Action
import org.gradle.api.InvalidUserCodeException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.XmlProvider
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logger
import org.gradle.api.plugins.JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.tasks.TaskContainer
import org.w3c.dom.Element
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

class PublishAfterEvaluationHandler(rootProject: Project) : Action<Gradle> {
    private val logger: Logger = rootProject.logger
    private var artifactoryPublisher: ArtifactoryPublisher? = null

    private fun enableArtifactoryPublisher(plugin: Plugin<*>) {
        artifactoryPublisher = try {
            ArtifactoryPublisher(plugin, logger)
        } catch (_: Exception) {
            logger.warn("Cannot publish CPK companion POM to Artifactory")
            null
        }
    }

    init {
        rootProject.plugins.withId("com.jfrog.artifactory") { plugin ->
            enableArtifactoryPublisher(plugin)
        }
    }

    override fun execute(gradle: Gradle) {
        for (project in gradle.rootProject.allprojects) {
            if (project.plugins.hasPlugin(CordappPlugin::class.java)) {
                publishCompanionFor(project)
            }
        }
    }

    private fun publishCompanionFor(project: Project) {
        val publications = (project.extensions.findByType(PublishingExtension::class.java) ?: return).publications
        val pomXmlWriter = PomXmlWriter(project.configurations)
        publications.withType(MavenPublication::class.java)
            .matching { it.pom.packaging == "jar" && !it.groupId.isNullOrEmpty() }
            .all { pub ->
                val publicationProvider = publications.register("cpk-${pub.name}-companion", MavenPublication::class.java) { cpk ->
                    cpk.groupId = pub.groupId
                    cpk.version = pub.version
                    cpk.artifactId = toCompanionArtifactId(pub.groupId, pub.artifactId)
                    cpk.pom { pom ->
                        pom.packaging = "pom"
                        pom.url.set(pub.pom.url)
                        pom.description.set(pub.pom.description)
                        pom.inceptionYear.set(pub.pom.inceptionYear)
                        pom.withXml(pomXmlWriter)
                    }
                }
                artifactoryPublisher?.run {
                    publish(project.tasks, pub, publicationProvider)
                }
            }
    }
}

private class PomXmlWriter(private val configurations: ConfigurationContainer) : Action<XmlProvider> {
    override fun execute(xml: XmlProvider) {
        val compileClasspath = configurations.getByName(COMPILE_CLASSPATH_CONFIGURATION_NAME).resolvedConfiguration
        val dependencies = xml.asElement().appendElement("dependencies")

        val cordappWriter = CordappXmlWriter(dependencies)
        val cordappDependencies = configurations.getByName(CORDAPP_CONFIGURATION_NAME).allDependencies
        compileClasspath.resolveFirstLevel(cordappDependencies).forEach(cordappWriter::write)

        val providedWriter = ProvidedXmlWriter(dependencies)
        val providedDependencies = configurations.getByName(CORDA_PROVIDED_CONFIGURATION_NAME).allDependencies
        compileClasspath.resolveFirstLevel(providedDependencies).forEach(providedWriter::write)
    }
}

private abstract class DependencyXmlWriter(private val dependencies: Element) {
    fun write(artifact: ResolvedArtifact) {
        val artifactId = artifact.moduleVersion.id
        val dependency = dependencies.appendElement("dependency")
        dependency.appendElement("groupId", artifactId.group)
        dependency.appendElement("artifactId", toArtifactId(artifact))
        dependency.appendElement("version", artifactId.version)
        artifact.classifier?.also { classifier ->
            dependency.appendElement("classifier", classifier)
        }
        if (artifact.type != "jar") {
            dependency.appendElement("type", artifact.type)
        }
        dependency.appendElement("scope", "compile")
    }

    abstract fun toArtifactId(artifact: ResolvedArtifact): String
}

private class CordappXmlWriter(dependencies: Element) : DependencyXmlWriter(dependencies) {
    override fun toArtifactId(artifact: ResolvedArtifact): String {
        return toCompanionArtifactId(artifact.moduleVersion.id.group, artifact.name)
    }
}

private class ProvidedXmlWriter(dependencies: Element): DependencyXmlWriter(dependencies) {
    override fun toArtifactId(artifact: ResolvedArtifact): String {
        return artifact.name
    }
}

/**
 * Integrate with the `com.jfrog.artifactory` Gradle plugin, which uses a
 * [ProjectEvaluationListener][org.gradle.api.ProjectEvaluationListener] to
 * configure itself. We can therefore assume here that any `ArtifactoryTask`
 * objects have already been configured.
 *
 * All we need is the set of [MavenPublication] objects that are to be
 * published to Artifactory. If our CPK's own [MavenPublication] is
 * among them then we include its companion's publication too.
 */
private class ArtifactoryPublisher(plugin: Plugin<*>, logger: Logger) {
    private companion object {
        private const val ARTIFACTORY_TASK_NAME = "org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask"
        private const val GET_PUBLICATIONS_METHOD_NAME = "getMavenPublications"
    }

    private val artifactoryTaskClass = try {
        @Suppress("unchecked_cast")
        Class.forName(ARTIFACTORY_TASK_NAME, true, plugin::class.java.classLoader) as Class<out Task>
    } catch (e: ClassNotFoundException) {
        logger.info("Task {} from Gradle com.jfrog.artifactory plugin is not available.", ARTIFACTORY_TASK_NAME)
        throw e
    }

    private val mavenPublications: Method = try {
        artifactoryTaskClass.getMethod(GET_PUBLICATIONS_METHOD_NAME)
    } catch (e: Exception) {
        logger.warn("Cannot locate $GET_PUBLICATIONS_METHOD_NAME method for ArtifactoryTask", e)
        throw e
    }

    init {
        if (!MutableCollection::class.java.isAssignableFrom(mavenPublications.returnType)) {
            logger.warn("Method {} does not return a collection type.", mavenPublications)
            throw InvalidUserCodeException()
        }
    }

    private fun getMavenPublications(task: Task): MutableCollection<MavenPublication> {
        return try {
            @Suppress("unchecked_cast")
            mavenPublications.invoke(task) as MutableCollection<MavenPublication>
        } catch (e: InvocationTargetException) {
            throw InvalidUserCodeException("Failed to extract Maven publications from $task", e.targetException)
        }
    }

    fun publish(tasks: TaskContainer, owner: MavenPublication, provider: Provider<out MavenPublication>) {
        tasks.withType(artifactoryTaskClass).configureEach { task ->
            val publications = getMavenPublications(task)
            if (publications.contains(owner)) {
                val publication = provider.get()
                if (publications.add(publication)) {
                    // Only modify the task graph if our publication wasn't already present.
                    task.dependsOn(tasks.named(getGeneratePomTaskName(publication), GenerateMavenPom::class.java))
                }
            }
        }
    }

    private fun getGeneratePomTaskName(publication: MavenPublication): String {
        // This name is documented behaviour for Gradle's maven-publish plugin.
        return "generatePomFileFor${publication.name.capitalize()}Publication"
    }
}