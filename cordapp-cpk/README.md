# Cordapp CPK Gradle Plugin.

## Purpose.
Applying this plugin to a project declares that the project should create a CPK-format CorDapp. The CPK-format
CorDapp is a ZIP file with a `.cpk` extension that contains the output of the `jar` task (the "main" jar), along
with that jar's dependent jars. In practice, the plugin will not include any of Corda's own jars among these
dependencies, nor any jars which should be provided by Corda, e.g. Kotlin or Quasar. The "main" jar  should also
contain sufficient OSGi metadata to be a valid OSGi bundle.

## Usage.
Include these lines at the top of the `build.gradle` file of the Gradle project:

```gradle
plugins {
    id 'net.corda.plugins.cordapp-cpk'
}
```
You will also need to declare the plugin version in `settings.gradle`:
```gradle
pluginManagement {
    plugins {
        id 'net.corda.plugins.cordapp-cpk' version cpkPluginVersion
    }
}
```
where `cpkPluginVersion` is a Gradle property:
```
cpkPluginVersion = '6.0.0'
```

Applying the `cordapp-cpk` plugin implicitly applies both Gradle's `java-library` plugin and Bnd's `builder` plugin,
which means that the output of the `jar` task will also become an OSGi bundle. The `cordapp-cpk` plugin assigns
the following default OSGi attributes to the bundle:

- `Bundle-SymbolicName`: `${project.group}.${archiveBaseName}[-${archiveAppendix}][-${archiveClassifier}]`
- `Bundle-Version`: `${project.version}`

### DSL.

The plugin creates a `cordapp` DSL extension, which currently bears a strong resemblance to the
legacy `cordapp` plugin's DSL extension:
```groovy
cordapp {
    targetPlatformVersion = '<Corda 5 Platform Version>'
    minimumPlatformVersion = '<Corda 5 Platform Version>'

    contract {
        name = 'Example CorDapp'
        versionId = 1
        licence = 'Test-Licence'
        vendor = 'R3'
    }

    workflow {
        name = 'Example CorDapp'
        versionId = 1
        licence = 'Test-Licence'
        vendor = 'R3'
    }

    signing {
        enabled = (true | false)

        // These options presumably mirror Ant's signJar task options.
        options {
            alias = '??'
            storepass = '??'
            keystore = '??'
            storetype= '??'
            keypass = '??'
            sigfile = '??'
            signedJar = '??'
            verbose = '??'
            strict = '??'
            internalsf = '??'
            sectionsonly = '??'
            lazy = '??'
            maxmemory = '??'
            preservelastmodified = '??'
            tsaurl = '??'
            tsacert = '??'
            tsaproxyhost = '??'
            tsaproxyport = '??'
            executable = '??'
            force = '??'
            sigalg = '??'
            digestalg = '??'
            tsadigestalg = '??'
        }
    }

    sealed {
        enabled = (true | false)
    }
}
```

This extension is likely to change as Corda 5 matures and its requirements evolve. The `name`,
`licence` and `vendor` fields are mapped to the `Bundle-Name`, `Bundle-License` and `Bundle-Vendor`
OSGi manifest tags respectively.

### Configurations.

Applying `cordapp-cpk` creates the following new Gradle configurations:

- `cordaProvided`: This configuration declares a dependency that the CorDapp needs to compile against,
but which should not become part of the CPK since it will be provided by the Corda node at runtime.
This effectively replaces the legacy `cordaCompile` configuration. Any `cordaProvided` dependency
is also implicitly added to Gradle's `compileOnly` and `*Implementation` configurations, and consequently
is not included either in the `runtimeClasspath` configuration, as a dependency in the published POM
file, or packaged inside the CPK file. However, it will be included in the `testRuntimeClasspath`
configuration.

- `cordapp`: This declares a compile-time dependency against the "main" jar of another CPK CorDapp.
As with `cordaProvided`, the dependency is also added implicitly to Gradle's `compileOnly` and
`*Implementation` configurations, and is excluded from the `runtimeClasspath` configuration, the
published POM file, and the contents of the CPK file. The "main" jars of all `cordapp` dependencies
are listed as lines in this "main" jar's `META-INF/CPKDependencies` file:
```
<Bundle-SymbolicName>,<Bundle-Version>
```
`cordapp` dependencies are currently _not_ transitive, and so all dependent CorDapps must be
declared explicitly in the `build.gradle` file. This is due to limitations of Gradle and POM files.
(We would likely need to preserve relationships between CPKs in a separate POM, as there is no other
way to recognise which among all `compile`-scoped dependencies are CPKs. This approach is something
I intend to investigate later.)

- `cordaEmbedded`: This configuration behaves similarly to `cordaProvided` in the sense that it
declares a `compileOnly` dependency that is excluded from both the CPK contents and from the
published POM. The difference is that the dependent jar is also added to a `lib/` folder inside the
CorDapp's "main" jar, and appended to the jar's `Bundle-Classpath` manifest attribute. Note that
an OSGi framework considers a `Bundle-Classpath` to contain ordinary jars and not bundles, even
if those jars contain OSGi metadata of their own. Note also that the embedded jars' transitive
dependencies will be embedded too, unless they are explicitly added to another Gradle configuration.
_Use embedding with care!_ It is provided as a tool for those cases where a dependency has no OSGi
metadata, or its metadata is somehow unusable. I strongly recommend _not_ embedding dependencies
which already have valid OSGi metadata.

