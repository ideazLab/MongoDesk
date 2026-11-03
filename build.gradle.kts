import org.jetbrains.compose.desktop.application.dsl.TargetFormat
plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.compose") version "1.9.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}
group = "com.ideazlab"
val appVersion = file("VERSION").readText().trim()
require(appVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "VERSION must contain a stable three-part version." }
version = appVersion
val generatedVersionResources = layout.buildDirectory.dir("generated/version-resources")
val generateAppVersion by tasks.registering {
    inputs.file(file("VERSION"))
    outputs.dir(generatedVersionResources)
    doLast {
        generatedVersionResources.get().file("build-info.properties").asFile.apply {
            parentFile.mkdirs()
            writeText("version=$appVersion\nrepository=ideazLab/MongoDesk\n")
        }
    }
}
sourceSets.main { resources.srcDir(generatedVersionResources) }
tasks.named("processResources") { dependsOn(generateAppVersion) }
// CI packages have no Developer ID certificate. Local builds keep mandatory persistent signing.
val ciUnsigned = providers.gradleProperty("ciUnsigned").map { it.toBooleanStrict() }.orElse(false)
kotlin { jvmToolchain(21) }
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation("org.mongodb:mongodb-driver-kotlin-coroutine:5.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("com.github.javakeyring:java-keyring:1.0.0")
    // Keyring's old transitive JNA predates Apple Silicon support.
    implementation("net.java.dev.jna:jna:5.19.1")
    implementation("net.java.dev.jna:jna-platform:5.19.1")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("de.bwaldvogel:mongo-java-server:1.47.0")
    testImplementation("io.netty:netty-transport:4.2.2.Final")
    testImplementation("org.apache.sshd:sshd-core:2.16.0")
}
compose.desktop {
    application {
        mainClass = "com.ideazlab.mongodesk.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "MongoDesk"
            packageVersion = appVersion
            description = "MongoDB workspace for browsing and querying data"
            vendor = "Ideazlab"
            includeAllModules = true
            windows {
                // Install for all users and expose the native Windows launch entry points.
                perUserInstall = false
                menu = true
                menuGroup = "MongoDesk"
                shortcut = true
                dirChooser = true
                iconFile.set(project.file("packaging/icons/MongoDesk.ico"))
                // Stable across releases so future MSI packages upgrade the existing installation.
                upgradeUuid = "9c75d5db-8838-5331-aeb9-6c5d0d9a91ed"
            }
            linux { iconFile.set(project.file("packaging/icons/MongoDesk.png")) }
            macOS {
                bundleID = "com.ideazlab.mongodesk"
                iconFile.set(project.file("packaging/icons/MongoDesk.icns"))
            }
        }
    }
}

// A local signing certificate has a stable identity across rebuilds. The stock
// Compose signing flow targets Apple distribution identities, so local app images
// are signed inside-out with our persistent identity after packaging.
tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    inputs.file(project.file("packaging/sign-macos-app.py"))
    inputs.property("ciUnsigned", ciUnsigned)
    val signingSettings = project.file(".signing/macos.properties")
    if (signingSettings.isFile) inputs.file(signingSettings)
    doLast {
        if (System.getProperty("os.name").startsWith("Mac") && !ciUnsigned.get()) {
            val variant = if (name == "createReleaseDistributable") "main-release" else "main"
            project.exec {
                commandLine("python3", project.file("packaging/sign-macos-app.py").absolutePath,
                    layout.buildDirectory.dir("compose/binaries/$variant/app/MongoDesk.app").get().asFile.absolutePath)
            }
        } else if (System.getProperty("os.name").startsWith("Mac")) {
            logger.lifecycle("CI package: persistent local signing explicitly skipped (-PciUnsigned=true). No Developer ID signing or notarization is performed.")
        }
    }
}

// Install a fresh signed bundle: merging into an older .app invalidates its seal.
tasks.register<Exec>("installMacosApp") {
    dependsOn("createDistributable")
    onlyIf { System.getProperty("os.name").startsWith("Mac") }
    commandLine("python3", project.file("packaging/install-macos-app.py").absolutePath)
}

// Explicit, user-approved legacy credential migration; never part of startup.
tasks.register<JavaExec>("migrateLocalCredentials") {
    group = "application"
    description = "Move legacy Keychain connection profiles into encrypted local storage."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.ideazlab.mongodesk.MigrateLocalCredentials")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
