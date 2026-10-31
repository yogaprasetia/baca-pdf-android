plugins {
  id("com.android.application")
}

android {
  namespace = "id.yoga.bacapdf"
  compileSdk = 36

  defaultConfig {
    applicationId = "id.yoga.bacapdf"
    minSdk = 24
    targetSdk = 36
    versionCode = 3
    versionName = "1.1.1"
  }
  signingConfigs {
    create("rel") {
      storeFile = file("../bacapdf.jks")
      storePassword = System.getenv("BACAPDF_KS_PASS") ?: ""
      keyAlias = "bacapdf"
      keyPassword = System.getenv("BACAPDF_KS_PASS") ?: ""
    }
  }
  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("rel")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  androidResources { noCompress += listOf("wasm", "bcmap", "pfb", "ttf") }
}

dependencies {
  implementation("androidx.appcompat:appcompat:1.7.1")
  implementation("androidx.core:core-ktx:1.16.0")
  implementation("androidx.recyclerview:recyclerview:1.4.0")
  implementation("androidx.webkit:webkit:1.14.0")
  implementation("com.google.android.material:material:1.12.0")
}
