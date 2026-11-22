package moe.yumeyuka.yumehub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GithubReactionsTest {
    @Test
    fun toggleExistingReactionLeavesOtherReactionsUntouched() {
        val heart = GithubReactionGroup(GithubReactionContent.HEART, 2, viewerReacted = false)
        val eyes = GithubReactionGroup(GithubReactionContent.EYES, 4, viewerReacted = true)
        val initial = listOf(heart, eyes)

        val added = initial.withToggledReaction(heart.content)
        assertEquals(listOf(heart.copy(count = 3, viewerReacted = true), eyes), added)
        assertEquals(initial, added.withToggledReaction(heart.content))
    }

    @Test
    fun cancellingOnlyReactionRemovesItsGroup() {
        val content = GithubReactionContent.HEART
        val added = emptyList<GithubReactionGroup>().withToggledReaction(content)

        assertEquals(listOf(GithubReactionGroup(content, 1, viewerReacted = true)), added)
        assertEquals(emptyList<GithubReactionGroup>(), added.withToggledReaction(content))
    }

    @Test
    fun confirmedResponseOnlyOverridesOriginalGroupsForItsSubject() {
        val updates = GithubReactionUpdates()
        val heart = GithubReactionGroup(GithubReactionContent.HEART, 1, viewerReacted = true)
        val source = emptyList<GithubReactionGroup>()
        val confirmed = GithubReactionUpdate(source, listOf(heart), pending = false)
        updates.set("comment", confirmed)

        assertEquals(confirmed, updates.current("comment", source))
        assertNull(updates.current("other-comment", source))
        assertNull(updates.current("comment", listOf(heart)))
        assertNull(updates.current("comment", listOf(heart.copy(count = 2))))
    }

    @Test
    fun failedRemovalRestoresLastConfirmedResponse() {
        val updates = GithubReactionUpdates()
        val source = emptyList<GithubReactionGroup>()
        val added = source.withToggledReaction(GithubReactionContent.HEART)
        val confirmed = GithubReactionUpdate(source, added, pending = false)
        updates.set("comment", confirmed)

        val previous = updates.current("comment", source)
        val removing = GithubReactionUpdate(source, source, pending = true)
        updates.set("comment", removing)
        assertEquals(removing, updates.current("comment", source))

        updates.set("comment", previous)
        assertEquals(confirmed, updates.current("comment", source))
    }
}
