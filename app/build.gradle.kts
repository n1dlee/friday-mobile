// No secret is read here: an API key compiled into an APK can be read back
// out of it by anyone who has the file. The user enters their own in Settings.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.gitlab.arturbosch.detekt")
}

android {

    androidResources {
        // The model is already compressed; letting aapt try again only makes
        // the build slower and forces a copy out of the APK at runtime.
        noCompress += "onnx"
    }

    namespace = "com.friday.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.friday.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.13.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Every phone Friday runs on is 64-bit ARM. The x86 and 32-bit copies
        // of the ONNX Runtime and Vosk libraries were a third of the APK.
        ndk { abiFilters += "arm64-v8a" }
    }

    // The owner's signing key lives in keystore/ and is never committed (see
    // keystore/README.md). Builds made elsewhere fall back to the SDK's debug
    // key — fine for trying Friday out, but such a build cannot update an
    // install signed with the owner's key.
    val ownerKey = rootProject.file("keystore/friday.keystore")
    signingConfigs {
        if (ownerKey.exists()) {
            create("friday") {
                storeFile = ownerKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    val signing = signingConfigs.findByName("friday") ?: signingConfigs.getByName("debug")

    buildTypes {
        debug {
            signingConfig = signing
        }
        release {
            signingConfig = signing
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Room writes the schema of every version here. Committing these lets Room
    // verify that a hand-written migration really produces the schema it
    // expects, instead of failing at runtime on the user's phone.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    testOptions {
        unitTests {
            // android.util.Log is a stub that throws in unit tests; returning
            // defaults lets pure logic that happens to log be tested directly.
            isReturnDefaultValues = true
            // Robolectric screenshot tests need the merged resources.
            isIncludeAndroidResources = true
            all {
                // Screenshots render only on request: ./gradlew testDebugUnitTest -Pscreenshots
                it.systemProperty("screenshots", project.hasProperty("screenshots").toString())
                it.systemProperty("screenshotDir", "${rootDir}/build/screenshots")
            }
        }
    }
}

dependencies {
    // Speaker verification. ONNX Runtime rather than TFLite: the wespeaker
    // model ships as ONNX, and converting it would add a step that could only
    // introduce error.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // Gmail. Play services authorisation identifies the app by package name
    // and signing certificate, so no client secret ships inside the APK.
    implementation("com.google.android.gms:play-services-auth:21.6.0")

    // Which country a phone number belongs to, for every country, kept up to
    // date by Google — so "SMS or WhatsApp?" is decided from the number itself.
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.52")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Core & Lifecycle
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Scheduled work: the daily briefing
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Room DB
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Networking: Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    // Koin DI
    implementation("io.insert-koin:koin-android:4.0.0")
    implementation("io.insert-koin:koin-androidx-compose:4.0.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Vosk offline speech recognition (silent wake word detection)
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // Unit Tests
    testImplementation("junit:junit:4.13.2")
    // Real JSONObject on the JVM test classpath — the android.jar one is a stub.
    testImplementation("org.json:json:20240303")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("app.cash.turbine:turbine:1.2.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    // Renders Compose screens on the JVM (native graphics) for visual checks without a device.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.14.1")

    // Android Instrumented Tests
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}

// Static analysis. Run with `./gradlew detekt`; the report lands in
// app/build/reports/detekt/.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/main/java", "src/test/java")
    parallel = true
    // Debt that existed when detekt was introduced. Listed, not fixed: every
    // entry is a known item, and anything new outside it fails the build.
    // Regenerate with `./gradlew detektBaseline` only after paying some off.
    baseline = rootProject.file("config/detekt/baseline.xml")
}

// detekt 1.23.8 embeds Kotlin 2.0.21 and refuses to run when Gradle resolves
// the project's newer Kotlin onto its classpath. Pin its own configuration
// back to the version it was built with; the app itself is unaffected.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(io.gitlab.arturbosch.detekt.getSupportedKotlinVersion())
        }
    }
}

/**
 * Guards the R8 keep rules: classes that native code (JNI) looks up by name,
 * or whose names we store, must come out of minification unrenamed. Without
 * this, a dependency update that changes R8's view of them would build fine
 * and crash on the phone — exactly what the release build did before the
 * rules in proguard-rules.pro existed.
 */
val verifyReleaseKeepRules by tasks.registering {
    group = "verification"
    description = "Checks that JNI-reached and stored class names survive R8 in the release build."
    dependsOn("minifyReleaseWithR8")
    val mapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
    inputs.file(mapping)
    doLast {
        val mustKeep = listOf(
            // ONNX Runtime JNI (speaker verification)
            "ai.onnxruntime.OrtEnvironment", "ai.onnxruntime.OrtSession", "ai.onnxruntime.OnnxTensor",
            "ai.onnxruntime.OrtException", "ai.onnxruntime.TensorInfo",
            // JNA + Vosk (wake word)
            "com.sun.jna.Native", "com.sun.jna.Pointer", "com.sun.jna.Structure", "com.sun.jna.Memory",
            "org.vosk.LibVosk", "org.vosk.Model", "org.vosk.Recognizer",
            // libphonenumber metadata
            "com.google.i18n.phonenumbers.PhoneNumberUtil",
            // Names stored in the database
            "com.friday.ai.domain.model.CommandResult\$SetAlarm",
            "com.friday.ai.service.MorningBriefWorker", "com.friday.ai.service.ErrandWatchWorker"
        )
        val renamed = mapping.get().asFile.useLines { lines ->
            lines.filter { !it.startsWith(" ") && it.contains(" -> ") }
                .map { it.substringBefore(" -> ") to it.substringAfter(" -> ").removeSuffix(":") }
                .filter { (from, to) -> from in mustKeep && from != to }
                .toList()
        }
        check(renamed.isEmpty()) {
            "R8 renamed classes that must keep their names: " +
                renamed.joinToString { (from, to) -> "$from -> $to" } + ". See app/proguard-rules.pro."
        }
        logger.lifecycle("R8 keep rules OK: ${mustKeep.size} classes kept by name.")
    }
}
