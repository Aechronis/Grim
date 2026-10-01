plugins {
    `java-library`
    `maven-publish`
}

group = rootProject.group
version = rootProject.version
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
    withJavadocJar()
}
repositories { mavenCentral() }
dependencies {
    compileOnly("net.minestom:minestom:2026.09.12-26.2")
}
base.archivesName.set("grim-minestom-support")
publishing.publications.create<MavenPublication>("maven") {
    artifactId = "grim-minestom-support"
    from(components["java"])
}
