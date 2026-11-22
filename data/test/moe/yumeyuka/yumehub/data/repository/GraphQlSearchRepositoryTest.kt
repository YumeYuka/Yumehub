package moe.yumeyuka.yumehub.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import moe.yumeyuka.yumehub.data.api.dto.GraphQlResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphQlSearchRepositoryTest {
    @Test
    fun repositorySearchMapsNodeIdAndForkCount() {
        val response =
            Json.decodeFromString<GraphQlResponse>(
                """
                {
                  "data": {
                    "search": {
                      "repositoryCount": 1,
                      "pageInfo": { "hasNextPage": false, "endCursor": null },
                      "nodes": [{
                        "__typename": "Repository",
                        "id": "R_sample",
                        "name": "sample",
                        "url": "https://github.com/octo/sample",
                        "owner": {
                          "__typename": "User",
                          "login": "octo",
                          "avatarUrl": "https://avatars.githubusercontent.com/u/1"
                        },
                        "stargazerCount": 12,
                        "forkCount": 34
                      }]
                    }
                  }
                }
                """.trimIndent(),
            )

        val result = parseGraphQlSearch(response, after = null, repositorySearch = true)

        assertTrue(SearchQuery.contains("stargazerCount forkCount"))
        assertEquals("R_sample", result.repositories.single().nodeId)
        assertEquals(34, result.repositories.single().forksCount)
    }

    @Test
    fun workSearchMapsParticipantAvatarsForIssuesAndPullRequests() {
        val response =
            Json.decodeFromString<GraphQlResponse>(
                """
                {
                  "data": {
                    "search": {
                      "issueCount": 2,
                      "pageInfo": { "hasNextPage": false, "endCursor": null },
                      "nodes": [
                        {
                          "__typename": "Issue",
                          "number": 1,
                          "title": "Issue",
                          "issueState": "OPEN",
                          "participants": {
                            "totalCount": 5,
                            "nodes": [{
                              "login": "commenter",
                              "avatarUrl": "https://avatars.githubusercontent.com/u/1"
                            }]
                          },
                          "assignedActors": { "nodes": [{ "login": "assignee" }] }
                        },
                        {
                          "__typename": "PullRequest",
                          "number": 2,
                          "title": "Pull request",
                          "pullRequestState": "OPEN",
                          "headRefName": "feature/example",
                          "baseRefName": "main",
                          "bodyText": "  Plain-text summary  ",
                          "participants": { "totalCount": 7, "nodes": [{ "login": "reviewer" }] },
                          "reviewDecision": "CHANGES_REQUESTED",
                          "commits": {
                            "nodes": [{ "commit": { "statusCheckRollup": { "state": "PENDING" } } }]
                          },
                          "additions": 14,
                          "deletions": 3
                        }
                      ]
                    }
                  }
                }
                """
                    .trimIndent(),
            )

        val (issue, pull) = parseGraphQlSearch(response, after = null, repositorySearch = false).work

        val issueParticipant = issue.participantAvatars.single()
        assertEquals("commenter", issueParticipant.login)
        assertEquals("https://avatars.githubusercontent.com/u/1", issueParticipant.avatarUrl)
        assertEquals(5, issue.participantsCount)
        assertNull(issue.bodyPreview)
        assertNull(issue.checkState)
        assertNull(issue.headRefName)
        assertNull(issue.baseRefName)
        assertEquals("reviewer", pull.participantAvatars.single().login)
        assertEquals("feature/example", pull.headRefName)
        assertEquals("main", pull.baseRefName)
        assertEquals(7, pull.participantsCount)
        assertEquals("Plain-text summary", pull.bodyPreview)
        assertEquals("CHANGES_REQUESTED", pull.reviewDecision)
        assertEquals("PENDING", pull.checkState)
        assertEquals(14, pull.additions)
        assertEquals(3, pull.deletions)
    }
}
