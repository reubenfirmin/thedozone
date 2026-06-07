import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    kotlin("multiplatform") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("com.github.node-gradle.node") version "7.1.0"
    id("io.4rc.zoned.plugin") version "1.0-SNAPSHOT"
}

version = "1.0.0-SNAPSHOT"
group = "zone"

repositories {
    mavenCentral()
    mavenLocal()
    // zoned-js pulls a patched kotlinx-html from jitpack transitively.
    maven("https://jitpack.io")
}

val webDir = file("src/main/web")

kotlin {
    jvmToolchain(21)

    js(IR) {
        browser {
            // Leaflet's wrapper does require("leaflet/dist/leaflet.css"); enable the css/style loaders
            // so webpack can bundle .css imports (shared across run/webpack/test tasks).
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }
            runTask {
                mainOutputFileName.set("main.bundle.js")
                sourceMaps = true
                devServerProperty.set(KotlinWebpackConfig.DevServer(
                    open = false,
                    port = 3000,
                    proxy = null,
                    static = mutableListOf("${layout.buildDirectory.get()}/processedResources/js/main")
                ))
            }
            webpackTask {
                mainOutputFileName.set("main.bundle.js")
                sourceMaps = true
            }
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
    }

    sourceSets["jsMain"].dependencies {
        implementation("io.4rc:zoned-js:1.0-SNAPSHOT")
        implementation(npm("nomnoml", "1.7.0"))
        implementation(npm("leaflet", "1.9.4"))
        implementation(npm("dropbox", "10.34.0"))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    }

    sourceSets["jsMain"].resources.srcDir(webDir)

    sourceSets["jsTest"].dependencies {
        implementation(kotlin("test-js"))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    }
}
//
//tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinCompile<*>> {
//    kotlinOptions {
//        freeCompilerArgs += "-XXLanguage:+JsAllowInvalidCharsIdentifiersEscaping"
//    }
//}
