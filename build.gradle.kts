import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.binary.compatibility.validator)
}

// Static analysis: detekt (with the bundled ktlint formatting rules) on every Kotlin module,
// sharing one config. detekt registers itself into `check`, so `./gradlew build` enforces it.
val detektFormatting = libs.detekt.formatting
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
    }

    dependencies {
        "detektPlugins"(detektFormatting)
    }
}

// CI-only: run every Test task on a specific JDK launcher so JVM-11 bytecode is proven to
// execute on a real JDK 11 runtime (compilation still targets JVM_11 regardless). Off by
// default — normal builds run tests on the Gradle daemon JDK, unchanged. CI enables it with
// -PtestJvm=11; the JDK is provisioned by setup-java and discovered via
// -Dorg.gradle.java.installations.fromEnv (no foojay, no auto-download).
providers.gradleProperty("testJvm").map(String::toInt).orNull?.let { testJvm ->
    val requested = JavaLanguageVersion.of(testJvm)
    subprojects {
        // Resolve the service per-subproject: java-base (applied by the Kotlin JVM / Android
        // plugins) registers JavaToolchainService on the subproject, not on the root project.
        plugins.withId("java-base") {
            val launcher = extensions.getByType<JavaToolchainService>()
                .launcherFor { languageVersion.set(requested) }
            tasks.withType<Test>().configureEach {
                javaLauncher.set(launcher)
            }
        }
    }
}

// The release workflow verifies the tag against every module configured for publication. It used a
// hardcoded list that silently went stale — two modules were added to the publishing set without
// being added to the gate, so either could ship at a version the tag never claimed. Deriving the
// list here means a new publishing module is covered the moment it applies the plugin.
val publishingModulePaths = mutableListOf<String>()
subprojects {
    plugins.withId("com.vanniktech.maven.publish") { publishingModulePaths += path }
}

tasks.register("publishingModules") {
    group = "help"
    description = "Prints the project path of every module configured to publish to Maven Central."
    // Populated by the time configuration finishes, which is when the task action (and the
    // configuration-cache entry) captures it.
    val paths = publishingModulePaths
    doLast { paths.sorted().forEach(::println) }
}

// aquifer-bom's constraint list is maintained by hand (a java-platform can't derive it: plugins.withId
// hasn't fired for the sibling modules when its script is evaluated). Capture what it constrains —
// after its dependencies block has run — so verifyBomCoverage can prove it covers every publishing
// module. Without this, adding an eighth publishing module and forgetting the BOM would under-constrain
// it silently, surfacing only in a consumer's versionless resolve instead of in this build — the exact
// staleness the release gate above was reworked to avoid.
val bomConstraintPaths = mutableListOf<String>()
project(":aquifer-bom").afterEvaluate {
    // ":${it.name}" reconstructs the project path from the constraint's artifact name; this holds
    // only because every module is top-level (path == ":" + name). A nested module (say
    // ":integrations:aquifer-foo", name "aquifer-foo") would mismatch — revisit this if one is added.
    configurations.getByName("api").dependencyConstraints.forEach { bomConstraintPaths += ":${it.name}" }
}

tasks.register("verifyBomCoverage") {
    group = "verification"
    description = "Fails if aquifer-bom's constraints and the publishing modules disagree either way."
    val expected = publishingModulePaths
    val covered = bomConstraintPaths
    doLast {
        val expectedSet = expected.toSet()
        val coveredSet = covered.toSet()
        // Under-coverage: a publishing module the BOM forgot — a consumer's versionless declaration
        // of it then fails to resolve, with no clue the BOM was meant to cover it.
        val missing = (expectedSet - coveredSet - ":aquifer-bom").sorted()
        check(missing.isEmpty()) {
            "aquifer-bom is missing version constraints for: ${missing.joinToString()}. " +
                "Add api(project(\"<path>\")) for each in aquifer-bom/build.gradle.kts."
        }
        // Over-coverage: the BOM constrains something never published, so a versionless declaration
        // of it resolves to a version that does not exist on Maven Central.
        val extra = (coveredSet - expectedSet).sorted()
        check(extra.isEmpty()) {
            "aquifer-bom constrains non-publishing module(s): ${extra.joinToString()}. " +
                "Remove the api(project(...)) constraint, or make the module publish."
        }
    }
}

// Aggregated API docs for the published modules: ./gradlew dokkaGenerate
dependencies {
    dokka(project(":aquifer-core"))
    dokka(project(":aquifer-test"))
    dokka(project(":aquifer-persistence-file"))
    dokka(project(":aquifer-persistence-sqldelight"))
    dokka(project(":aquifer-android"))
    dokka(project(":aquifer-compose"))
    dokka(project(":aquifer-okhttp"))
}

dokka {
    moduleName.set("Aquifer")
}

// Public API surface is locked in api/*.api dumps; apiCheck runs as part of `check`.
// After an intentional API change, regenerate with: ./gradlew apiDump
apiValidation {
    ignoredProjects += listOf("sample")
    // The SQLDelight-generated database classes are an implementation detail regenerated from the
    // .sq schema, not a hand-authored public contract, so they are excluded from the locked API.
    ignoredPackages += listOf("io.github.quasarapps.aquifer.persistence.sqldelight.db")
}
