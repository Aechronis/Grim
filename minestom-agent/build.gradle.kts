plugins {
    `java-library`
    `maven-publish`
    id("com.gradleup.shadow")
}

group = rootProject.group
version = rootProject.version
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}
repositories { mavenCentral() }
dependencies {
    implementation("org.ow2.asm:asm:9.9.1")
    implementation("org.ow2.asm:asm-commons:9.9.1")
}
base.archivesName.set("grim-minestom-agent")
tasks.jar {
    manifest.attributes(
        "Premain-Class" to "ac.grim.grimac.minestom.agent.MinestomAgent",
        "Launcher-Agent-Class" to "ac.grim.grimac.minestom.agent.MinestomAgent",
    )
}
tasks.shadowJar {
    archiveClassifier.set("standalone")
    archiveFileName.set("grim-minestom-agent.jar")
    relocate("org.objectweb.asm", "ac.grim.grimac.minestom.agent.asm")
}
publishing.publications.create<MavenPublication>("maven") {
    artifactId = "grim-minestom-agent"
    from(components["java"])
}
