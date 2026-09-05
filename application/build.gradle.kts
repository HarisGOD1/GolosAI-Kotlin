plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("su.kamil.dev.golos.app.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":voice-backend"))
    implementation(project(":system-utils"))
    implementation("ch.qos.logback:logback-classic:1.5.6")
}
