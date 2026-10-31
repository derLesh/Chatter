package dev.chatter.app.util

import android.content.Context
import dev.chatter.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.time.Duration.Companion.hours

/** A new launcher icon; [autoWindow] when a season set it by itself, so it does so only once. */
data class IconChange(val icon: AppIcon, val autoWindow: String? = null)

/**
 * What the launcher icon becomes on [today], or null when it stays. A seasonal icon gives way to
 * [own] once its days are over; with [auto] each season's icon is set once, on its first day the
 * app runs, so picking another one during the season sticks. [lastAutoWindow] is the
 * [IconChange.autoWindow] set last. A season never replaces the Nightly icon by itself, since that
 * icon is how Nightly is told apart from Chatter.
 */
fun iconChangeOn(today: LocalDate, current: AppIcon, own: AppIcon, auto: Boolean, lastAutoWindow: String?): IconChange? {
    if (auto && own != AppIcon.Nightly) {
        val due = AppIcon.entries.firstOrNull { it.season?.isOn(today) == true }
        val window = due?.season?.let { "${it.name} ${it.nextStart(today)}" }
        if (due != null && window != lastAutoWindow) return IconChange(due, window)
    }
    return if (current.season?.isOn(today) == false) IconChange(own) else null
}

/**
 * Sets the launcher icon: by hand from the picker, and by itself on special days while the user
 * lets it. Checked at start, every hour while the app runs, and when the switch changes.
 */
class AppIconSwitcher(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _current = MutableStateFlow(AppIcon.current(context))
    /** The icon the launcher shows now. */
    val current: StateFlow<AppIcon> = _current

    fun start() {
        scope.launch {
            var wasAuto: Boolean? = null
            settings.settings.map { it.seasonalAppIcons }.distinctUntilChanged().collectLatest { auto ->
                if (wasAuto == true && !auto) giveSeasonWay()
                wasAuto = auto
                while (true) {
                    update(auto)
                    // Days change at midnight; an hour late is soon enough for an icon.
                    delay(1.hours)
                }
            }
        }
    }

    /** The user's own choice; a non-seasonal one is what a season gives way to again. */
    suspend fun pick(icon: AppIcon) {
        if (icon.season == null) settings.setOwnAppIcon(icon.name)
        set(icon)
    }

    private fun set(icon: AppIcon) {
        AppIcon.set(context, icon)
        _current.value = icon
    }

    private suspend fun update(auto: Boolean) {
        val current = AppIcon.current(context)
        // Picked before there was anything to give way to.
        if (current.season == null && settings.ownAppIcon() == null) settings.setOwnAppIcon(current.name)
        val change = iconChangeOn(LocalDate.now(), current, own(current), auto, settings.lastAutoAppIcon()) ?: return
        if (change.icon != current) set(change.icon)
        change.autoWindow?.let { settings.setLastAutoAppIcon(it) }
    }

    /** Turning the switch off takes a seasonal icon away again. */
    private suspend fun giveSeasonWay() {
        val current = AppIcon.current(context)
        if (current.season != null) set(own(current))
    }

    private suspend fun own(current: AppIcon): AppIcon =
        settings.ownAppIcon()?.let { name -> AppIcon.entries.firstOrNull { it.name == name && it.season == null && it.listed } }
            ?: current.takeIf { it.season == null }
            ?: AppIcon.default
}
