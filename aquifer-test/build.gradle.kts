import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
    // Publishes the SourceOfTruth contract suite as a separate `test-fixtures` variant of this
    // module rather than as part of its main surface, so JUnit does not become a transitive
    // dependency of consumers who only want `fakeAquifer`, `FakeClock` or `settle()`.
    `java-test-fixtures`
}

group = "io.github.quasarapps"

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
    api(libs.kotlinx.coroutines.core)
    // settle() takes TestScope as its receiver, so the coroutine test library is part of this
    // module's public API surface, not an implementation detail.
    api(libs.kotlinx.coroutines.test)

    // The contract suite's own surface: a subclass writes `@Test`-free Kotlin, but it inherits
    // JUnit-annotated methods and calls kotlin.test assertions, so both are `api` *of the fixtures
    // variant* — which is precisely the separation this variant buys. They stay off the main
    // surface above.
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.kotlin.test.junit5)
    testFixturesApi(libs.junit.jupiter)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.turbine)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.quasarapps", "aquifer-test", version.toString())
    configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"), sourcesJar = true))

    pom {
        name.set("Aquifer Test")
        description.set("Test utilities for Aquifer: a programmable fake Aquifer, an in-memory SourceOfTruth, a deterministic clock, and a settle helper.")
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
