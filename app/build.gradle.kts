plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStore = providers.environmentVariable("HYUNDAEHWA_KEYSTORE")
val releasePassword = providers.environmentVariable("HYUNDAEHWA_STOREPASS")
val gleoRecipient = providers.gradleProperty("gleoRecipientPackage").orElse("")
val gleoSigner = providers.gradleProperty("gleoRecipientSha256").orElse("")
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "ai.hyundaehwa.car"
    compileSdk = 35
    useLibrary("android.car")
    defaultConfig {
        applicationId = "ai.hyundaehwa.car"
        minSdk = 28
        targetSdk = 35
        versionCode = 36
        versionName = "0.6.0-rc7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "ENABLE_GEMINI_RELAY", "false")
        buildConfigField("String", "GLEO_RECIPIENT_PACKAGE", quoted(gleoRecipient.get()))
        buildConfigField("String", "GLEO_RECIPIENT_SHA256", quoted(gleoSigner.get()))
    }
    signingConfigs {
        create("production") {
            if (releaseStore.isPresent && releasePassword.isPresent) {
                storeFile = file(releaseStore.get())
                storePassword = releasePassword.get()
                keyAlias = "hyundaehwa-release"
                keyPassword = releasePassword.get()
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    testBuildType = "debug"
    androidResources { noCompress += listOf("ogg", "mp3") }
    buildTypes {
        debug {
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            buildConfigField("boolean", "ENABLE_GEMINI_RELAY", "false")
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("production")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("releaseQa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".releaseqa"
            versionNameSuffix = "-qa"
            matchingFallbacks += "release"
        }
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation(project(":core"))
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:vehicle-data:1.1.11")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:navi-data:3.0.4")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground.security:universal-access:0.6.0")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("androidx.security:security-crypto-ktx:1.1.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.21")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.google.dagger:hilt-android:2.51.1")
}
