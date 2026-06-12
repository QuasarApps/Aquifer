import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

group = "io.github.quasar-apps"
version = "0.1.0-SNAPSHOT"

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    api(project(":aquifer-core"))
    api(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.quasar-apps", "aquifer-persistence-file", version.toString())
    configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"), sourcesJar = true))

    pom {
        name.set("Aquifer Persistence (JSON files)")
        description.set("JSON-files SourceOfTruth for Aquifer: atomic writes, self-healing reads, kotlinx.serialization.")
        url.set("https://github.com/Quasar-Apps/aquifer")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("quasar-apps")
                name.set("Quasar Apps")
                url.set("https://github.com/Quasar-Apps")
            }
        }
        scm {
            url.set("https://github.com/Quasar-Apps/aquifer")
            connection.set("scm:git:git://github.com/Quasar-Apps/aquifer.git")
            developerConnection.set("scm:git:ssh://git@github.com/Quasar-Apps/aquifer.git")
        }
    }
}
