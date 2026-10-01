plugins {
    application
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
dependencies {
    implementation("net.aechronis:grim-minestom:${providers.gradleProperty("libraryVersion").get()}")
    implementation("net.minestom:minestom:2026.09.12-26.2")
}
application.mainClass.set("PublicationSmoke")
tasks.named<JavaExec>("run") {
    val directory = layout.buildDirectory.dir("smoke-run")
    workingDir(directory)
    doFirst {
        directory.get().asFile.deleteRecursively()
        directory.get().asFile.mkdirs()
    }
}
