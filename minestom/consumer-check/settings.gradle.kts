rootProject.name = "grim-minestom-consumer-check"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri(providers.gradleProperty("libraryRepository").get())
            content { includeModule("net.aechronis", "grim-minestom") }
            if (providers.gradleProperty("pomOnly").map(String::toBoolean).getOrElse(false)) {
                metadataSources {
                    mavenPom()
                    ignoreGradleMetadataRedirection()
                }
            }
        }
        mavenCentral()
    }
}
