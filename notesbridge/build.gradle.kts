plugins {
    alias(libs.plugins.android.library)
}

// The bridge runs inside the patched Samsung Notes clone (scripts/notes-clone), not in this
// app. Plain Java on purpose: no Kotlin runtime or other library that could clash with the
// ones Samsung Notes already ships. The app only shares the AIDL interfaces.
android {
    namespace = "io.github.gaiser147.notesbridge"
    compileSdk = 35

    defaultConfig {
        // Samsung Notes 4.4 needs Android 10.
        minSdk = 29
    }

    buildFeatures {
        aidl = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/**
 * notesbridge.dex: the bridge as one dex file, which patch.sh adds to the clone as its next
 * classesN.dex.
 */
val bridgeDex by tasks.registering(Exec::class) {
    val javac = tasks.named<JavaCompile>("compileReleaseJavaWithJavac")
    dependsOn(javac)
    val classes = javac.flatMap { it.destinationDirectory }
    val out = layout.buildDirectory.dir("outputs/dex")
    inputs.dir(classes)
    outputs.file(out.map { it.file("notesbridge.dex") })
    val sdk = android.sdkDirectory
    val buildTools = android.buildToolsVersion
    doFirst {
        val dir = out.get().asFile.apply { deleteRecursively(); mkdirs() }
        val files = fileTree(classes).matching { include("**/*.class") }.files.map { it.absolutePath }.sorted()
        commandLine(
            listOf(
                sdk.resolve("build-tools/$buildTools/d8").absolutePath,
                "--release", "--min-api", "29",
                "--lib", sdk.resolve("platforms/android-35/android.jar").absolutePath,
                "--output", dir.absolutePath,
            ) + files
        )
    }
    doLast {
        val dir = out.get().asFile
        dir.resolve("classes.dex").renameTo(dir.resolve("notesbridge.dex"))
    }
}
