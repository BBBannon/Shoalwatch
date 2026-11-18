package app.shoalwatch.ui.player

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import app.shoalwatch.R
import app.shoalwatch.data.playback.UpNextState
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.id
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The order TalkBack reads the player's corner in (Milestone 7 Task 8): with the watch-and-delete card and Up next both shown,
 * the card's text, its buttons, then Up next's title and buttons, then the player controls. The order is computed by Compose's
 * own accessibility ordering function (see [traversal]).
 */
abstract class PlayerCornerOrderBase : RenderBase() {
    private val log = ActionLog()
    private val prompt = WatchedPromptUi(app.shoalwatch.ui.browse.BEN, id(101), film = false, together = 2, always = false)
    private val upNext = UpNextState(id(102), "The Turn", 8, cancelled = false)

    private fun showBoth(together: Int = 2) = show {
        PlayerScreen(ui(playingEpisode.copy(upNext = upNext)).copy(watched = prompt.copy(together = together), controlsVisible = true), log.actions)
    }

    /**
     * The unmerged semantics nodes in the order TalkBack is handed them: Compose's own accessibility ordering
     * (`setTraversalValues`, the function its accessibility delegate runs to fill each node's "traversal before", honouring
     * `isTraversalGroup` and `traversalIndex`), called through reflection over the whole tree (no public API exposes the order;
     * the delegate itself keeps only the nodes Robolectric's window reports uncovered).
     */
    private fun traversal(): List<SemanticsNode> {
        rule.waitForIdle()
        val root = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val nodes = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().associateBy { it.id } + (root.id to root)
        val all = androidx.collection.MutableIntObjectMap<Any>()
        val withBounds = Class.forName("androidx.compose.ui.semantics.SemanticsNodeWithAdjustedBounds")
            .getConstructor(SemanticsNode::class.java, androidx.compose.ui.unit.IntRect::class.java)
        fun bounded(n: SemanticsNode): Any = withBounds.newInstance(n, n.boundsInRoot.let {
            androidx.compose.ui.unit.IntRect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
        })
        nodes.values.forEach { all[it.id] = bounded(it) }
        all[-1] = bounded(root) // the host view's id, where the ordering starts
        val before = androidx.collection.MutableIntIntMap()
        val after = androidx.collection.MutableIntIntMap()
        rule.runOnIdle {
            Class.forName("androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat_androidKt")
                .getMethod("access\$setTraversalValues", androidx.collection.IntObjectMap::class.java, androidx.collection.MutableIntIntMap::class.java,
                    androidx.collection.MutableIntIntMap::class.java, android.content.res.Resources::class.java)
                .invoke(null, all, before, after, context.resources)
        }
        assertTrue("no traversal order", before.size > 0)
        var at = -1
        before.forEachKey { k -> if (at == -1 && !after.containsKey(k)) at = k } // the head: nothing is read before it
        val order = mutableListOf<SemanticsNode>()
        val seen = HashSet<Int>()
        while (seen.add(at)) {
            nodes[at]?.let { order += it }
            at = before.getOrDefault(at, -1)
            if (at == -1) break
        }
        return order
    }

    private fun SemanticsNode.says(label: String): Boolean =
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == label } ||
            config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }

    private fun indexOf(order: List<SemanticsNode>, label: String): Int =
        order.indexOfFirst { n -> n.says(label) || n.children.any { it.says(label) } }.also { assertTrue("$label not in the order: ${order.map(::name)}", it >= 0) }

    private fun name(n: SemanticsNode): String = (n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).joinToString("/").ifEmpty { "#${n.id}" }

    private fun assertReadInOrder(labels: List<String>) {
        val order = traversal()
        val at = labels.map { indexOf(order, it) }
        assertEquals("read in this order: $labels\nwas: ${order.map(::name)}", at.sorted(), at)
    }

    private val spoken get() = "${text(R.string.watched_episode)} ${text(R.string.watched_delete_button_spoken)}"

    @Test fun theCardThenUpNextThenTheControls() {
        showBoth()
        assertReadInOrder(
            listOf(
                spoken, text(R.string.watched_delete), text(R.string.watched_together_label), text(R.string.watched_keep),
                text(R.string.up_next, "The Turn"), text(R.string.up_next_play), text(R.string.action_cancel),
                text(R.string.cd_back), text(R.string.player_lock), text(R.string.player_pause), // the controls: top bar, then the middle row
            ),
        )
    }

    @Test fun withoutWatchedTogetherTheOrderHolds() {
        showBoth(together = 0)
        assertReadInOrder(
            listOf(spoken, text(R.string.watched_delete), text(R.string.watched_keep), text(R.string.up_next, "The Turn"), text(R.string.cd_back)),
        )
    }

    /**
     * Review M3 of Tasks 7-8: the same rule without reflection, so a Compose change that breaks [traversal] (checked on Compose
     * BOM 2025.12.01) still leaves a guard: the corner holding both cards is a traversal group read first (index -1).
     */
    @Test fun theCornerIsATraversalGroupReadFirst() {
        showBoth()
        val corner = SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true)
            .and(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, -1f))
            .and(androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasTestTag(WATCHED_PROMPT_TAG)))
            .and(androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasTestTag(UP_NEXT_TAG)))
        assertEquals(1, rule.onAllNodes(corner, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun theCardIsAPoliteLiveRegion() {
        showBoth()
        rule.onNode(hasContentDescription(spoken).and(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, androidx.compose.ui.semantics.LiveRegionMode.Polite)))
            .assertExists()
    }

    @Test fun neitherCardIsClippedAndTheyDoNotMeet() {
        showBoth()
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val card = rule.onNodeWithTag(WATCHED_PROMPT_TAG).fetchSemanticsNode().boundsInRoot
        val next = rule.onNodeWithTag(UP_NEXT_TAG).fetchSemanticsNode().boundsInRoot
        listOf(card, next).forEach { b ->
            assertTrue("off screen: $b in $root", b.left >= root.left && b.top >= root.top && b.right <= root.right && b.bottom <= root.bottom)
        }
        assertTrue("they meet: $card and $next", !card.overlaps(next))
        // Every node inside either card lies inside the root (nothing pushed off the screen).
        rule.onAllNodes(androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(WATCHED_PROMPT_TAG) or androidx.compose.ui.test.hasTestTag(UP_NEXT_TAG)), useUnmergedTree = true).fetchSemanticsNodes().forEach { n ->
            val b = n.boundsInRoot
            if (b.width > 0 && b.height > 0) assertTrue("${name(n)} off screen: $b", b.left >= root.left - 0.5f && b.right <= root.right + 0.5f && b.bottom <= root.bottom + 0.5f)
        }
    }
}

@Config(qualifiers = PLAYER_PHONE) class PlayerCornerOrderPhoneTest : PlayerCornerOrderBase() { override val fontScale = 1f }
@Config(qualifiers = PLAYER_PHONE) class PlayerCornerOrderPhoneLargeTextTest : PlayerCornerOrderBase() { override val fontScale = 2f }
@Config(qualifiers = PLAYER_TABLET) class PlayerCornerOrderTabletTest : PlayerCornerOrderBase() { override val fontScale = 1f }
@Config(qualifiers = PLAYER_TABLET) class PlayerCornerOrderTabletLargeTextTest : PlayerCornerOrderBase() { override val fontScale = 2f }
