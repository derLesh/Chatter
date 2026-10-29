package dev.chatter.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chatter.app.R
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.MessageBody
import dev.chatter.app.chat.MessageKind
import dev.chatter.app.chat.PowerUp
import dev.chatter.app.chat.Segment
import dev.chatter.app.emotes.Emote
import dev.chatter.app.emotes.EmoteProvider
import dev.chatter.app.emotes.twitchEmoteUrl
import dev.chatter.app.settings.Settings
import dev.chatter.app.settings.SpecialMessage
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.ui.chat.MessageRow
import dev.chatter.app.ui.chat.rememberChatStyle

internal val SPECIAL_MESSAGE_TITLES = listOf(
    SpecialMessage.GiantEmote to R.string.special_giant_emotes,
    SpecialMessage.Highlighted to R.string.special_highlighted,
    SpecialMessage.Effect to R.string.special_effects,
    SpecialMessage.Reward to R.string.special_rewards,
    SpecialMessage.WatchStreak to R.string.special_watch_streaks,
)

private fun hintOf(kind: SpecialMessage): Int = when (kind) {
    SpecialMessage.GiantEmote -> R.string.special_giant_emotes_hint
    SpecialMessage.Highlighted -> R.string.special_highlighted_hint
    SpecialMessage.Effect -> R.string.special_effects_hint
    SpecialMessage.Reward -> R.string.special_rewards_hint
    SpecialMessage.WatchStreak -> R.string.special_watch_streaks_hint
}

/** One switch per special message, each above a made-up message drawn the way it would be. */
@Composable
internal fun SpecialMessagesPage(settings: Settings, vm: MainViewModel) {
    val rewardName = stringResource(R.string.special_preview_reward)
    // Always drawn as the chat draws them when on, so the preview shows what the switch gives.
    val style = rememberChatStyle(settings, emptyMap(), rewardNames = mapOf(PREVIEW_REWARD to rewardName))
        .copy(specialMessages = SpecialMessage.entries.toSet())
    val text = stringResource(R.string.special_preview_text)
    SettingsGroup {
        SPECIAL_MESSAGE_TITLES.forEach { (kind, title) ->
            item(title) {
                val on = kind in settings.specialMessages
                Column {
                    SwitchItem(title, on, { vm.setSpecialMessage(kind, it) }, hintOf(kind))
                    val preview = remember(kind, text) { previewOf(kind, text) }
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                            .alpha(if (on) 1f else OFF_ALPHA),
                    ) {
                        // No padding around the row, so a highlight fills the whole preview.
                        MessageRow(preview, style, vm.imageLoader, onGesture = null)
                    }
                }
            }
        }
    }
}

private fun previewOf(kind: SpecialMessage, text: String): ChatItem {
    val message = ChatItem(
        id = "preview", channel = "", kind = MessageKind.Chat, timestamp = System.currentTimeMillis(),
        login = "chatter", displayName = "Chatter", color = 0xFF1E90FF.toInt(),
        body = MessageBody.of(listOf(Segment.Text(text))), text = text,
    )
    return when (kind) {
        SpecialMessage.GiantEmote -> message.copy(
            body = MessageBody.of(listOf(Segment.EmoteSeg(Emote("Kappa", "25", twitchEmoteUrl("25"), EmoteProvider.Twitch)))),
            text = "Kappa",
            powerUp = PowerUp.GiantEmote,
        )
        SpecialMessage.Highlighted -> message.copy(powerUp = PowerUp.Highlighted)
        SpecialMessage.Effect -> message.copy(powerUp = PowerUp.Effect)
        SpecialMessage.Reward -> message.copy(rewardId = PREVIEW_REWARD)
        SpecialMessage.WatchStreak -> message.copy(kind = MessageKind.UserNotice, body = MessageBody.EMPTY, text = "", watchStreak = 5)
    }
}

private const val PREVIEW_REWARD = "preview"
/** A turned-off preview stays readable, but clearly not what the chat shows. */
private const val OFF_ALPHA = 0.4f
