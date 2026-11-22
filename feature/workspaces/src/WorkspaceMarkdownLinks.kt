package moe.yumeyuka.yumehub.feature.workspaces

import moe.yumeyuka.yumehub.core.model.GithubGitObject
import moe.yumeyuka.yumehub.core.model.GithubLinkTarget
import moe.yumeyuka.yumehub.core.model.GithubRepositoryLocation
import moe.yumeyuka.yumehub.core.model.githubGitRefCandidates
import moe.yumeyuka.yumehub.core.model.parseGithubLink
import moe.yumeyuka.yumehub.data.repository.isRepositoryContentPath
import moe.yumeyuka.yumehub.ui.component.githubCodeLanguageForFilename

internal fun resolveMarkdownEditorLink(
    url: String,
    owner: String,
    name: String,
    ref: String,
): GithubGitObject? {
    val target = parseGithubLink(url) as? GithubLinkTarget.Repository ?: return null
    if (!target.owner.equals(owner, ignoreCase = true)) return null
    if (!target.name.equals(name, ignoreCase = true)) return null
    val location = target.location as? GithubRepositoryLocation.GitObject ?: return null
    if (location.directory) return null
    val path =
        githubGitRefCandidates(location)
            .firstOrNull { ref in it.qualifiedRefs }
            ?.path
            ?.takeIf(::isRepositoryContentPath) ?: return null
    if (githubCodeLanguageForFilename(path.substringAfterLast('/')) != "markdown") return null
    return GithubGitObject(ref, path, location.anchor)
}
