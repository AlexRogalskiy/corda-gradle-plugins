package net.corda.plugins

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.plugins.JavaPlugin.COMPILE_CONFIGURATION_NAME
import org.gradle.api.plugins.JavaPlugin.RUNTIME_CONFIGURATION_NAME

const val CORDAPP_TASK_GROUP = "Cordapp"

class CordappUtils {
    companion object {
        private fun createChildConfiguration(name: String, parent: Configuration, configurations: ConfigurationContainer): Configuration {
            return configurations.maybeCreate(name)
                .setTransitive(false)
                .setVisible(false)
                .also { configuration ->
                    parent.extendsFrom(configuration)
                }
        }

        fun createCompileConfiguration(name: String, configurations: ConfigurationContainer): Configuration {
            @Suppress("deprecation")
            return createChildConfiguration(name, configurations.getByName(COMPILE_CONFIGURATION_NAME), configurations)
        }

        fun createRuntimeConfiguration(name: String, configurations: ConfigurationContainer): Configuration {
            @Suppress("deprecation")
            return createChildConfiguration(name, configurations.getByName(RUNTIME_CONFIGURATION_NAME), configurations)
        }
    }
}
