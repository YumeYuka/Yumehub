package moe.yumeyuka.yumehub.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPreviewTest {
    @Test
    fun actionsNotificationsUseCheckConclusionAndOpenWorkflowRun() {
        val runUrl = "https://github.com/owner/repo/actions/runs/123"
        val page =
            Json.parseToJsonElement(
                    """
                    {
                      "pageInfo": {"hasNextPage": false},
                      "nodes": [
                        {
                          "id": "check-thread", "title": "Build failed", "isUnread": true,
                          "threadType": "CheckSuite", "reason": "CI_ACTIVITY",
                          "lastUpdatedAt": "2026-11-25T00:00:00Z",
                          "summaryItemBody": "The run has failed.",
                          "list": {"__typename": "Repository", "name": "repo", "owner": {"login": "owner"}},
                          "subject": {
                            "__typename": "CheckSuite", "status": "COMPLETED", "conclusion": "FAILURE",
                            "url": "https://github.com/owner/repo/commit/abc/checks",
                            "workflowRun": {"url": "$runUrl"}
                          }
                        },
                        {
                          "id": "run-thread", "title": "Build started", "isUnread": false,
                          "lastUpdatedAt": "2026-11-25T00:00:00Z",
                          "subject": {
                            "__typename": "WorkflowRun", "url": "$runUrl",
                            "checkSuite": {"status": "IN_PROGRESS", "conclusion": null}
                          }
                        }
                      ]
                    }
                    """
                        .trimIndent(),
                )
                .jsonObject
        val (failed, running) = parseNotificationPage(page, after = null).items
        assertTrue(failed.unread)
        assertEquals("ci_activity", failed.reason)
        assertEquals("owner/repo", failed.repository?.fullName)
        assertEquals("The run has failed.", failed.preview?.body)
        assertEquals("FAILURE", failed.preview?.state)
        assertEquals(runUrl, failed.subject?.url)
        assertEquals("IN_PROGRESS", running.preview?.state)
        assertEquals(runUrl, running.subject?.url)
    }

    @Test
    fun securityThreadsUseTheirPermalinkOrThreadUrl() {
        val alertsUrl = "https://github.com/owner/repo/security/dependabot"
        val threadUrl = "https://github.com/orgs/owner/feature_requests/123"
        val page =
            Json.parseToJsonElement(
                    """
                    {
                      "pageInfo": {"hasNextPage": false},
                      "nodes": [
                        {
                          "id": "security", "title": "Dependabot alert", "isUnread": true,
                          "lastUpdatedAt": "2026-11-25T00:00:00Z",
                          "subject": {
                            "__typename": "RepositoryDependabotAlertsThread",
                            "notificationsPermalink": "$alertsUrl"
                          }
                        },
                        {
                          "id": "feature", "title": "Request", "isUnread": true,
                          "lastUpdatedAt": "2026-11-25T00:00:00Z", "url": "$threadUrl",
                          "subject": {"__typename": "MemberFeatureRequestNotification"}
                        }
                      ]
                    }
                    """
                        .trimIndent(),
                )
                .jsonObject
        val (security, request) = parseNotificationPage(page, after = null).items
        assertEquals("RepositoryDependabotAlertsThread", security.subject?.type)
        assertEquals(alertsUrl, security.subject?.url)
        assertEquals("MemberFeatureRequestNotification", request.subject?.type)
        assertEquals(threadUrl, request.subject?.url)
    }
}
