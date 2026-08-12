plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.4" /* [SC] DO NOT EDIT */

val targetShadowJars = subprojects.map { "${it.path}:shadowJar" }

tasks.register("buildAllVersions") {
    group = "build"
    description = "Builds the plugin jar for every supported Minecraft version."
    dependsOn(targetShadowJars)
}

tasks.register<Sync>("collectVersionJars") {
    group = "build"
    description = "Collects all supported plugin jars in build/libs."
    dependsOn(targetShadowJars)
    from(subprojects.map { it.layout.buildDirectory.dir("libs") })
    include("*.jar")
    into(layout.buildDirectory.dir("libs"))
}