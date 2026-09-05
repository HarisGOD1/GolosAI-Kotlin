plugins {
    kotlin("jvm") version "2.2.21" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = "su.kamil.dev"
    version = "1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    dependencies {
        val coroutinesVersion = "1.8.1"
        val slf4jVersion = "2.0.13"

        "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
        "implementation"("org.slf4j:slf4j-api:$slf4jVersion")
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
        "testImplementation"("io.mockk:mockk:1.13.10")
        "testImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    }

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}