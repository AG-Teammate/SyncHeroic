plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.syncheroic"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.syncheroic"
        minSdk = 28
        targetSdk = 36
        versionCode = providers.environmentVariable("VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("VERSION_NAME").orNull ?: "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "CONFIG_BASE_URL", "\"https://raw.githubusercontent.com/AG-Teammate/SyncHeroic/main/config/\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging.resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val keyStorePath = providers.environmentVariable("SYNC_HEROIC_KEYSTORE_PATH").orNull
            if (keyStorePath != null) signingConfig = signingConfigs.create("releaseSecrets") {
                storeFile = file(keyStorePath)
                storePassword = providers.environmentVariable("SYNC_HEROIC_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("SYNC_HEROIC_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("SYNC_HEROIC_KEY_PASSWORD").orNull
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        // API 36 is the project's tested stable target. Hosted runners may expose
        // preview/newer SDK metadata before the project deliberately adopts it.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.health.connect)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
