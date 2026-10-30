import java.io.File
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.jaredsburrows.license")
}

// Release credentials are supplied only to the local signing process or the
// explicitly requested CI release step. Debug builds need no private key.
val releaseStorePath = providers.environmentVariable("ZHUZHALKA_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("ZHUZHALKA_KEYSTORE_PASSWORD").orNull
val hasReleaseSigning = !releaseStorePath.isNullOrBlank() && !releaseStorePassword.isNullOrBlank()
val releaseCertificatePin = rootProject.file("../scripts/release-signing-cert.sha256")

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doLast {
        check(hasReleaseSigning) {
            "Release signing is required. Supply ZHUZHALKA_KEYSTORE_PATH and ZHUZHALKA_KEYSTORE_PASSWORD."
        }
        val keyFile = File(requireNotNull(releaseStorePath))
        check(keyFile.isAbsolute && keyFile.isFile) { "Release keystore must be an existing absolute path." }
        val password = requireNotNull(releaseStorePassword).toCharArray()
        try {
            val keyStore = KeyStore.getInstance("JKS")
            keyFile.inputStream().use { keyStore.load(it, password) }
            val certificate = checkNotNull(keyStore.getCertificate("zhuzhalka-release")) {
                "Permanent release alias is missing."
            }
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded).joinToString("") { "%02x".format(it) }
            check(actual == releaseCertificatePin.readText().trim()) {
                "The signing certificate differs from the permanent public certificate pin."
            }
        } finally {
            password.fill('\u0000')
        }
    }
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.zhuzhalka.vpn"
        minSdk = 24
        targetSdk = 37
        // One monotonic code for all ABIs; exceeds every 0.1 Play Store/F-Droid code.
        versionCode = 5000301
        versionName = "0.3.0-alpha.2"

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        splits {
            abi {
                isEnable = true
                reset()
                if (!abiFilterList.isNullOrEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64",
                        "x86"
                    )
                }
                isUniversalApk = abiFilterList.isNullOrEmpty()
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("permanentRelease") {
                storeFile = file(requireNotNull(releaseStorePath))
                storePassword = releaseStorePassword
                keyAlias = "zhuzhalka-release"
                keyPassword = releaseStorePassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("permanentRelease")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions.add("distribution")
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
            buildConfigField("String", "DISTRIBUTION", "\"F-Droid\"")
        }
        create("playstore") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"Play Store\"")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf(
            "en",
            "zh-rCN",
            "zh-rTW",
            "vi",
            "ru",
            "fa",
            "ar",
            "az",
            "bn",
            "bqi-rIR"
        )
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            // Treat Jupiter discovery warnings as failures: JUnit 4 failed on an invalid @Test
            // method, such as one that returns a value, while Jupiter only warns and skips it.
            // The key is @API(status = EXPERIMENTAL) in JUnit 6; after a JUnit upgrade, check
            // that a temporary `@Test fun probe() = 1` still fails the unit test task.
            it.systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
        }
    }

}

androidComponents {
    onVariants(selector().all()) { variant ->
        val suffix = if (variant.productFlavors.any { it.second == "fdroid" }) "-fdroid" else ""
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull {
                it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI
            }?.identifier ?: "universal"
            output.outputFileName.set(output.versionName.map { "Zhuzhalka_${it}${suffix}_${abi}.apk" })
        }
    }
}

tasks.configureEach {
    if (name == "prePlaystoreReleaseBuild" || name == "preFdroidReleaseBuild") {
        dependsOn(verifyReleaseSigning)
    }
}

// The upstream JVM facade tries Android's loader first. Host tests must use the desktop JNI loader.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "fr.acinq.secp256k1", module = "secp256k1-kmp-jni-android")
}

dependencies {
    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Compose Libraries
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.secp256k1.core)
    implementation(libs.secp256k1.android)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // QR Code: CameraX + ZXing
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.compose)
    implementation(libs.core) // zxing core

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Reorderable list
    implementation(libs.reorderable)

    // Testing Libraries
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockwebserver)
    testRuntimeOnly(libs.secp256k1.jvm)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
