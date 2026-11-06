plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.rhineix.penumbra"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "dev.rhineix.penumbra"
        minSdk = 30
        targetSdk = 37
        versionCode = providers.gradleProperty("APP_VERSION_CODE").orElse("8").get().toInt().also { require(it in 1..2_100_000_000) }
        versionName = providers.gradleProperty("APP_VERSION_NAME").orElse("0.3.3").get().also {
            require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?").matches(it)) { "Invalid release version" }
        }
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        jniLibs.excludes += setOf("**/x86/**", "**/x86_64/**", "**/armeabi-v7a/**")
    }
    androidResources { localeFilters += listOf("en") }
    lint { abortOnError = true }
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("io.github.suqi8.coui.kmp:coui-ui:1.1.0")
    implementation("io.github.suqi8.coui.kmp:coui-preference:1.1.0")
    implementation("dev.chrisbanes.haze:haze:2.1.0")
    implementation("dev.chrisbanes.haze:haze-blur:2.1.0")
    implementation("br.com.devsrsouza.compose.icons:tabler-icons:1.1.1")
}
