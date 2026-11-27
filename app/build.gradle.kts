plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.ncorti.ktfmt.gradle")
}

android {
    namespace = "enterprises.legitimate.rabbitears"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "enterprises.legitimate.rabbitears"
        minSdk = 26
        targetSdk = 37
        // The commit count of HEAD; a shallow clone builds with a count that is not main's.
        versionCode =
            providers
                .exec { commandLine("git", "rev-list", "--count", "HEAD") }
                .standardOutput
                .asText
                .get()
                .trim()
                .toInt()
        // M.m is the owner's to set, here alone; scripts/cut-release.sh reads it from this line.
        versionName = "1.0.$versionCode"
    }

    // The upload key reaches the build only from the release job's environment, through
    // scripts/release.sh; everywhere else the release bundle is built unsigned.
    val uploadKeystore = providers.environmentVariable("ANDROID_UPLOAD_KEYSTORE_FILE").orNull
    if (uploadKeystore != null) {
        signingConfigs.create("upload") {
            storeFile = file(uploadKeystore)
            storePassword = providers.environmentVariable("ANDROID_UPLOAD_KEYSTORE_PASSWORD").get()
            keyAlias = "upload"
            // A PKCS12 keystore holds its key under the store's own password.
            keyPassword = storePassword
        }
    }

    buildTypes {
        debug { signingConfig = null }
        release { signingConfig = signingConfigs.findByName("upload") }
    }

    buildFeatures { compose = true }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // Upstream releases must not turn a green tree red overnight.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
}

ktfmt { kotlinLangStyle() }

// println reaches the console under --quiet, so a run that executed nothing
// cannot pass for a green one.
tasks.withType<Test>().configureEach {
    afterSuite(
        KotlinClosure2<TestDescriptor, TestResult, Unit>(
            { suite, result ->
                if (suite.parent == null) {
                    println(
                        "${result.testCount} tests, ${result.failedTestCount} failed, " +
                            "${result.skippedTestCount} skipped",
                    )
                }
            },
        ),
    )
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation(project(":api"))
    testImplementation("junit:junit:4.13.2")
}
