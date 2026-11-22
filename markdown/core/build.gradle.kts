plugins { id("github.kotlin.library") }

dependencies {
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    inputs.dir(layout.projectDirectory.dir("../fixtures")).withPropertyName("githubHtmlFixtures")
    systemProperty(
        "markdown.fixtures",
        layout.projectDirectory.dir("../fixtures").asFile.absolutePath,
    )
}
