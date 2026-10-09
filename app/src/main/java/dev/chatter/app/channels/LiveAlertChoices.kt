package dev.chatter.app.channels

/**
 * Which channels notify when they go live. The channels in the list do unless turned [off]; other
 * followed channels only when turned [on].
 */
data class LiveAlertChoices(val on: Set<String> = emptySet(), val off: Set<String> = emptySet()) {
    fun wanted(login: String, inList: Boolean): Boolean = if (inList) login !in off else login in on

    /** The choices after turning [login]'s notification [enabled]. */
    fun with(login: String, inList: Boolean, enabled: Boolean): LiveAlertChoices =
        if (inList) copy(off = if (enabled) off - login else off + login)
        else copy(on = if (enabled) on + login else on - login)
}
