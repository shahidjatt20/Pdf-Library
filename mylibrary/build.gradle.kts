plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.pdf.mylibrary"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    // Tell AGP which variant produces the publishable component ("release").
    // Without this, components["release"] does not exist and JitPack has nothing
    // to publish (hence the missing publishToMavenLocal task).
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("com.github.chrisbanes:PhotoView:2.3.0")
    implementation("com.otaliastudios:zoomlayout:1.9.0") // Updated version
}

// Registers publishReleasePublicationToMavenLocal / publishToMavenLocal so JitPack
// can resolve and publish the AAR. JitPack overrides the coordinates with
// com.github.<user>:<repo>:<tag>, but we set sane defaults for local publishing.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.ShahidIITS"
                artifactId = "mylibrary"
                version = "1.0.1"
            }
        }
    }
}
