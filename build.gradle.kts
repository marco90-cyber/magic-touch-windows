import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.compose") version "1.6.11"
}

group = "com.marco.magictouchremove"
version = (findProperty("appVersion") as String?) ?: "1.0.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
}

val buildNumber = (findProperty("buildNumber") as String?) ?: "0"

tasks.processResources {
    inputs.property("build", buildNumber)
    filesMatching("version.properties") { expand(mapOf("build" to buildNumber)) }
}

compose.desktop {
    application {
        mainClass = "com.marco.magictouchremove.MainKt"
        jvmArgs += listOf("-XX:MaxRAMPercentage=75", "-Xss4m", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30", "-XX:G1PeriodicGCInterval=15000")
        nativeDistributions {
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appres"))
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "MagicTouchRemove"
            packageVersion = (findProperty("msiVersion") as String?) ?: "1.0.0"
            description = "Rimozione oggetti dalle foto con l'IA, in locale"
            vendor = "marco90-cyber"
            modules("java.net.http", "jdk.unsupported", "java.management", "jdk.management", "java.xml")
            windows {
                iconFile.set(project.file("icon.ico"))
                menuGroup = "Magic Touch Remove"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6d1f2b0e-7a1c-4c44-9f0b-3a5d7d2b9a11"
            }
        }
    }
}
