plugins {
    kotlin("jvm") version "2.2.21" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
    jacoco
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "jacoco")

    group = "su.kamil.dev"
    version = "1.0-SNAPSHOT"

    dependencyLocking {
        lockAllConfigurations()
    }

    jacoco {
        toolVersion = "0.8.12"
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        ignoreFailures = true
    }

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        ignoreFailures.set(true)
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
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = true
        }
    }

    tasks.withType<Jar> {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

val jacocoRootReport = tasks.register<JacocoReport>("jacocoRootReport") {
    group = "verification"
    description = "Generates aggregated JaCoCo code coverage report across all modules"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "test" } })

    val executionDataFiles = files(subprojects.map {
        file("${it.layout.buildDirectory.asFile.get()}/jacoco/test.exec")
    })
    executionData.setFrom(executionDataFiles)

    val classDirs = files(subprojects.map {
        fileTree("${it.layout.buildDirectory.asFile.get()}/classes/kotlin/main")
    })
    classDirectories.setFrom(classDirs)

    val sourceDirs = files(subprojects.map {
        "${it.projectDir}/src/main/kotlin"
    })
    sourceDirectories.setFrom(sourceDirs)

    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.register("testAll") {
    group = "verification"
    description = "Runs all unit and integration tests across all modules in one command"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "test" } })
    finalizedBy(jacocoRootReport)
}

tasks.register("detektAll") {
    group = "verification"
    description = "Runs Detekt static analysis across all modules"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "detekt" } })
}

tasks.register("ktlintAll") {
    group = "verification"
    description = "Runs Ktlint checks across all modules"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "ktlintCheck" } })
}

tasks.register("bundleMinimalModel") {
    group = "distribution"
    description = "Downloads the minimal GGML model (ggml-tiny.bin) into models/ for offline archive distribution"
    doLast {
        val modelsDir = file("models")
        modelsDir.mkdirs()
        val target = file("models/ggml-tiny.bin")
        if (!target.exists() || target.length() < 1024 * 1024) {
            println("Downloading minimal audio model (ggml-tiny.bin) for offline archive...")
            val url = java.net.URI("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin").toURL()
            url.openStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            println("Minimal model bundled at: ${target.absolutePath} (${target.length() / (1024 * 1024)} MB)")
        } else {
            println("Minimal model already present at: ${target.absolutePath}")
        }
    }
}