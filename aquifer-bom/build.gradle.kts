import com.vanniktech.maven.publish.JavaPlatform

plugins {
    `java-platform`
    alias(libs.plugins.maven.publish)
}

group = "io.github.quasarapps"

// A BOM constrains, not depends: it supplies the version for each Aquifer artifact a consumer
// declares *without* one, so importing the platform aligns the modules on this build's version.
// These are ordinary, overridable platform constraints — not `enforcedPlatform`: an explicit or
// transitive version still wins, the standard BOM contract and the right one for a library (forcing
// a version onto a consumer's graph is not ours to do). It matters pre-1.0, where a minor may break
// binary compatibility across modules, so declaring one version keeps them in step by default.
dependencies {
    constraints {
        api(project(":aquifer-core"))
        api(project(":aquifer-test"))
        api(project(":aquifer-persistence-file"))
        api(project(":aquifer-persistence-sqldelight"))
        api(project(":aquifer-android"))
        api(project(":aquifer-compose"))
        api(project(":aquifer-okhttp"))
    }
}

// The constraint list above is hand-maintained; verifyBomCoverage (root) fails the build if it omits
// any publishing module, so it can't silently go stale the way the release gate's hardcoded list once
// did. Wired into check so `./gradlew build` — what CI runs — enforces it.
tasks.named("check") { dependsOn(rootProject.tasks.named("verifyBomCoverage")) }

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.quasarapps", "aquifer-bom", version.toString())
    configure(JavaPlatform())

    pom {
        name.set("Aquifer BOM")
        description.set("Bill of materials pinning all Aquifer modules to one version.")
        url.set("https://github.com/QuasarApps/aquifer")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("quasarapps")
                name.set("Quasar Apps")
                url.set("https://github.com/QuasarApps")
            }
        }
        scm {
            url.set("https://github.com/QuasarApps/aquifer")
            connection.set("scm:git:git://github.com/QuasarApps/aquifer.git")
            developerConnection.set("scm:git:ssh://git@github.com/QuasarApps/aquifer.git")
        }
    }
}
