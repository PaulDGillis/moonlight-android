// Top-level build file where you can add configuration options common to all sub-projects/modules.
//buildscript {
//    repositories {
//        mavenCentral()
//        google()
//    }
//    dependencies {
//        classpath("com.android.tools.build:gradle:8.5.1")
//        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
//    }
//}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
}

//allprojects {
//    repositories {
//        mavenCentral()
//        google()
//        maven(url = "https://jitpack.io")
//    }
//}
