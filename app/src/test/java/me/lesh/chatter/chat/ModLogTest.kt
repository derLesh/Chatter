package me.lesh.chatter.chat

import me.lesh.chatter.eventsub.ModAction
import me.lesh.chatter.eventsub.ModTarget
import me.lesh.chatter.settings.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModLogTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val buffers = MessageBuffers(scope, dispatcher, MutableStateFlow(Settings()))
    private var clock = 1_000_000L
    private val log = ModLog(buffers, { mod, action ->
        when (action) {
            is ModAction.Timeout -> "$mod timed out ${action.target.name} for ${action.seconds}s"
            is ModAction.Ban -> "$mod banned ${action.target.name}"
            ModAction.Clear -> "$mod cleared"
            is ModAction.Unban -> "$mod unbanned ${action.target.name}"
            else -> null
        }
    }, now = { clock })
    private val viewer = ModTarget("viewer", "Viewer")

    private fun lines(body: (shown: () -> List<String>) -> Unit) = runTest(dispatcher) {
        buffers.open("forsen")
        val watcher = launch { buffers.messages("forsen").collect {} }
        body {
            advanceUntilIdle()
            buffers.messages("forsen").value.map { it.text }
        }
        watcher.cancel()
    }

    @Test
    fun eventSubNamesTheModOnTheLineIrcWrote() = lines { shown ->
        log.fromIrc("forsen", "viewer", 600, "viewer has been timed out for 600 seconds.")
        // Worked out from when the timeout ends, so a second off; IRC's number wins.
        log.fromEventSub("forsen", "Mod", ModAction.Timeout(viewer, 599, reason = null))
        assertEquals(listOf("Mod timed out Viewer for 600s"), shown())
    }

    @Test
    fun ircIsSkippedWhenEventSubWasFirst() = lines { shown ->
        log.fromEventSub("forsen", "Mod", ModAction.Ban(viewer, reason = null))
        log.fromIrc("forsen", "VIEWER", null, "viewer has been banned.")
        assertEquals(listOf("Mod banned Viewer"), shown())
    }

    @Test
    fun aClearPairsWithTheClearOnly() = lines { shown ->
        log.fromIrc("forsen", null, null, "Chat has been cleared by a moderator.")
        log.fromEventSub("forsen", "Mod", ModAction.Ban(viewer, reason = null))
        log.fromEventSub("forsen", "Mod", ModAction.Clear)
        assertEquals(listOf("Mod cleared", "Mod banned Viewer"), shown())
    }

    @Test
    fun aRepeatLaterIsANewLine() = lines { shown ->
        log.fromIrc("forsen", "viewer", 60, "first")
        clock += 60_000
        log.fromEventSub("forsen", "Mod", ModAction.Timeout(viewer, 60, reason = null))
        assertEquals(listOf("first", "Mod timed out Viewer for 60s"), shown())
    }

    @Test
    fun whatIrcNeverSaysIsAddedAsItComes() = lines { shown ->
        log.fromEventSub("forsen", "Mod", ModAction.Unban(viewer))
        log.fromEventSub("forsen", "Mod", ModAction.Mode(ModAction.ChatModeKind.Slow, on = true, value = 30))
        assertEquals(listOf("Mod unbanned Viewer"), shown())
    }
}
