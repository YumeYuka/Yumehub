plugins { id("github.android.library") }

configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "moe.yumeyuka.yumehub.core.model"
    sourceSets.getByName("test") { kotlin.srcDirs("test") }
}

dependencies { testImplementation(libs.junit) }

tasks.named<org.gradle.api.tasks.SourceTask>("ktfmtFormatTest") {
    setSource(fileTree("test") { include("**/*.kt") })
}

tasks.named<org.gradle.api.tasks.SourceTask>("ktfmtCheckTest") {
    setSource(fileTree("test") { include("**/*.kt") })
}
