plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":core"))
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
}
