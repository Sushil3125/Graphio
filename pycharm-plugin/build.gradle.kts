// Gradle build of the Code Map PyCharm plugin (IntelliJ Platform Gradle Plugin 2.x).
//
//   ./gradlew buildPlugin     -> build/distributions/codemap-pycharm-<version>.zip  (Marketplace-ready)
//   ./gradlew verifyPlugin    -> JetBrains Plugin Verifier against the local IDE (or recommended IDEs)
//   ./gradlew runIde          -> sandbox IDE with the plugin installed
//   ./gradlew signPlugin / publishPlugin  -> need CERTIFICATE_CHAIN, PRIVATE_KEY, PRIVATE_KEY_PASSWORD, PUBLISH_TOKEN
//
// plugin.xml is the source of truth for id, name, version, description and change notes; this build only patches
// <idea-version> (since-build 253, no until-build). The web UI from ../codemap/web is bundled under /codemap-web/.

import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.sushil"

// Parallel agents/devs can use separate output dirs: ./gradlew -Pcodemap.buildDir=build-foo compileJava
providers.gradleProperty("codemap.buildDir").orNull?.let { layout.buildDirectory = layout.projectDirectory.dir(it) }

// ---- plugin.xml is the single source of truth for the version ------------------------------------------------
val pluginXml = layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml")
val pluginXmlVersion: String = Regex("<version>\\s*([^<\\s]+)\\s*</version>")
    .find(providers.fileContents(pluginXml).asText.get())?.groupValues?.get(1)
    ?: error("No <version> in ${pluginXml.asFile}")
version = pluginXmlVersion

// ---- IDE to build against ---------------------------------------------------------------------------------------
val ideLocalPath: String? = providers.gradleProperty("ideLocalPath").orNull
    ?.takeIf { it.isNotBlank() && file(it).resolve("product-info.json").isFile }
val platformVersion = providers.gradleProperty("platformVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        if (ideLocalPath != null) {
            local(ideLocalPath)
        } else {
            // Unified PyCharm (2025.3+).
            pycharm(platformVersion)
        }
        // PyCharm's Python code model (PSI, resolve, type inference). Also present in IntelliJ with the Python plugin.
        bundledPlugin("PythonCore")
        pluginVerifier()
        zipSigner()
        // IDE tests (BasePlatformTestCase, light fixtures); PythonCore is on the test classpath via bundledPlugin.
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    // The platform test framework references opentest4j (assertion failures) at runtime.
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

java {
    toolchain {
        // Any JDK 21 works; locally the PyCharm JBR is found via org.gradle.java.installations.paths (gradle.properties),
        // elsewhere foojay (settings.gradle.kts) downloads one.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

// ---- bundled web UI ---------------------------------------------------------------------------------------------
// The plugin serves ../codemap/web (index.html, app.js, style.css, vendor/elk) from the jar under /codemap-web/.
// Analysis runs in Java on PyCharm's Python model; the Python package in ../codemap is NOT bundled (standalone CLI only).
tasks.processResources {
    from(layout.projectDirectory.dir(providers.gradleProperty("codemapServerSource").map { "$it/web" })) {
        into("codemap-web")
        exclude("**/__pycache__/**", "**/.*")
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

// ---- IntelliJ Platform configuration ----------------------------------------------------------------------------
intellijPlatform {
    // Sandboxes (runIde, tests, exportGraph) live under the build dir, so -Pcodemap.buildDir=... isolates them too.
    sandboxContainer = layout.buildDirectory.dir("idea-sandbox")
    // No GUI forms; skipping instrumentation avoids downloading the java-compiler-ant-tasks.
    instrumentCode = false
    // Headless IDE run just to index settings text; not worth it for one settings page.
    buildSearchableOptions = false

    pluginConfiguration {
        // id / name / version / description / change-notes come from plugin.xml.
        version = pluginXmlVersion
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            if (ideLocalPath != null) local(file(ideLocalPath))
            else create(IntelliJPlatformType.PyCharm, platformVersion)
        }
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
        )
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
            .orElse(providers.gradleProperty("intellijPlatformPublishingToken"))
        // PUBLISH_CHANNEL=eap (or beta, …) publishes to a custom channel; default is the stable "default" channel.
        channels = providers.environmentVariable("PUBLISH_CHANNEL").map { listOf(it) }.orElse(listOf("default"))
    }
}

// ---- tests ------------------------------------------------------------------------------------------------------
tasks.test {
    useJUnit()
    // Headless IDE tests: keep them from touching the user's IDE config/system dirs (the plugin sets its own sandbox).
    systemProperty("java.awt.headless", "true")
    maxHeapSize = "2g"
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = providers.gradleProperty("codemap.testOutput").isPresent
    }
    providers.gradleProperty("codemap.debug").orNull?.let { systemProperty("codemap.debug", it) }
}

// ---- headless graph export --------------------------------------------------------------------------------------
//   ./gradlew exportGraph -PexportProject=/path/to/COPY/of/project -PexportOut=/path/out.json
// Runs the IDE headless with its own sandbox (build/idea-sandbox/...-exportGraph) and the "codemap-export"
// ApplicationStarter. Opening a directory writes .idea/ into it: only point it at copies.
val exportArgs: List<String> = listOfNotNull(
    providers.gradleProperty("exportProject").orNull,
    providers.gradleProperty("exportOut").orNull,
).let { pos ->
    if (pos.isEmpty()) emptyList()
    else listOf("codemap-export") + pos +
        listOfNotNull(providers.gradleProperty("exportTimeoutSec").orNull).flatMap { listOf("--timeout-sec", it) } +
        listOfNotNull(providers.gradleProperty("exportSdk").orNull).flatMap { listOf("--sdk", it) }
}
val exportXmx: String = providers.gradleProperty("exportXmx").getOrElse("3g")
val exportWorkers: String? = providers.gradleProperty("exportWorkers").orNull
intellijPlatformTesting.runIde.register("exportGraph") {
    task {
        args(exportArgs)
        jvmArgs(
            "-Djava.awt.headless=true",
            "-Didea.trust.all.projects=true",
            "-Didea.initially.ask.config=never",
            "-Djb.consents.confirmation.enabled=false",
            "-Djb.privacy.policy.text=<!--999.999-->",
            "-Dide.show.tips.on.startup.default.value=false",
            "-Dide.no.platform.update=true",
            "-Didea.suppress.statistics.report=true",
        )
        maxHeapSize = exportXmx
        if (exportWorkers != null) jvmArgs("-Dcodemap.workers=$exportWorkers")
    }
}
