plugins {
    id("com.android.application") version "8.13.2" apply false
    kotlin("android") version "2.2.21" apply false
    kotlin("plugin.parcelize") version "2.2.21" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
