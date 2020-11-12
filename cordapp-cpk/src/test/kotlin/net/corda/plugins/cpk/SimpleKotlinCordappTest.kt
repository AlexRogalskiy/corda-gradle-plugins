package net.corda.plugins.cpk

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.io.TempDir
import org.osgi.framework.Constants.BUNDLE_LICENSE
import org.osgi.framework.Constants.BUNDLE_NAME
import org.osgi.framework.Constants.BUNDLE_SYMBOLICNAME
import org.osgi.framework.Constants.BUNDLE_VENDOR
import org.osgi.framework.Constants.BUNDLE_VERSION
import org.osgi.framework.Constants.EXPORT_PACKAGE
import org.osgi.framework.Constants.IMPORT_PACKAGE
import org.osgi.framework.Constants.REQUIRE_CAPABILITY
import java.nio.file.Path
import java.util.jar.JarFile

class SimpleKotlinCordappTest {
    companion object {
        const val guavaVersion = "29.0-jre"

        const val ioOsgiVersion = "version=\"[1.4,2)\""
        const val guavaOsgiVersion = "version=\"[29.0,30)\""
        const val kotlinOsgiVersion = "version=\"[1.3,2)\""
        const val cordaOsgiVersion = "version=\"[5.0,6)\""
        const val cordappOsgiVersion = "version=\"1.0.1\""

        private lateinit var testProject: GradleProject

        @Suppress("unused")
        @BeforeAll
        @JvmStatic
        fun setup(@TempDir testProjectDir: Path, reporter: TestReporter) {
            testProject = GradleProject(testProjectDir, reporter)
                .withTestName("simple-kotlin-cordapp")
                .withSubResource("src/main/kotlin/com/example/contract/ExampleContract.kt")
                .withSubResource("src/main/kotlin/com/example/contract/states/ExampleState.kt")
                .build(
                    "-Pcordapp_contract_version=$expectedCordappContractVersion",
                    "-Pcommons_io_version=$commonsIoVersion",
                    "-Pguava_version=$guavaVersion"
                )
        }
    }

    @Test
    fun simpleTest() {
        assertThat(testProject.dependencyConstraints)
            .anyMatch { it.startsWith("commons-io-$commonsIoVersion.jar") }
            .anyMatch { it.startsWith("guava-$guavaVersion.jar") }
            .hasSizeGreaterThanOrEqualTo(2)

        val artifacts = testProject.artifacts
        assertThat(artifacts).hasSize(2)

        val cpk = artifacts.single { it.toString().endsWith(".cpk") }
        assertThat(cpk).isRegularFile()

        val cordapp = artifacts.single { it.toString().endsWith(".jar") }
        assertThat(cordapp).isRegularFile()

        val jarManifest = JarFile(cordapp.toFile()).use(JarFile::getManifest)
        println(jarManifest.mainAttributes.entries)

        with(jarManifest.mainAttributes) {
            assertEquals("Simple Kotlin", getValue(BUNDLE_NAME))
            assertEquals("com.example.simple-kotlin-cordapp", getValue(BUNDLE_SYMBOLICNAME))
            assertEquals("1.0.1.SNAPSHOT", getValue(BUNDLE_VERSION))
            assertEquals("com.google.common.collect;$guavaOsgiVersion,kotlin;$kotlinOsgiVersion,kotlin.io;$kotlinOsgiVersion,kotlin.jvm.internal;$kotlinOsgiVersion,kotlin.text;$kotlinOsgiVersion,net.corda.core.contracts;$cordaOsgiVersion,net.corda.core.identity;$cordaOsgiVersion,net.corda.core.transactions;$cordaOsgiVersion,org.apache.commons.io;$ioOsgiVersion", getValue(IMPORT_PACKAGE))
            assertEquals("com.example.contract;uses:=\"kotlin,net.corda.core.contracts,net.corda.core.transactions\";$cordappOsgiVersion,com.example.contract.states;uses:=\"kotlin,net.corda.core.contracts,net.corda.core.identity\";$cordappOsgiVersion", getValue(EXPORT_PACKAGE))
            assertEquals("osgi.ee;filter:=\"(&(osgi.ee=JavaSE)(version=1.8))\"", getValue(REQUIRE_CAPABILITY))
            assertEquals("Test-Licence", getValue(BUNDLE_LICENSE))
            assertEquals("R3", getValue(BUNDLE_VENDOR))
            assertEquals("true", getValue("Sealed"))
            assertNull(getValue("Private-Package"))
        }
    }
}