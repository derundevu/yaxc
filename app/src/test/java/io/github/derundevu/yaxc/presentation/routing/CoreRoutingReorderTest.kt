package io.github.derundevu.yaxc.presentation.routing

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import io.github.derundevu.yaxc.helper.CoreRoutingEditorMode
import io.github.derundevu.yaxc.helper.CoreRoutingRule
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.editor.JsonEditorState
import io.github.derundevu.yaxc.testing.RegressionApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34], qualifiers = "w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoreRoutingReorderTest {
    @get:Rule val compose = createComposeRule()
    private val first = CoreRoutingRule(id = "first", name = "First", domainsText = "full:first.invalid", outboundTag = "direct")
    private val last = CoreRoutingRule(id = "last", name = "Last", ipsText = "192.0.2.0/24", enabled = false, outboundTag = "block")
    private val initial = listOf(first, last)
    private val rules = mutableStateOf(initial)
    private val busy = mutableStateOf(false)
    private val moves = mutableListOf<Pair<Int, Int>>()
    private var saves = 0

    @Test
    fun previewDoesNotChangePrecedenceAndDropCommitsOnceWithStableRuleContents() {
        showEditor()
        beginMove()
        compose.runOnIdle {
            assertEquals(initial, rules.value)
            assertTrue(moves.isEmpty())
            assertEquals(0, saves)
        }
        header(first).performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(last, first), rules.value)
            assertEquals(listOf(0 to 1), moves)
            assertEquals(0, saves)
        }
        assertAbove(last, first)
    }

    @Test
    fun cancelAndBusyTransitionRestoreOriginalOrderWithoutCommitting() {
        showEditor()
        beginMove()
        header(first).performTouchInput { cancel() }
        compose.runOnIdle {
            assertEquals(initial, rules.value)
            assertTrue(moves.isEmpty())
        }
        assertAbove(first, last)

        beginMove()
        compose.runOnIdle { busy.value = true }
        header(first).performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(initial, rules.value)
            assertTrue(moves.isEmpty())
            assertEquals(0, saves)
        }
        assertAbove(first, last)
    }

    @Test
    fun collapsedRulePassingExpandedNeighbourKeepsReleaseFramesContinuous() {
        val middle = first.copy(id = "middle", name = "Middle")
        val original = listOf(first, middle, last)
        rules.value = original
        showEditor()
        header(last).performClick()
        compose.onNodeWithTag("core-routing:content").performScrollToKey(first.id)
        compose.waitForIdle()
        val from = header(first)
        val distance = header(last).fetchSemanticsNode().boundsInRoot.center.y -
            from.fetchSemanticsNode().boundsInRoot.center.y
        compose.mainClock.autoAdvance = false
        try {
            from.performTouchInput {
                down(Offset(width / 4f, height / 2f))
                advanceEventTime(700)
                moveBy(Offset(0f, distance), delayMillis = 400)
            }
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(original, rules.value) }
            val heldY = header(first).fetchSemanticsNode().boundsInRoot.center.y
            val positions = mutableListOf(heldY)
            header(first).performTouchInput { up() }
            repeat(48) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                positions += header(first).fetchSemanticsNode().boundsInRoot.center.y
            }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            val settledY = header(first).fetchSemanticsNode().boundsInRoot.center.y
            val distanceToSlot = settledY - heldY
            // A last collapsed card can legitimately settle lower when the viewport clamps.
            // Require a trajectory, not an instantaneous reset or an impossible fixed anchor.
            assertEquals(heldY, positions[1], 1f)
            assertEquals(heldY, positions[2], 1f)
            val direction = if (distanceToSlot >= 0f) 1f else -1f
            // Match Main's slightly underdamped spring; ignore only its sub-percent overshoot.
            val epsilon = maxOf(1f, abs(distanceToSlot) * 0.01f)
            positions.forEach { y ->
                assertTrue("Release must not overshoot its slot", y >= minOf(heldY, settledY) - epsilon && y <= maxOf(heldY, settledY) + epsilon)
            }
            if (abs(distanceToSlot) > epsilon) {
                assertTrue("Clamped release must include intermediate positions", positions.any { y ->
                    direction * (y - heldY) > epsilon && direction * (settledY - y) > epsilon
                })
                for (index in 1 until positions.size) {
                    assertTrue("Release must not reverse direction", direction * (positions[index] - positions[index - 1]) >= -epsilon)
                }
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.runOnIdle {
            assertEquals(listOf(middle, last, first), rules.value)
            assertEquals(listOf(0 to 2), moves)
            assertEquals(0, saves)
        }
        assertAbove(last, first)
    }

    private fun beginMove() {
        compose.onNodeWithTag("core-routing:content").performScrollToKey(first.id)
        val from = header(first)
        val distance = header(last).fetchSemanticsNode().boundsInRoot.top -
            from.fetchSemanticsNode().boundsInRoot.top
        from.performTouchInput {
            down(Offset(width / 4f, height / 2f))
            advanceEventTime(700)
            moveBy(Offset(0f, distance), delayMillis = 400)
        }
        compose.waitForIdle()
    }

    private fun header(rule: CoreRoutingRule) = compose.onNodeWithTag("core-routing:rule-header:${rule.id}")

    private fun assertAbove(upper: CoreRoutingRule, lower: CoreRoutingRule) {
        val upperBounds = compose.onNodeWithTag("core-routing:rule:${upper.id}").fetchSemanticsNode().boundsInRoot
        val lowerBounds = compose.onNodeWithTag("core-routing:rule:${lower.id}").fetchSemanticsNode().boundsInRoot
        assertTrue("Final card order must match rule precedence", upperBounds.top < lowerBounds.top)
    }

    private fun showEditor() {
        val editor = JsonEditorState()
        compose.setContent {
            YaxcTheme {
                CoreRoutingScreen(
                    editorMode = CoreRoutingEditorMode.Visual,
                    domainStrategy = "AsIs",
                    rules = rules.value,
                    unsupportedRuleCount = 0,
                    isLoading = false,
                    isBusy = busy.value,
                    savedRevision = 0,
                    editorState = editor,
                    antifilterEnabled = false,
                    antifilterInstalled = false,
                    antifilterRouteCount = 0,
                    onBack = {},
                    onSave = { saves++ },
                    onAntifilterEnabledChange = {},
                    onImportFromClipboard = {},
                    onExportJson = {},
                    onEditorModeChange = {},
                    onDomainStrategyChange = {},
                    onRuleChange = { changed -> rules.value = rules.value.map { if (it.id == changed.id) changed else it } },
                    onAddRule = {},
                    onMoveRule = { from, to ->
                        moves += from to to
                        rules.value = rules.value.toMutableList().apply { add(to, removeAt(from)) }
                    },
                    onDeleteRule = { id -> rules.value = rules.value.filterNot { it.id == id } },
                )
            }
        }
    }
}
