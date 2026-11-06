package me.lesh.chatter.ui.chat

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import me.lesh.chatter.R
import me.lesh.chatter.chat.ScheduledMessage
import me.lesh.chatter.chat.ScheduledMessages
import me.lesh.chatter.settings.SendLaterButton
import me.lesh.chatter.ui.FormSheet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Sending later (Labs). [button] is how a message is planned, null while the feature is off;
 * [waiting] are the planned messages of the channel the input sends to, shown above it either way,
 * so one planned before turning it off can still be dropped.
 */
class SendLater(
    val button: SendLaterButton?,
    val waiting: List<ScheduledMessage>,
    val onPlan: (sendAt: Long) -> Unit,
    val onTakeBack: (ScheduledMessage) -> Unit,
    val onDrop: (ScheduledMessage) -> Unit,
)

/**
 * Picks when [text] goes out: a tap on "in 5 min" plans it at once, a time of day takes the
 * button at the bottom. The time counts from the tap, not from when the sheet is gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SendLaterSheet(text: String, onPlan: (sendAt: Long) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    // Moves on with each minute, so "Today at 10:00" turns into "Tomorrow" before the button plans that.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - now % 60_000)
            now = System.currentTimeMillis()
        }
    }
    // The next full hour: a round time is what people plan for most.
    val start = remember { Instant.ofEpochMilli(now).atZone(zone).plusHours(1) }
    val time = rememberTimePickerState(start.hour, 0, is24Hour = DateFormat.is24HourFormat(context))
    val sendAt = ScheduledMessages.nextAt(time.hour, time.minute, now, zone)
    val at = Instant.ofEpochMilli(sendAt).atZone(zone)
    val clock = clockFormat(context, "hm").format(at)
    FormSheet(
        title = stringResource(R.string.send_later),
        confirmLabel = stringResource(R.string.send_later_confirm, clock),
        onConfirm = { onPlan(sendAt) },
        onDismiss = onDismiss,
        text = text,
    ) { finish ->
        item(key = "soon") {
            SheetHeading(stringResource(R.string.send_later_soon))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .bleed(SHEET_PADDING)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = SHEET_PADDING),
            ) {
                SOON_MINUTES.forEach { minutes ->
                    AssistChip(
                        onClick = {
                            val at = System.currentTimeMillis() + minutes * 60_000L
                            finish { onPlan(at) }
                        },
                        label = {
                            Text(
                                if (minutes < 60) stringResource(R.string.send_later_in_minutes, minutes)
                                else stringResource(R.string.send_later_in_hours, minutes / 60),
                            )
                        },
                    )
                }
            }
        }
        item(key = "at") {
            SheetHeading(stringResource(R.string.send_later_at))
            // Centred like every other Material time picker.
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                TimeInput(state = time)
                val today = at.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                Text(
                    stringResource(if (today) R.string.send_later_today else R.string.send_later_tomorrow, clock),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 20.dp, bottom = 12.dp).semantics { heading() },
    )
}

/**
 * One planned message above the input: when it goes out, or that it did not. A tap takes it back
 * into the input, or, while something else is typed there, says so in its place for a moment:
 * the snackbar would be under the keyboard.
 */
@Composable
internal fun ScheduledBar(message: ScheduledMessage, canTakeBack: Boolean, onTakeBack: () -> Unit, onDrop: () -> Unit) {
    var explaining by remember { mutableStateOf(false) }
    if (explaining) {
        LaunchedEffect(Unit) {
            delay(2_500)
            explaining = false
        }
    }
    val blocked = stringResource(R.string.scheduled_edit_blocked)
    val label = when {
        explaining && !canTakeBack -> blocked
        message.failed -> stringResource(R.string.scheduled_failed, message.text)
        else -> stringResource(R.string.scheduled_at, sendTime(message.sendAt), message.text)
    }
    val content = if (message.failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (message.failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = stringResource(R.string.scheduled_edit)) { if (canTakeBack) onTakeBack() else explaining = true }
            .semantics {
                if (!canTakeBack) stateDescription = blocked
                // TalkBack reads the reason out where it appears, as the eye finds it there.
                if (explaining) liveRegion = LiveRegionMode.Polite
            }
            .padding(start = 12.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_schedule_send),
            contentDescription = null,
            tint = if (message.failed) content else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDrop) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.scheduled_drop), tint = content)
        }
    }
}

/** The opening of the planning sheet, next to Send. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SendLaterIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
        Icon(painterResource(R.drawable.ic_schedule_send), contentDescription = stringResource(R.string.send_later))
    }
}

/**
 * Runs [onHold] when the finger stays down, and keeps that press from also sending: the rest of it
 * is consumed before the button underneath sees it. Works on a disabled button too, so a message
 * can be planned during slow mode's countdown. TalkBack offers no long press on a disabled
 * button, so it gets a custom action instead, which works in both states.
 */
@Composable
internal fun Modifier.holdToSendLater(enabled: Boolean, onHold: () -> Unit): Modifier {
    if (!enabled) return this
    val hold by rememberUpdatedState(onHold)
    val haptics = LocalHapticFeedback.current
    val label = stringResource(R.string.send_later)
    return semantics { customActions = listOf(CustomAccessibilityAction(label) { hold(); true }) }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var letGo = false
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    waitForUpOrCancellation(PointerEventPass.Initial)
                    letGo = true
                }
                if (letGo) return@awaitEachGesture
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                hold()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
}

/** The time alone today, with the date on any other day; to the second, as it goes out to the millisecond. */
@Composable
private fun sendTime(sendAt: Long): String {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(sendAt).atZone(zone)
    return clockFormat(context, if (at.toLocalDate() == LocalDate.now(zone)) "hms" else "dMMMhms").format(at)
}

/** [skeleton] on the 12 or 24 hour clock the phone is set to, like the time picker; its "h" is the hour. */
private fun clockFormat(context: Context, skeleton: String): DateTimeFormatter {
    val hour = if (DateFormat.is24HourFormat(context)) "H" else "h"
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton.replace("h", hour)))
}

private val SOON_MINUTES = listOf(1, 5, 10, 30, 60)

/** FormSheet's side padding. */
private val SHEET_PADDING = 20.dp

/** Spreads into [padding] on both sides, so a row that scrolls runs to the screen's edge instead of being cut short. */
private fun Modifier.bleed(padding: Dp) = layout { measurable, constraints ->
    val extra = padding.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}
