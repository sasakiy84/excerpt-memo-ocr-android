plugins { id("com.android.application") }

val releaseVersion = providers.gradleProperty("releaseVersion").orElse("0.3.0").get()
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
        testInstrumentationRunner = "com.sasakiy84.excerptmemo.LocalTestRunner"
    }
    signingConfigs.getByName("debug") { enableV4Signing = true }
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

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
    val testHome = gradle.gradleUserHomeDir.resolve("robolectric-home")
    doFirst { testHome.mkdirs() }
    systemProperty("user.home", testHome.absolutePath)
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    for (key in listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort")) {
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    maxHeapSize = "3g"
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.activity:activity:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.9.4")
    implementation("androidx.lifecycle:lifecycle-livedata:2.9.4")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("org.robolectric:robolectric:4.17")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("com.google.truth:truth:1.4.4")
}

// Host OCR tests use the same ONNX Java API and native CPU kernels on Linux.
// APKs and instrumented tests continue to use the Android runtime artifact.
configurations.matching { it.name.contains("UnitTest") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("com.microsoft.onnxruntime:onnxruntime-android"))
            .using(module("com.microsoft.onnxruntime:onnxruntime:1.24.3"))
    }
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
