import com.github.gradle.node.npm.task.NpxTask
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    kotlin("multiplatform") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("com.github.node-gradle.node") version "7.0.2"
    id("io.4rc.zoned-js-plugin") version "1.0-SNAPSHOT"
        id("io.4rc.zoned") version "1.0-SNAPSHOT"
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
        // TODO buildStyle should move?
        implementation("zoned:zoned-js:1.0-SNAPSHOT")
        // TODO why here
        implementation(npm("nomnoml", "1.6.2"))
    }

    sourceSets["jsMain"].resources.srcDir(webDir)

    sourceSets["jsTest"].dependencies {
        implementation(kotlin("test-js"))
    }
}

tasks.register<Copy>("copyResources") {
    from("src/jvmMain/resources/assets")
    into("dist")
}

// TODO this should go in plugin
tasks.register<NpxTask>("buildStyle") {
    println("Rerunning tailwind!")
    command.set("build/js/node_modules/tailwindcss")
    args.set(listOf("-i", "src/jsMain/resources/template.css", "-o", "src/jsMain/resources/style.css"))
    // this is needed for build -t
    inputs.files(fileTree("src/jsMain/kotlin"))
    // maybe not this
    val outputDir = layout.projectDirectory.dir("dist")
    outputs.dir(outputDir)
}

//
//tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinCompile<*>> {
//    kotlinOptions {
//        freeCompilerArgs += "-XXLanguage:+JsAllowInvalidCharsIdentifiersEscaping"
//    }
//}
