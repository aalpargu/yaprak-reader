import com.android.build.api.instrumentation.InstrumentationScope
import com.yaprak.build.RedactBookContentLogs

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.yaprak.reader"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.yaprak.reader"
        minSdk = 26
        targetSdk = 36
        versionCode = 184
        versionName = "0.183.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources")
    sourceSets.getByName("test").resources.srcDir("schemas")
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("yaprak.screenshotDir", layout.buildDirectory.dir("reports/screenshots").get().asFile.absolutePath)
            it.systemProperty("maven.repo.local", rootProject.layout.buildDirectory.dir("robolectric-deps").get().asFile.absolutePath)
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            // Conscrypt's host library lookup lowercases "Windows" using the JVM locale.
            // Keep host tests deterministic on Turkish Windows as well as CI.
            it.systemProperty("user.language", "en")
            it.systemProperty("user.country", "US")
            it.maxHeapSize = "2g"
        }
    }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// With dependency instrumentation, KSP's variant configurations did not inherit the global
// processor declaration. Keep debug/release/test processor classpaths connected explicitly.
configurations.configureEach {
    if (name.startsWith("ksp") && name.endsWith("KotlinProcessorClasspath")) {
        extendsFrom(configurations.getByName("ksp"))
    }
}

dependencies {
    implementation(libs.junrar)
    constraints { implementation(libs.slf4j) }
    implementation(libs.pdfbox.android)
    constraints {
        implementation(libs.bcprov)
        implementation(libs.bcpkix)
        implementation(libs.bcutil)
    }
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.translate)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.tooling)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.androidx.fragment)
    coreLibraryDesugaring(libs.desugar)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.test)
    debugImplementation(libs.androidx.compose.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.test)
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.instrumentation.transformClassesWith(RedactBookContentLogs::class.java, InstrumentationScope.ALL) {}
    }
}
