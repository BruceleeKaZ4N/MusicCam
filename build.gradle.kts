buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP provides built-in Kotlin; pin its compiler without applying kotlin-android.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.2.1" apply false
}
