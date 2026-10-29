plugins {
  alias(libs.plugins.android.application) apply false
  // Only puts KGP `libs.versions.kotlin` on the build classpath, overriding the older KGP that AGP's built-in Kotlin
  // depends on. Never applied: AGP 9 registers the Kotlin support itself.
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.compose) apply false
}
