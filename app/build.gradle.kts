plugins {
    id("com.android.application")
}

android {
    namespace = "com.oplmobilesmb"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.oplmobilesmb"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "0.4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "org/bouncycastle/pqc/crypto/picnic/*",
                "com/sun/jna/**"
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // Android-compatible JFileServer fork used by SimbaDroid.
    implementation("com.github.buttercookie42:jfileserver:ff550a7") {
        exclude(group = "com.hazelcast", module = "hazelcast")
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    }
    implementation("org.bouncycastle:bcprov-jdk15to18:1.79")
    implementation("org.slf4j:slf4j-nop:2.0.16")
}
