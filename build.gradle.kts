plugins {
    java
}

group = "com.zenith.plugin"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/maven-releases")
    maven("https://repo.opencollab.dev/maven-snapshots")
    maven("https://libraries.minecraft.net")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.rfresh2:ZenithProxy:1.0.0")
}

tasks.jar {
    archiveBaseName.set("stash-manager")
    destinationDirectory.set(file("build/libs"))
}
