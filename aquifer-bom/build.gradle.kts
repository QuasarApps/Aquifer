import com.vanniktech.maven.publish.JavaPlatform

plugins {
    `java-platform`
    alias(libs.plugins.maven.publish)
}

group = "io.github.quasarapps"

// A BOM constrains, not depends: it pins each Aquifer artifact to this build's version so a
// consumer picks one version line and cannot mix modules (pre-1.0 minors may break binary
// compatibility across modules, per the CHANGELOG header).
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
