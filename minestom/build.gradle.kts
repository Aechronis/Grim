import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    `java-library`
    `maven-publish`
    id("com.gradleup.shadow")
    id("com.vanniktech.maven.publish.base") version "0.37.0"
}

group = "net.aechronis"
version = providers.gradleProperty("minestomVersion").getOrElse("${rootProject.version}-SNAPSHOT")
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

// Keep the upstream dependency declarations authoritative. Bundle the engine and the
// libraries hosted outside Central; publish the remaining dependencies normally.
evaluationDependsOn(":common")
val common = project(":common")
val commonSources = common.extensions.getByType<SourceSetContainer>().named("main")
val embedded = configurations.create("embedded") {
    isCanBeConsumed = false
}
configurations.compileOnly { extendsFrom(embedded) }
val embeddedGroups = setOf("ac.grim.grimac", "github.scarsz")
val embeddedSources = configurations.create("embeddedSources") {
    isCanBeConsumed = false
    isTransitive = false
    defaultDependencies {
        embedded.incoming.resolutionResult.allComponents.forEach { component ->
            val module = component.id as? ModuleComponentIdentifier ?: return@forEach
            add(project.dependencies.create("${module.group}:${module.module}:${module.version}:sources"))
        }
    }
}

dependencies {
    add(embedded.name, project(":common")) { isTransitive = false }
    common.configurations.getByName("api").dependencies.forEach { dependency ->
        add(if (dependency.group in embeddedGroups) embedded.name else "api", dependency.copy())
    }
    common.configurations.getByName("runtimeOnly").dependencies.forEach { dependency ->
        add("runtimeOnly", dependency.copy())
    }
    compileOnly("net.minestom:minestom:2026.09.12-26.2")
    // Includes upstream Adventure 5 compatibility required by this Minestom release.
    add(embedded.name, "com.github.retrooper:packetevents-api:2.14.1-20260926.204002-6") {
        version { strictly("2.14.1-20260926.204002-6") }
    }
    add(embedded.name, "com.github.retrooper:packetevents-netty-common:2.14.1-20260926.204002-6")
    implementation("com.google.guava:guava:33.7.1-jre")
    runtimeOnly("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("io.netty:netty-buffer:4.1.118.Final")
    implementation("io.netty:netty-transport:4.1.118.Final")
}

base.archivesName.set("grim-minestom")
shadow { addShadowVariantIntoJavaComponent = false }
val bundledJar = tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    configurations.set(listOf(embedded))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/**/module-info.class")
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(layout.projectDirectory.dir("notices")) { into("META-INF/licenses") }
}
tasks.jar { enabled = false }
tasks.assemble { dependsOn(bundledJar) }
// Both composite builds and Maven consumers use the same complete library JAR.
listOf("apiElements", "runtimeElements").forEach { name ->
    configurations.named(name) {
        outgoing.artifacts.clear()
        outgoing.artifact(bundledJar)
        outgoing.variants.clear()
    }
}
tasks.named<Jar>("sourcesJar") {
    dependsOn(embeddedSources)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ embeddedSources.map { zipTree(it) } }) { exclude("META-INF/MANIFEST.MF") }
    from(commonSources.map { it.allSource })
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(layout.projectDirectory.dir("notices")) { into("META-INF/licenses") }
}
tasks.named<Jar>("javadocJar") {
    from(common.tasks.named<Javadoc>("javadoc")) { into("engine") }
}

publishing {
    publications.create<MavenPublication>("maven") {
        artifactId = "grim-minestom"
        from(components["java"])
        pom {
            name.set("Grim for Minestom")
            description.set("Grim's simulation anticheat embedded in stock Minestom, maintained by Aechronis.")
            url.set("https://github.com/Aechronis/Grim")
            licenses {
                license {
                    name.set("GNU General Public License, version 3")
                    url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("Aechronis")
                    name.set("Aechronis")
                    url.set("https://github.com/Aechronis")
                }
                developer {
                    id.set("GrimAnticheat")
                    name.set("GrimAnticheat contributors")
                    url.set("https://github.com/GrimAnticheat")
                }
            }
            scm {
                url.set("https://github.com/Aechronis/Grim")
                connection.set("scm:git:https://github.com/Aechronis/Grim.git")
                developerConnection.set("scm:git:ssh://git@github.com/Aechronis/Grim.git")
            }
        }
    }
    repositories {
        maven {
            name = "localBuild"
            url = uri(layout.buildDirectory.dir("repository"))
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
}
