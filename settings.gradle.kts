pluginManagement {
    includeBuild("build-logic")

    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    val localProperties = java.util.Properties()
    runCatching {
        rootDir
            .resolve("local.properties")
            .takeIf { it.isFile }
            ?.inputStream()
            ?.use { stream -> localProperties.load(stream) }
    }
    fun gprCredential(localName: String, envName: String) =
        localProperties.getProperty(localName)?.takeIf(String::isNotBlank)?.let { value ->
            providers.provider { value }
        } ?: providers.gradleProperty(localName).orElse(providers.environmentVariable(envName))
    val gprUser = gprCredential("gpr.user", "GITHUB_ACTOR")
    val gprKey = gprCredential("gpr.key", "GITHUB_TOKEN")

    repositories {
        google { content { excludeGroup("io.github.neoruaa.glasmio") } }
        mavenCentral { content { excludeGroup("io.github.neoruaa.glasmio") } }
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.YarikSOffice") }
        }
        maven {
            name = "GitHubPackagesMiuix"
            url = uri("https://maven.pkg.github.com/compose-miuix-ui/miuix")
            content { includeGroup("top.yukonga.miuix.kmp") }
            if (gprUser.isPresent && gprKey.isPresent) {
                credentials {
                    username = gprUser.get()
                    password = gprKey.get()
                }
            }
        }
        maven {
            name = "GitHubPackagesLucide"
            url = uri("https://maven.pkg.github.com/YumeYucca/lucide-compose")
            content { includeGroup("io.github.YumeYucca") }
            if (gprUser.isPresent && gprKey.isPresent) {
                credentials {
                    username = gprUser.get()
                    password = gprKey.get()
                }
            }
        }
    }
}

rootProject.name = "Yumehub"

check(file("third_party/glasmio/settings.gradle.kts").isFile) {
    "Glasmio 子模块未初始化，请在仓库根目录运行 git submodule update --init --recursive"
}

includeBuild("third_party/glasmio") {
    dependencySubstitution {
        listOf("core", "glass", "ui", "scaffold", "icons", "preference").forEach { moduleName ->
            substitute(module("io.github.neoruaa.glasmio:glasmio-$moduleName"))
                .using(project(":glasmio-$moduleName"))
        }
    }
}

include(":core:common")
include(":core:model")
include(":data")
include(":locale")
include(":ui")

include(":feature:actions")
include(":feature:auth")
include(":feature:commitdetail")
include(":feature:discussiondetail")
include(":feature:discovery")
include(":feature:editor")
include(":feature:explore")
include(":feature:home")
include(":feature:issuesearch")
include(":feature:markdown")
include(":feature:notifications")
include(":feature:profile")
include(":feature:profilecollections")
include(":feature:pullrequestcommits")
include(":feature:pullrequestdiff")
include(":feature:releases")
include(":feature:repositories")
include(":feature:repository")
include(":feature:repositoryactivity")
include(":feature:search")
include(":feature:settings")
include(":feature:starred")
include(":feature:workdetail")
include(":feature:worklist")
include(":feature:workspaces")

include(":markdown:core")
include(":markdown:compose")
include(":markdown:sample")

include(":pack")
include(":app")
