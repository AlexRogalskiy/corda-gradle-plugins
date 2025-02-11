package net.corda.plugins.cpk

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CordappWithConstraintTest {
    companion object {
        private const val cordappVersion = "1.0.1-SNAPSHOT"
        private const val library1Version = "1.2.3-SNAPSHOT"
        private const val library2Version = "1.2.4-SNAPSHOT"

        private lateinit var testProject: GradleProject

        @Suppress("unused")
        @BeforeAll
        @JvmStatic
        fun setup(@TempDir testProjectDir: Path, reporter: TestReporter) {
            testProject = GradleProject(testProjectDir, reporter)
                .withTestName("cordapp-with-constraint")
                .withSubResource("src/main/kotlin/com/example/constraint/ConstraintContract.kt")
                .withSubResource("library1/src/main/java/org/testing/compress/package-info.java")
                .withSubResource("library1/src/main/java/org/testing/compress/ExampleZip.java")
                .withSubResource("library1/build.gradle")
                .withSubResource("library2/src/main/java/org/testing/io/package-info.java")
                .withSubResource("library2/src/main/java/org/testing/io/ExampleStream.java")
                .withSubResource("library2/build.gradle")
                .build(
                    "-Pcordapp_version=$cordappVersion",
                    "-Pcordapp_contract_version=$expectedCordappContractVersion",
                    "-Pcorda_api_version=$cordaApiVersion",
                    "-Plibrary1_version=$library1Version",
                    "-Plibrary2_version=$library2Version",
                    "-Pcommons_io_version=$commonsIoVersion",
                    "-Pcommons_codec_version=$commonsCodecVersion",
                    "-Pcommons_compress_version=$commonsCompressVersion"
                )
        }
    }

    @Test
    fun testLibrariesWithConstraints() {
        assertThat(testProject.dependencyConstraints)
            .noneMatch { it.fileName.startsWith("commons-compress-") }
            .noneMatch { it.fileName.startsWith("library1-") }
            .anyMatch { it.fileName == "commons-io-$commonsIoVersion.jar" }
            .anyMatch { it.fileName == "commons-codec-$commonsCodecVersion.jar" }
            .anyMatch { it.fileName == "library2-$library2Version.jar" }
            .hasSizeGreaterThanOrEqualTo(3)
    }
}
