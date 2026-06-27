plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.portal.kasa"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.portal.kasa"
    minSdk = 28          // Android 9 — Portal+ ("aloha")
    targetSdk = 29       // Android 10 — Portal-era behavior
    versionCode = 1
    versionName = "1.0"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
  }
}

dependencies {
  // Shared utilities (DebugLog), composite-build substituted from the workspace's portal-commons
  // (the includeBuild lives in settings.gradle.kts):
  implementation("com.portal:commons")
  // The assistant tool contract is not a dependency: its frozen wire strings are inlined in KasaToolProvider.
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose) // collectAsStateWithLifecycle
  implementation(libs.androidx.lifecycle.viewmodel.compose) // ViewModel + viewModel()
  implementation(libs.kotlinx.coroutines.android)

  testImplementation(libs.junit)
  // Real org.json for JVM unit tests (android.jar stubs throw), so KasaParse/PlugMatch are testable.
  testImplementation(libs.json)
  // runTest + test dispatchers, so the (now injectable) KasaRepository send pipeline is unit-testable.
  testImplementation(libs.kotlinx.coroutines.test)

  debugImplementation(libs.androidx.compose.ui.tooling)
}
