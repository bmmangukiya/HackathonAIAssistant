plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hackathon.assistant.llm"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(project(":core"))
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")
    testImplementation("junit:junit:4.13.2")
}

// org.json is part of Android, not the JVM unit-test classpath.
dependencies { testImplementation("org.json:json:20250517") }
