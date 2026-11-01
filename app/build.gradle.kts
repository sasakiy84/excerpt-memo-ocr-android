plugins { id("com.android.application") }

val releaseVersion = providers.gradleProperty("releaseVersion").orElse("0.1.0").get()
require(Regex("(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})").matches(releaseVersion))
val (major, minor, patch) = releaseVersion.split('.').map(String::toInt)
val storePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val storePass = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull

android {
    namespace = "com.sasakiy84.excerptmemo"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.sasakiy84.excerptmemo"
        minSdk = 36
        targetSdk = 36
        versionCode = major * 1_000_000 + minor * 1_000 + patch
        versionName = releaseVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (storePath != null) signingConfigs {
        create("release") {
            storeFile = file(storePath)
            storePassword = storePass
            keyAlias = "excerpt-memo-release"
            keyPassword = storePass
        }
    }
    buildTypes {
        debug { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
        release {
            isMinifyEnabled = false
            ndk { abiFilters += "arm64-v8a" }
            if (storePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    androidResources { noCompress += "onnx" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.activity:activity:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.9.4")
    implementation("androidx.lifecycle:lifecycle-livedata:2.9.4")
    implementation("androidx.camera:camera-camera2:1.5.0")
    implementation("androidx.camera:camera-lifecycle:1.5.0")
    implementation("androidx.camera:camera-view:1.5.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("com.google.truth:truth:1.4.4")
}

val verifyReleaseSigning by tasks.registering {
    doLast {
        require(!storePath.isNullOrBlank() && !storePass.isNullOrBlank()) {
            "Set ANDROID_KEYSTORE_PATH and ANDROID_KEYSTORE_PASSWORD for release signing"
        }
        require(file(storePath).isFile) { "Release keystore does not exist" }
    }
}
tasks.matching { it.name in setOf("packageRelease", "validateSigningRelease", "bundleRelease") }
    .configureEach { dependsOn(verifyReleaseSigning) }