- `cordaRuntimeOnly`: This declares a dependency that will be added to the `runtimeClasspath`, but
which the CorDapp doesn't need to compile against and must not be packaged into the CPK file either.
It replaces the legacy `cordaRuntime` configuration.

The legacy `cordaCompile` and `cordaRuntime` configurations are built upon Gradle's deprecated
`compile` and `runtime` configurations, which will likely be removed in later versions of Gradle.
We need to replace both `cordaCompile` and `cordaRuntime` before this happens.

# Tasks.

## External Tasks.

- `jar`: This is the standard `Jar` task created by Gradle's `java-library` plugin, and
then enhanced by Bnd's `builder` plugin to create an OSGi bundle.

- `cpk`: This task creates a `.cpk` file with the output from `jar` as its "main" jar. The
contents of the jar's `runtimeClasspath` configuration is added to the CPK's `lib/` folder,
except for those jars which have been declared as either a `cordapp`, `cordaProvided`,
`cordaEmbedded` or `cordaRuntimeOnly` dependency.

The `jar` and `cpk` tasks are both automatic dependencies of Gradle's `assemble` task.

## Internal Tasks.

These tasks perform intermediate steps as part of creating a CPK.

- `cordappDependencyCalculator`: Calculates which jars belong to which part of a CPK's packaging.

- `cordappDependencyConstraints`: Generates the "main" jar's `META-INF/DependencyConstraints` file.

- `cordappCPKDependencies`: Generates the "main" jar's `META-INF/CPKDependencies` file.

- `verifyBundle`: Verifies that the "main" jar's OSGi metadata is consistent with the packages
that have been included in the CPK. This task uses Bnd's `Verifier` class with "strict" verification
enabled to ensure that every `Import-Package` element has an associated version too.

# OSGi Metadata.

The `cordapp-cpk` plugin automatically adds these dependencies to the CorDapp:
```groovy
compileOnly "biz.aQute.bnd:biz.aQute.bnd.annotation:$bndVersion"
compileOnly "org.osgi:osgi.annotation:7.0.0"
```

These annotations [control how Bnd will generate OSGi metadata](https://bnd.bndtools.org/chapters/230-manifest-annotations.html)
for the "main" jar. In practice, the plugin already tries to handle the typical cases for creating CorDapps.

## Package Exports

The `cordapp-cpk` plugin creates a Bnd `-exportcontents` command to generate the "main" jar's OSGi
`Export-Package` header. By default, it will automatically add every package inside the "main" jar to this
`-exportcontents` command. The assumption here is that a CorDapp will not have a complicated package structure,
and that Corda's OSGi sandboxes will provide additional CorDapp isolation anyway.

CorDapp developers who wish to configure their package exports more precisely can disable this default behaviour
from the `jar` task:
```groovy
tasks.named('jar', Jar) {
    osgi {
        autoExport = false
    }
}
```
You can then apply `@org.osgi.annotation.bundle.Export` annotations "by hand" to selected `package-info.java`
files.

You can also export package names explicitly, although applying `@Export` annotations would still be better:
```groovy
tasks.named('jar', Jar) {
    osgi {
        exportPackage 'com.example.cordapp', 'com.example.cordapp.foo'
    }
}
```

## Package Imports

In an ideal world, Bnd would generate the correct OSGi `Import-Package` manifest header automatically. That being
said, Bnd will occasionally also notice unexpected package references from unused code-paths within the byte-code.
The `cordapp-cpk` plugin provides the following options to override the detected package settings:
```groovy
tasks.named('jar', Jar) {
    osgi {
        // Declares that this CorDapp requires the OSGi framework to provide the 'com.example.cordapp' package.
        // This value is passed straight through to Bnd.
        importPackage 'com.example.cordapp'

        // Declares that this CorDapp uses the 'com.example.cordapp.foo` package.
        // However, Corda will not complain if no-one provides it at runtime. This
        // assumes that the missing package isn't really required at all.
        optionalImport 'com.example.cordapp.foo'

        // Like `optionalImport`, except that it also assigns this package an empty
        // version range. This is useful when the unused package doesn't have a version
        // range of its own because it does not belong to another OSGi bundle.
        suppressImportVersion 'com.example.cordapp.bar'
    }
}
```

## ServiceLoader

Bundles that use `java.util.ServiceLoader` require special handling to support their `META-INF/services/` files.
Bnd provides [`@ServiceProvider` and `@ServiceConsumer` annotations](https://bnd.bndtools.org/chapters/240-spi-annotations.html)
to ensure that the bundle respects OSGi's [Service Loader Mediator Specification](https://docs.osgi.org/specification/osgi.cmpn/7.0.0/service.loader.html).

    <sup>Requires Gradle 6.6</sup>