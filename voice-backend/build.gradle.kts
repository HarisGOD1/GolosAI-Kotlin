plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":system-utils")) {
        isTransitive = false
    }
}
