package moe.yumeyuka.yumehub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.WorkProperty
import moe.yumeyuka.yumehub.data.api.dto.WorkPropertyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorkPropertyRequestsTest {
    private fun issue(request: suspend (String, JsonObject, Boolean) -> JsonObject) =
        WorkPropertiesGraphQl("owner", "repo", CollaborationKind.Issue, 7, request)

    private fun data(text: String) = Json.parseToJsonElement(text).jsonObject

    private val target =
        data(
            """{"repository":{"viewerPermission":"WRITE","issueOrPullRequest":{"__typename":"Issue","id":"I","milestone":null}}}""",
        )

    @Test
    fun milestoneUsesOneReadThenOneWrite() = runBlocking {
        val writes = mutableListOf<Boolean>()
        val graph = issue { query, variables, write ->
            writes.add(write)
            if (write) {
                assertEquals("I", variables.graphObject("input").graphRequiredText("id"))
                assertEquals("M", variables.graphObject("input").graphRequiredText("milestoneId"))
                data("""{"updateIssue":{"issue":{"id":"I"}}}""")
            } else {
                assertTrue(query.contains("milestone { id title }"))
                target
            }
        }
        graph.save(WorkProperty.Milestone, emptyList(), listOf(WorkPropertyOption("M", "Next")))
        assertEquals(listOf(false, true), writes)
    }

    @Test
    fun labelConflictOnLaterPagePreventsWriteAndSkipsOtherProperties() = runBlocking {
        val cursors = mutableListOf<String?>()
        val graph = issue { query, variables, write ->
            assertFalse(write)
            if (query.contains("WorkPropertyTarget")) target
            else {
                assertTrue(query.contains("labels(first:"))
                cursors.add(variables.graphText("after"))
                if (cursors.size == 1) {
                    data(
                        """{"node":{"labels":{"nodes":[],"pageInfo":{"hasNextPage":true,"endCursor":"p2"}}}}""",
                    )
                } else {
                    data(
                        """{"node":{"labels":{"nodes":[{"id":"L","name":"bug"}],"pageInfo":{"hasNextPage":false}}}}""",
                    )
                }
            }
        }
        try {
            graph.save(WorkProperty.Labels, emptyList(), emptyList())
            fail("Expected concurrent label change")
        } catch (_: WorkDetailReviewChangedException) {
            assertEquals(listOf(null, "p2"), cursors)
        }
    }

    @Test
    fun projectReadFailureOrCancellationNeverBecomesAnEmptyWriteBaseline() = runBlocking {
        for (failure in listOf(GithubPageException(), CancellationException("changed session"))) {
            val graph = issue { query, _, write ->
                assertFalse(write)
                if (query.contains("WorkPropertyTarget")) target
                else {
                    assertTrue(query.contains("projectItems(first:"))
                    throw failure
                }
            }
            try {
                graph.save(WorkProperty.Projects, emptyList(), listOf(WorkPropertyOption("P", "P")))
                fail("Expected project read failure")
            } catch (error: Exception) {
                assertSame(failure, error)
            }
        }
    }

    @Test
    fun subIssueSearchFiltersCurrentAndParentedIssuesInOneRequest() = runBlocking {
        var calls = 0
        val graph = issue { query, variables, write ->
            calls++
            assertFalse(write)
            assertTrue(query.contains("issueOrPullRequest(number:"))
            assertTrue(query.contains("search(query:"))
            assertEquals("7", variables.graphText("number"))
            assertEquals("prev", variables.graphText("after"))
            data(
                """{
                  "repository":{"issueOrPullRequest":{"__typename":"Issue","id":"I"}},
                  "search":{"pageInfo":{"hasNextPage":true,"endCursor":"next"},"nodes":[
                    {"id":"I"}, {"id":"parented","parent":{"id":"another"}},
                    {"id":"eligible","number":9,"title":"Child","issueState":"OPEN","repository":{"nameWithOwner":"owner/repo"}}
                  ]}
                }""",
            )
        }
        val page = graph.subIssueOptions("Child", "prev")
        assertEquals(listOf("eligible"), page.items.map { it.id })
        assertEquals("next", page.nextCursor)
        assertEquals(1, calls)
    }
}
