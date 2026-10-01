plugins {
    `java-library`
    `maven-publish`
}

group = rootProject.group
version = rootProject.version
java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
java.withSourcesJar()
java.withJavadocJar()
tasks.withType<JavaCompile>().configureEach { options.release.set(25) }

repositories {
    mavenCentral()
    maven("https://repo.codemc.io/repository/maven-snapshots/")
    maven("https://maven.grim.ac/public/releases")
    maven("https://maven.grim.ac/public/snapshots")
    maven("https://repo.grim.ac/snapshots")
    maven("https://nexus.scarsz.me/content/repositories/releases")
}

dependencies {
    api(project(":common"))
    compileOnly(project(":minestom-support"))
    compileOnly("net.minestom:minestom:2026.09.12-26.2")
    // Includes upstream Adventure 5 compatibility required by this Minestom release.
    implementation("com.github.retrooper:packetevents-api:2.14.1-20260926.204002-6") {
        version { strictly("2.14.1-20260926.204002-6") }
    }
    implementation("com.google.guava:guava:33.7.1-jre")
    runtimeOnly("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("com.github.retrooper:packetevents-netty-common:2.14.1-20260926.204002-6")
    implementation("io.netty:netty-buffer:4.1.118.Final")
    implementation("io.netty:netty-transport:4.1.118.Final")
}

base.archivesName.set("grim-minestom")
publishing.publications.create<MavenPublication>("maven") {
    artifactId = "grim-minestom"
    from(components["java"])
}
