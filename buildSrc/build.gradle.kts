plugins { java }

repositories { google(); mavenCentral() }

dependencies {
    implementation(libs.android.gradle.api)
    implementation(libs.asm)
    testImplementation(libs.junit)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
