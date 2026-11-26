package moe.yumeyuka.yumehub.data.repository

import androidx.paging.PagingData
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.api.dto.UserDto
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationRejectedException
import moe.yumeyuka.yumehub.data.cache.RemoteFetch
import moe.yumeyuka.yumehub.data.cache.relationshipCacheTag
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

class ProfileCollectionsRepository(
    private val api: GitHubGraphQlApi,
    private val paging: PagingRepository,
    private val tokenStore: TokenStore,
    private val cache: CachedDataRepository,
) {
    val account: String
        get() = tokenStore.accountLogin.value

    suspend fun mutations(account: String): MutationCoordinator = cache.mutations(account)

    fun starredChanges(account: String) = cache.starredRows(account).changes { it }

    fun listChanges(account: String, listId: String) =
        cache.listRows(account).changes(listId) { listId in it }

    fun submitRepositoryLists(
        account: String,
        row: RepositoryDto,
        before: Set<String>,
        after: Set<String>,
    ): Boolean {
        val id = row.nodeId ?: return false
        val added = after - before
        val removed = before - after
        val accepted =
            cache
                .mutations(account)
                .submit(
                    repositoryListsKey(id),
                    before.sorted(),
                    after.sorted(),
                    setOf(CacheTag(DataDomain.Repositories, null)),
                    send = {
                        val remote =
                            try {
                                repositoryListIds(account, id)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (error: Exception) {
                                throw MutationRejectedException(cause = error)
                            }
                        val next = (remote - removed) + added
                        if (remote == next) remote.sorted()
                        else updateRepositoryLists(account, id, next)
                    },
                    readBack = { repositoryListIds(account, id).sorted() },
                )
        if (accepted) cache.listRows(account).track(id, row, added + removed)
        return accepted
    }

    fun repositoriesKey(login: String, organization: Boolean): String {
        val type = if (organization) "Organization" else "User"
        return "profile-repos.v2:${login.lowercase(Locale.ROOT)}:$type"
    }

    fun starredKey(account: String, login: String, query: String = ""): String =
        if (account.equals(login, true) && query.isBlank()) StarredRepositoriesPagingKey
        else
            "profile-stars.v3:${login.lowercase(Locale.ROOT)}:${query.trim().lowercase(Locale.ROOT)}"

    fun listItemsKey(login: String, slug: String, search: String = ""): String =
        "user-list.v1:${login.lowercase(Locale.ROOT)}:${slug.lowercase(Locale.ROOT)}:" +
            search.trim().lowercase(Locale.ROOT)

    fun organizationsKey(login: String): String = "profile-orgs.v2:${login.lowercase(Locale.ROOT)}"

    fun revalidation(account: String, key: String) = paging.revalidation(account, key)

    fun retryRevalidation(account: String, key: String) = paging.retryRevalidation(account, key)

    fun repositories(
        account: String,
        login: String,
        organization: Boolean,
    ): Flow<PagingData<RepositoryDto>> {
        val type = if (organization) "Organization" else "User"
        return pages(
            account,
            repositoriesKey(login, organization),
            login,
            "RepositoriesQuery",
            "repositoryOwner",
            "repositories",
            type,
            ", ownerAffiliations: [OWNER], orderBy: {field: UPDATED_AT, direction: DESC}",
            RepoFields,
            { it.nodeId.orEmpty() },
            ::repositoryItem,
            serializer = RepositoryDto.serializer(),
            tags = setOf(CacheTag(DataDomain.Repositories, null)),
        )
    }

    fun starred(
        account: String,
        login: String,
        query: String = "",
    ): Flow<PagingData<RepositoryDto>> =
        pages(
            account,
            starredKey(account, login, query),
            login,
            "StarredRepositories",
            "repositoryOwner",
            "starredRepositories",
            "User",
            if (query.isBlank()) ", orderBy: {field: STARRED_AT, direction: DESC}"
            else ", query: \u0024query, orderBy: {field: STARRED_AT, direction: DESC}",
            RepoFields,
            { it.nodeId.orEmpty() },
            ::repositoryItem,
            query.trim().takeIf(String::isNotEmpty),
            serializer = RepositoryDto.serializer(),
            tags =
                setOf(
                    relationshipCacheTag(
                        if (account.equals(login, true)) "viewer:starred"
                        else "stars:${login.lowercase(Locale.ROOT)}",
                    ),
                ),
        )

    fun userListsTag(login: String) =
        CacheTag(DataDomain.Repositories, "user-lists:${login.lowercase(Locale.ROOT)}")

    private fun catalog(account: String, login: String) =
        cache.resource(
            account,
            "user-lists-v1:${login.lowercase(Locale.ROOT)}",
            ListSerializer(UserRepositoryList.serializer()),
            tags = setOf(userListsTag(login)),
        )

    fun observeUserLists(account: String, login: String) =
        catalog(account, login).observe {
            RemoteFetch.Fresh(fetchUserLists(account, login))
        }

    fun refreshUserLists(account: String, login: String) =
        catalog(account, login).refresh(force = true) { fetchUserLists(account, login) }

    private suspend fun fetchUserLists(account: String, login: String): List<UserRepositoryList> {
        val session = GithubGraphQlSession(api, tokenStore, account)
        val query =
            "query UserLists(\u0024login: String!, \u0024first: Int!, \u0024after: String) { " +
                "user(login: \u0024login) { id lists(first: \u0024first, after: \u0024after) { " +
                "pageInfo { endCursor hasNextPage } nodes { id name slug isPrivate description " +
                "items { totalCount } } } } }"
        val result = mutableListOf<UserRepositoryList>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var pageCount = 0
        do {
            if (pageCount++ >= 1000) throw GithubPageException()
            val variables = buildJsonObject {
                put("login", login)
                put("first", 100)
                if (cursor != null) put("after", cursor)
            }
            val page = session.execute(query, variables).graphObject("user").graphObject("lists")
            result += page.graphNodes().map(::userRepositoryList)
            cursor = page.graphCursor(cursor)
            if (cursor != null && !seenCursors.add(cursor)) throw GithubPageException()
        } while (cursor != null)
        return result
    }

    suspend fun createUserList(account: String, name: String): UserRepositoryList {
        val session = GithubGraphQlSession(api, tokenStore, account)
        val mutation =
            "mutation CreateNewList(\u0024input: CreateUserListInput!) { " +
                "createUserList(input: \u0024input) { list { id name slug isPrivate description " +
                "items { totalCount } } } }"
        val variables = buildJsonObject {
            put(
                "input",
                buildJsonObject {
                    put("name", name.trim())
                    put("description", "")
                    put("isPrivate", false)
                },
            )
        }
        return session
            .execute(mutation, variables, write = true)
            .graphObject("createUserList")
            .graphObject("list")
            .let(::userRepositoryList)
            .also { created ->
                val catalog = catalog(account, account)
                catalog.state.value.data?.let { lists ->
                    if (lists.none { it.id == created.id }) catalog.update(lists + created)
                }
            }
    }

    suspend fun repositoryListIds(account: String, repositoryId: String): Set<String> {
        val session = GithubGraphQlSession(api, tokenStore, account)
        val query =
            "query RepositoryUserLists(\u0024id: ID!, \u0024first: Int!, \u0024after: String) { " +
                "node(id: \u0024id) { ... on Repository { lists(first: \u0024first, " +
                "after: \u0024after, onlyOwnedByViewer: true) { pageInfo { endCursor " +
                "hasNextPage } nodes { id } } } } }"
        val result = mutableSetOf<String>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var pageCount = 0
        do {
            if (pageCount++ >= 1000) throw GithubPageException()
            val variables = buildJsonObject {
                put("id", repositoryId)
                put("first", 100)
                if (cursor != null) put("after", cursor)
            }
            val page = session.execute(query, variables).graphObject("node").graphObject("lists")
            result += page.graphNodes().map { it.graphRequiredText("id") }
            cursor = page.graphCursor(cursor)
            if (cursor != null && !seenCursors.add(cursor)) throw GithubPageException()
        } while (cursor != null)
        return result
    }

    private suspend fun updateRepositoryLists(
        account: String,
        repositoryId: String,
        listIds: Set<String>,
    ): List<String> {
        val session = GithubGraphQlSession(api, tokenStore, account)
        val mutation =
            "mutation UpdateUserListsForItem(\u0024input: UpdateUserListsForItemInput!) { " +
                "updateUserListsForItem(input: \u0024input) { item { ... on Repository { id lists(first: 100, onlyOwnedByViewer: true) { pageInfo { hasNextPage endCursor } nodes { id } } } } } }"
        val variables = buildJsonObject {
            put(
                "input",
                buildJsonObject {
                    put("itemId", repositoryId)
                    put(
                        "listIds",
                        kotlinx.serialization.json.buildJsonArray {
                            listIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                        },
                    )
                    put("suggestedListIds", kotlinx.serialization.json.buildJsonArray {})
                },
            )
        }
        val item =
            session
                .execute(mutation, variables, write = true)
                .graphObject("updateUserListsForItem")
                .graphObject("item")
        if (item.graphRequiredText("id") != repositoryId) throw GithubPageException()
        val lists = item.graphObject("lists")
        if (lists.graphCursor(null) == null)
            return lists.graphNodes().map { it.graphRequiredText("id") }.sorted()
        return try {
            repositoryListIds(account, repositoryId).sorted()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            throw java.io.IOException("Could not read all lists after update", error)
        }
    }

    fun listItems(
        account: String,
        login: String,
        slug: String,
        search: String = "",
    ): Flow<PagingData<RepositoryDto>> =
        githubGraphQlPager(
            account,
            listItemsKey(login, slug, search),
            paging,
            { it.nodeId.orEmpty() },
            serializer = RepositoryDto.serializer(),
            tags = setOf(CacheTag(DataDomain.Repositories, null)),
        ) {
            val session = GithubGraphQlSession(api, tokenStore, account)
            val query =
                "query FetchList(\u0024login: String!, \u0024slug: String!, \u0024first: Int!, " +
                    "\u0024after: String) { list(login: \u0024login, slug: \u0024slug) { " +
                    "items(first: \u0024first, after: \u0024after) { pageInfo { endCursor " +
                    "hasNextPage } nodes { ... on Repository { $RepoFields } } } } }"
            { after ->
                val needle = search.trim()
                val seenCursors = mutableSetOf<String>()
                after?.let(seenCursors::add)
                var cursor = after
                var pageCount = 0
                var matchingItems = emptyList<RepositoryDto>()
                do {
                    if (pageCount++ >= 1000) throw GithubPageException()
                    val variables = buildJsonObject {
                        put("login", login)
                        put("slug", slug)
                        put("first", 30)
                        if (cursor != null) put("after", cursor)
                    }
                    val page =
                        session.execute(query, variables).graphObject("list").graphObject("items")
                    val repositories = page.graphNodes().map(::repositoryItem)
                    cursor = page.graphCursor(cursor)
                    if (cursor != null && !seenCursors.add(cursor)) throw GithubPageException()
                    matchingItems =
                        if (needle.isBlank()) repositories
                        else
                            repositories.filter { repository ->
                                repository.fullName.orEmpty().contains(needle, ignoreCase = true) ||
                                    repository.name.orEmpty().contains(needle, ignoreCase = true) ||
                                    repository.description
                                        .orEmpty()
                                        .contains(needle, ignoreCase = true)
                            }
                } while (matchingItems.isEmpty() && cursor != null)
                GithubGraphQlPage(matchingItems, cursor)
            }
        }

    fun organizations(account: String, login: String): Flow<PagingData<UserDto>> {
        val viewer = account.equals(login, true)
        return pages(
            account,
            organizationsKey(login),
            login,
            if (viewer) "ViewerOrganizationsQuery" else "OrganizationsQuery",
            if (viewer) "viewer" else "user",
            "organizations",
            null,
            "",
            "__typename login name avatarUrl description url",
            { it.login.orEmpty() },
            { it.dashboardUser() },
            serializer = UserDto.serializer(),
            tags = setOf(CacheTag(DataDomain.Profile, null)),
        )
    }

    private fun <T : Any> pages(
        account: String,
        key: String,
        login: String,
        operation: String,
        root: String,
        connection: String,
        type: String?,
        args: String,
        fields: String,
        identity: (T) -> String,
        decode: (JsonObject) -> T,
        searchQuery: String? = null,
        serializer: KSerializer<T>,
        tags: Set<CacheTag> = emptySet(),
    ): Flow<PagingData<T>> =
        githubGraphQlPager(account, key, paging, identity, serializer = serializer, tags = tags) {
            val session = GithubGraphQlSession(api, tokenStore, account)
            val loginVariable = if (root == "viewer") "" else "\u0024login: String!, "
            val loginArgument = if (root == "viewer") "" else "(login: \u0024login)"
            val queryVariable = if (searchQuery == null) "" else "\u0024query: String, "
            val selection =
                "$connection(first: \u0024first, after: \u0024after$args) { pageInfo { endCursor hasNextPage } nodes { $fields } }"
            val body = if (type == null) selection else "... on $type { $selection }"
            val gqlQuery =
                "query $operation(${loginVariable}${queryVariable}\u0024first: Int!, \u0024after: String) { $root$loginArgument { $body } }"
            val load: suspend (String?) -> GithubGraphQlPage<T> = { after ->
                val variables = buildJsonObject {
                    if (root != "viewer") put("login", login)
                    if (searchQuery != null) put("query", searchQuery)
                    put("first", 30)
                    if (after != null) put("after", after)
                }
                val page =
                    session.execute(gqlQuery, variables).graphObject(root).graphObject(connection)
                GithubGraphQlPage(page.graphNodes().map(decode), page.graphCursor(after))
            }
            load
        }
}

@Serializable
data class UserRepositoryList(
    val id: String,
    val name: String,
    val slug: String,
    val isPrivate: Boolean,
    val description: String?,
    val itemCount: Int,
)

private fun userRepositoryList(node: JsonObject): UserRepositoryList {
    val items = node.graphObject("items")
    return UserRepositoryList(
        id = node.graphRequiredText("id"),
        name = node.graphRequiredText("name"),
        slug = node.graphRequiredText("slug"),
        isPrivate = node.graphText("isPrivate")?.toBooleanStrictOrNull() ?: false,
        description = node.graphText("description"),
        itemCount = items.graphText("totalCount")?.toIntOrNull() ?: 0,
    )
}

internal const val RepoFields =
    "id databaseId name url description isPrivate isFork owner { __typename login avatarUrl } primaryLanguage { name } stargazerCount forkCount"

internal const val RepositoryCardFields = "$RepoFields openGraphImageUrl"

internal fun repositoryItem(node: JsonObject): RepositoryDto =
    (node.dashboardRepository() ?: throw GithubPageException()).copy(
        id = node.graphText("databaseId")?.toLongOrNull(),
        nodeId = node.graphRequiredText("id"),
        private = node.graphText("isPrivate")?.toBooleanStrictOrNull(),
        fork = node.graphText("isFork")?.toBooleanStrictOrNull(),
        forksCount = node.graphText("forkCount")?.toIntOrNull(),
    )
