package net.corda.plugins.cpk

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.io.TempDir
import org.osgi.framework.Constants.BUNDLE_VERSION
import org.osgi.framework.Constants.DYNAMICIMPORT_PACKAGE
import org.osgi.framework.Constants.IMPORT_PACKAGE
import java.nio.file.Path

class WithCordaMetadataTest {
    companion object {
        const val CONTRACT_CORDAPP_VERSION = "1.1.1-SNAPSHOT"
        const val WORKFLOW_CORDAPP_VERSION = "2.2.2-SNAPSHOT"
        const val SERVICE_CORDAPP_VERSION = "3.3.3-SNAPSHOT"
        const val HIBERNATE_ANNOTATIONS_PACKAGE = "org.hibernate.annotations"
        const val HIBERNATE_PROXY_PACKAGE = "org.hibernate.proxy"
        const val JAVASSIST_PROXY_PACKAGE = "javassist.util.proxy"
        const val JAVAX_PERSISTENCE_PACKAGE = "javax.persistence"
        private lateinit var testProject: GradleProject

        @Suppress("unused")
        @BeforeAll
        @JvmStatic
        fun setup(@TempDir testProjectDir: Path, reporter: TestReporter) {
            testProject = GradleProject(testProjectDir, reporter)
                .withTestName("with-corda-metadata")
                .withSubResource("contracts/build.gradle")
                .withSubResource("contracts/src/main/kotlin/com/example/metadata/contracts/ExampleContract.kt")
                .withSubResource("contracts/src/main/kotlin/com/example/metadata/schemas/ExampleSchema.kt")
                .withSubResource("workflows/build.gradle")
                .withSubResource("workflows/src/main/kotlin/com/example/metadata/workflows/ExampleFlow.kt")
                .withSubResource("services/build.gradle")
                .withSubResource("services/src/main/kotlin/com/example/metadata/services/ExampleService.kt")
                .build(
                    "-Pcontract_name=With Contract Metadata",
                    "-Pcordapp_contract_version=$expectedCordappContractVersion",
                    "-Pcontract_cordapp_version=$CONTRACT_CORDAPP_VERSION",
                    "-Pworkflow_name=With Workflow Metadata",
                    "-Pcordapp_workflow_version=$expectedCordappWorkflowVersion",
                    "-Pworkflow_cordapp_version=$WORKFLOW_CORDAPP_VERSION",
                    "-Pservice_name=With Service Metadata",
                    "-Pcordapp_service_version=$expectedCordappServiceVersion",
                    "-Pservice_cordapp_version=$SERVICE_CORDAPP_VERSION"
                )
        }
    }

    @Test
    fun testAllArtifactsPresent() {
        assertThat(testProject.artifacts)
            .anyMatch { it.toString().endsWith("contracts-$CONTRACT_CORDAPP_VERSION.jar") }
            .anyMatch { it.toString().endsWith("contracts-$CONTRACT_CORDAPP_VERSION-cordapp.cpk") }
            .anyMatch { it.toString().endsWith("workflows-$WORKFLOW_CORDAPP_VERSION.jar") }
            .anyMatch { it.toString().endsWith("workflows-$WORKFLOW_CORDAPP_VERSION-cordapp.cpk") }
            .anyMatch { it.toString().endsWith("services-$SERVICE_CORDAPP_VERSION.jar") }
            .anyMatch { it.toString().endsWith("services-$SERVICE_CORDAPP_VERSION-cordapp.cpk") }
            .hasSize(6)
    }

    @Test
    fun verifyContractMetadata() {
        val persistenceApiVersion = testProject.properties.getProperty("persistence_api_version")
        val contractJar = testProject.artifacts.single { it.toString().endsWith("contracts-$CONTRACT_CORDAPP_VERSION.jar") }
        assertThat(contractJar).isRegularFile()
        with(contractJar.manifest.mainAttributes) {
            assertThat(getValue(CORDA_CONTRACT_CLASSES))
                .isEqualTo("com.example.metadata.contracts.ExampleContract,com.example.metadata.contracts.ExampleContract\$NestedContract")
            assertThat(getValue(CORDA_MAPPED_SCHEMA_CLASSES))
                .isEqualTo("com.example.metadata.schemas.ExampleSchemaV1")
            assertThat(getValue(CORDA_WORKFLOW_CLASSES)).isNull()
            assertThat(getValue(CORDA_SERVICE_CLASSES)).isNull()
            assertThat(getValue(CORDAPP_CONTRACT_NAME))
                .isEqualTo("With Contract Metadata")
            assertThat(getValue(CORDAPP_CONTRACT_VERSION))
                .isEqualTo(expectedCordappContractVersion.toString())
            assertThat(getValue(BUNDLE_VERSION))
                .isEqualTo(toOSGi(CONTRACT_CORDAPP_VERSION))
            assertThatHeader(getValue(IMPORT_PACKAGE))
                .doesNotContainPackage(HIBERNATE_ANNOTATIONS_PACKAGE, HIBERNATE_PROXY_PACKAGE, JAVASSIST_PROXY_PACKAGE)
                .containsPackageWithAttributes(JAVAX_PERSISTENCE_PACKAGE, "version=${toOSGiRange(persistenceApiVersion)}")
            assertThatHeader(getValue(DYNAMICIMPORT_PACKAGE))
                .containsPackageWithAttributes(HIBERNATE_ANNOTATIONS_PACKAGE)
                .containsPackageWithAttributes(HIBERNATE_PROXY_PACKAGE)
                .containsPackageWithAttributes(JAVASSIST_PROXY_PACKAGE)
        }
    }

