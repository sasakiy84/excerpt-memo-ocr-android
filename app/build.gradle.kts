plugins { id("com.android.application") }

val releaseVersion = providers.gradleProperty("releaseVersion").orElse("0.6.0").get()
require(Regex("(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})").matches(releaseVersion))
val (major, minor, patch) = releaseVersion.split('.').map(String::toInt)

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
    buildTypes {
        debug { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
        release {
            isMinifyEnabled = false
            ndk { abiFilters += "arm64-v8a" }
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

val robolectricSdk by configurations.creating
val offlineSdk = gradle.gradleUserHomeDir.resolve("verified-robolectric-sdk")
val prepareRobolectricSdk by tasks.registering(Copy::class) {
    from(robolectricSdk)
    into(offlineSdk)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
    val testHome = gradle.gradleUserHomeDir.resolve("robolectric-home")
    doFirst { testHome.mkdirs() }
    systemProperty("user.home", testHome.absolutePath)
    dependsOn(prepareRobolectricSdk)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", offlineSdk.absolutePath)
    for (key in listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort")) {
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    systemProperty("host.opencv.path", providers.gradleProperty("hostOpenCv").orElse(
        gradle.gradleUserHomeDir.resolve("host-opencv/4.12.0/build/lib/libopencv_java4120.so").absolutePath).get())
    maxHeapSize = "3g"
}

dependencies {
    robolectricSdk("org.robolectric:android-all-instrumented:16-robolectric-13921718-i7")
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.activity:activity:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.9.4")
    implementation("androidx.lifecycle:lifecycle-livedata:2.9.4")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    implementation("org.opencv:opencv:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

// Host OCR tests use the same ONNX Java API and native CPU kernels on Linux.
// APKs and instrumented tests continue to use the Android runtime artifact.
configurations.matching { it.name.contains("UnitTest") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("com.microsoft.onnxruntime:onnxruntime-android"))
            .using(module("com.microsoft.onnxruntime:onnxruntime:1.24.3"))
    }
}

// Pin every resolved Android/test/lint configuration. Explicit updates use --write-locks.
dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
}
