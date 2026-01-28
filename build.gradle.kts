// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // This matches your 8.2.2 requirement
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false

    // Updated to 1.9.22 for better compatibility with Java 17 and AGP 8.2.2
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}