// The desktop app (Windows x64): system audio → the shared core → a transparent always-on-top caption window.
// The Windows zip: tools/win-app/build.sh on Windows (Git Bash, MSVC), run by .github/workflows/windows.yml.
// (This fork builds Windows only: the macOS app, its dmg and its audio helper were removed.)

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(17) }

// the Android app's version: one release carries the APK and the zip
val appGradle = rootProject.file("app/build.gradle.kts").readText()
val appVersion = Regex("versionName = \"([^\"]+)\"").find(appGradle)!!.groupValues[1]
val appCode = Regex("versionCode = (\\d+)").find(appGradle)!!.groupValues[1]

// tools/win-app's output: windows-x64 (the native libraries, sysaudio and the models) and sherpa-jvm.jar
val winDist = rootProject.layout.projectDirectory.dir("tools/win-app/build/resources/windows-x64")
val dist = (findProperty("simulsub.dist") as String?)?.let { file(it) }

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation("org.json:json:20240303")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Windows: the keys, encrypted with DPAPI (Crypt32Util)
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation(files((dist ?: winDist.asFile).resolve("sherpa-jvm.jar")))
    // Prefs is exercised against its own settings file and key service (see PrefsTest)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

compose.desktop {
    application {
        mainClass = "io.github.korenkrita.simulsub.MainKt"
        jvmArgs += listOf("-Dsimulsub.version=$appVersion", "-Dsimulsub.versionCode=$appCode")
        // packaged, the native libraries and sysaudio sit in compose.application.resources.dir (see Main.kt)
        if (dist == null) {
            // Forward slashes on purpose: jpackage writes these into SimulSub.cfg, where a backslash starts an
            // escape (, 	, ), so `C:\...\tools\win-app\build` arrived as `C:...oolswin-appbuild`.
            fun p(path: java.io.File) = path.absolutePath.replace('\\', '/')
            jvmArgs += listOf(
                "-Djava.library.path=${p(winDist.dir("native").asFile)}",
                "-Dsimulsub.root=${p(rootProject.projectDir)}",
                // from the repo: the models ./setup.sh downloaded, a data folder of its own
                "-Dsimulsub.models=${p(rootProject.projectDir)}/models",
                "-Dsimulsub.data=${p(layout.buildDirectory.dir("run-data").get().asFile)}",
            )
        }
        // No targetFormats: tools/win-app/build.sh runs :desktop:createDistributable and zips the app image itself,
        // so jpackage never builds an installer here.
        nativeDistributions {
            packageName = "SimulSub"
            // jpackage wants MAJOR > 0; the app, the Release and the zip's folder show versionName (-Dsimulsub.version)
            packageVersion = "1.$appCode"
            description = "SimulSub: live captions for what the computer plays"
            vendor = "KorenKrita"
            copyright = "(c) KorenKrita"
            // the Windows-ROOT trust store (Main.kt) needs jdk.crypto.mscapi
            modules("java.net.http", "jdk.crypto.ec", "java.naming", "jdk.crypto.mscapi")
            dist?.let { appResourcesRootDir.set(it.resolve("resources")) }
            // the exe's icon (the zip's folder, see tools/win-app); icons/make.py draws it
            windows { iconFile.set(project.file("icons/icon.ico")) }
        }
    }
}
