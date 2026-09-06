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
    implementation("org.yaml:snakeyaml:2.2")
}

tasks.register<Zip>("packageWindowsZip") {
    group = "distribution"
    description = "Packages standalone Windows distribution with 1-click install.bat"
    dependsOn("installDist")
    archiveBaseName.set("GolosAI-Windows-x64")
    from(layout.buildDirectory.dir("install/application")) {
        into("GolosAI")
    }
    from(rootProject.file("packaging/windows/install.bat")) {
        into("GolosAI")
    }
    from(rootProject.file("packaging/windows/uninstall.bat")) {
        into("GolosAI")
    }
    from(rootProject.file("README.md")) {
        into("GolosAI")
    }
}

tasks.register<Tar>("packageLinuxTarGz") {
    group = "distribution"
    description = "Packages standalone Linux distribution with 1-click install.sh"
    dependsOn("installDist")
    archiveBaseName.set("GolosAI-Linux-x64")
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
    from(layout.buildDirectory.dir("install/application")) {
        into("GolosAI")
    }
    from(rootProject.file("packaging/linux/install.sh")) {
        into("GolosAI")
        filePermissions { unix(493) } // 0755
    }
    from(rootProject.file("packaging/linux/uninstall.sh")) {
        into("GolosAI")
        filePermissions { unix(493) } // 0755
    }
    from(rootProject.file("README.md")) {
        into("GolosAI")
    }
}

tasks.register<Zip>("packageMacOsZip") {
    group = "distribution"
    description = "Packages standalone macOS distribution with 1-click install.sh"
    dependsOn("installDist")
    archiveBaseName.set("GolosAI-macOS-universal")
    from(layout.buildDirectory.dir("install/application")) {
        into("GolosAI")
    }
    from(rootProject.file("packaging/macos/install.sh")) {
        into("GolosAI")
        filePermissions { unix(493) } // 0755
    }
    from(rootProject.file("packaging/macos/uninstall.sh")) {
        into("GolosAI")
        filePermissions { unix(493) } // 0755
    }
    from(rootProject.file("README.md")) {
        into("GolosAI")
    }
}

tasks.register("packageAllDistributions") {
    group = "distribution"
    description = "Builds standalone release packages for Windows, Linux, and macOS"
    dependsOn("packageWindowsZip", "packageLinuxTarGz", "packageMacOsZip")
}