    @Test
    fun verifyContractCPKMetadata() {
        val contractCPK = testProject.artifacts.single { it.toString().endsWith("contracts-$CONTRACT_CORDAPP_VERSION-cordapp.cpk") }
        assertThat(contractCPK).isRegularFile()
        val mainAttributes = contractCPK.manifest.mainAttributes
        assertThat(mainAttributes.getValue(CPK_FORMAT_TAG)).isEqualTo(CPK_FORMAT)
    }

    @Test
    fun verifyWorkflowMetadata() {
        val workflowJar = testProject.artifacts.single { it.toString().endsWith("workflows-$WORKFLOW_CORDAPP_VERSION.jar") }
        assertThat(workflowJar).isRegularFile()
        with(workflowJar.manifest.mainAttributes) {
            assertThat(getValue(CORDA_WORKFLOW_CLASSES))
                .isEqualTo("com.example.metadata.workflows.ExampleFlow,com.example.metadata.workflows.ExampleFlow\$NestedFlow")
            assertThat(getValue(CORDA_CONTRACT_CLASSES)).isNull()
            assertThat(getValue(CORDA_MAPPED_SCHEMA_CLASSES)).isNull()
            assertThat(getValue(CORDA_SERVICE_CLASSES)).isNull()
            assertThat(getValue(CORDAPP_WORKFLOW_NAME))
                .isEqualTo("With Workflow Metadata")
            assertThat(getValue(CORDAPP_WORKFLOW_VERSION))
                .isEqualTo(expectedCordappWorkflowVersion.toString())
            assertThat(getValue(BUNDLE_VERSION))
                .isEqualTo(toOSGi(WORKFLOW_CORDAPP_VERSION))
            assertThatHeader(getValue(IMPORT_PACKAGE))
                .doesNotContainPackage(HIBERNATE_ANNOTATIONS_PACKAGE, HIBERNATE_PROXY_PACKAGE, JAVASSIST_PROXY_PACKAGE)
            assertThatHeader(getValue(DYNAMICIMPORT_PACKAGE))
                .containsPackageWithAttributes(HIBERNATE_ANNOTATIONS_PACKAGE)
                .containsPackageWithAttributes(HIBERNATE_PROXY_PACKAGE)
                .containsPackageWithAttributes(JAVASSIST_PROXY_PACKAGE)
        }
    }

    @Test
    fun verifyWorkflowCPKMetadata() {
        val workflowCPK = testProject.artifacts.single { it.toString().endsWith("workflows-$WORKFLOW_CORDAPP_VERSION-cordapp.cpk") }
        assertThat(workflowCPK).isRegularFile()
        val mainAttributes = workflowCPK.manifest.mainAttributes
        assertThat(mainAttributes.getValue(CPK_FORMAT_TAG)).isEqualTo(CPK_FORMAT)
    }

    @Test
    fun verifyServiceMetadata() {
        val serviceJar = testProject.artifacts.single { it.toString().endsWith("services-$SERVICE_CORDAPP_VERSION.jar") }
        assertThat(serviceJar).isRegularFile()
        with(serviceJar.manifest.mainAttributes) {
            assertThat(getValue(CORDA_SERVICE_CLASSES))
                .isEqualTo("com.example.metadata.services.ExampleService")
            assertThat(getValue(CORDA_WORKFLOW_CLASSES)).isNull()
            assertThat(getValue(CORDA_CONTRACT_CLASSES)).isNull()
            assertThat(getValue(CORDA_MAPPED_SCHEMA_CLASSES)).isNull()
            assertThat(getValue(CORDAPP_WORKFLOW_NAME))
                .isEqualTo("With Service Metadata")
            assertThat(getValue(CORDAPP_WORKFLOW_VERSION))
                .isEqualTo(expectedCordappServiceVersion.toString())
            assertThat(getValue(BUNDLE_VERSION))
                .isEqualTo(toOSGi(SERVICE_CORDAPP_VERSION))
            assertThatHeader(getValue(IMPORT_PACKAGE))
                .doesNotContainPackage(HIBERNATE_ANNOTATIONS_PACKAGE, HIBERNATE_PROXY_PACKAGE, JAVASSIST_PROXY_PACKAGE)
            assertThatHeader(getValue(DYNAMICIMPORT_PACKAGE))
                .containsPackageWithAttributes(HIBERNATE_ANNOTATIONS_PACKAGE)
                .containsPackageWithAttributes(HIBERNATE_PROXY_PACKAGE)
                .containsPackageWithAttributes(JAVASSIST_PROXY_PACKAGE)
        }
    }

    @Test
    fun verifyServiceCPKMetadata() {
        val serviceCPK = testProject.artifacts.single { it.toString().endsWith("services-$SERVICE_CORDAPP_VERSION-cordapp.cpk") }
        assertThat(serviceCPK).isRegularFile()
        val mainAttributes = serviceCPK.manifest.mainAttributes
        assertThat(mainAttributes.getValue(CPK_FORMAT_TAG)).isEqualTo(CPK_FORMAT)
    }
}