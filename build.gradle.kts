plugins {
    id("zenithproxy.plugin.dev") version "1.0.0-SNAPSHOT"
}

group = properties["maven_group"] as String
version = properties["plugin_version"] as String
val mc = properties["mc"] as String
val pluginId = properties["plugin_id"] as String
val pluginName = properties["plugin_name"] as String
val javaRelease = if (mc in setOf("1.21.11", "26.1.2", "26.2.0")) 25 else 21

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

zenithProxyPlugin {
    templateProperties = mapOf(
        "version" to project.version,
        "mc_version" to mc,
        "plugin_id" to pluginId,
        "maven_group" to group as String,
    )
    javaReleaseVersion = JavaLanguageVersion.of(javaRelease)
}

repositories {
    maven("https://maven.2b2t.vc/releases") {
        description = "ZenithProxy Releases"
    }
    maven("https://maven.2b2t.vc/remote") {
        description = "Dependencies used by ZenithProxy"
    }
}

dependencies {
    zenithProxy("com.zenith:ZenithProxy:$mc-SNAPSHOT")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named<Copy>("generateTemplates") {
    from(rootProject.file("src/main/templates"))
}

tasks.withType<Jar>().configureEach {
    if (name == "shadowJar") {
        archiveFileName.set("${pluginName}-${project.version}+${mc}.jar")
    }
    from(rootProject.file("LICENSE")) {
        into("META-INF")
        rename("LICENSE", "${pluginId}-LICENSE.txt")
    }
}
