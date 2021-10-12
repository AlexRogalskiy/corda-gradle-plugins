@file:JvmName("CordappUtils")
package net.corda.plugins

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.plugins.JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME
import org.gradle.api.plugins.JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME

const val CORDAPP_TASK_GROUP = "Cordapp"

private fun ConfigurationContainer.createChildConfiguration(name: String, parent: Configuration): Configuration {
    return maybeCreate(name)
        .setTransitive(false)
        .setVisible(false)
        .also { configuration ->
            configuration.isCanBeConsumed = false
            configuration.isCanBeResolved = false
            parent.extendsFrom(configuration)
        }
}

fun ConfigurationContainer.createImplementationConfiguration(name: String): Configuration {
    return createChildConfiguration(name, getByName(IMPLEMENTATION_CONFIGURATION_NAME))
}

fun ConfigurationContainer.createRuntimeOnlyConfiguration(name: String): Configuration {
    return createChildConfiguration(name, getByName(RUNTIME_ONLY_CONFIGURATION_NAME))
}
