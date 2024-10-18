import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    kotlin("multiplatform") version "2.0.20"
    kotlin("plugin.serialization") version "2.0.20"
    id("com.github.node-gradle.node") version "7.0.2"
    id("io.4rc.zoned-js-plugin") version "1.0-SNAPSHOT"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

version = "1.0.0-SNAPSHOT"
group = "zone"

repositories {
    mavenCentral()
    mavenLocal()
}

val webDir = file("src/main/web")

kotlin {
    js(IR) {
        browser {
            runTask {
                outputFileName = "main.bundle.js"
                sourceMaps = true
                devServer = KotlinWebpackConfig.DevServer(
                    open = false,
                    port = 3000,
                    proxy = null,
                    static = mutableListOf("$buildDir/processedResources/js/main")
                )
            }
            webpackTask {
                mainOutputFileName = "main.bundle.js"
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
        implementation("zoned:zoned-js:1.0-SNAPSHOT")

        implementation(npm("ace-builds", "1.30.0"))
        implementation(npm("file-loader", "6.2.0")) // required by ace-builds, TODO double check
        implementation(npm("path-browserify", "1.0.1")) // required by ace-builds, TODO double check
        implementation(npm("crypto-browserify", "3.12.0")) // required by ace-builds, TODO double check
        implementation(npm("stream-browserify", "3.0.0")) // required by ace-builds, TODO double check
        implementation(npm("buffer", "6.0.3")) // required by ace-builds

        implementation(npm("dragula", "3.7.3"))
        implementation(npm("file-saver", "2.0.5"))
        implementation(npm("browser-fs-access", "0.31.1"))
        implementation(npm("html-to-image", "1.11.11"))
        implementation(npm("microlight", "0.0.7"))
        implementation(npm("leaflet", "1.9.4"))
        implementation(npm("nomnoml", "1.6.2"))
    }

    sourceSets["jsMain"].resources.srcDir(webDir)

    sourceSets["jsTest"].dependencies {
        implementation(kotlin("test-js"))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinCompile<*>> {
    kotlinOptions {
        freeCompilerArgs += "-XXLanguage:+JsAllowInvalidCharsIdentifiersEscaping"
    }
}
