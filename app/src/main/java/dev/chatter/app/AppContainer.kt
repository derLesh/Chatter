package dev.chatter.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.chatter.app.auth.AuthRepository
import dev.chatter.app.auth.AuthState
import dev.chatter.app.badges.BadgeRepository
import dev.chatter.app.badges.SupporterTitles
import dev.chatter.app.changelog.ChangelogRepository
import dev.chatter.app.channels.BlockedUsersRepository
import dev.chatter.app.channels.ChannelRepository
import dev.chatter.app.chat.AppChatNotices
import dev.chatter.app.chat.ChatRepository
import dev.chatter.app.chat.ChatterRegistry
import dev.chatter.app.chat.CommandExecutor
import dev.chatter.app.chat.EmoteOptions
import dev.chatter.app.chat.MentionInboxRepository
import dev.chatter.app.chat.MessageBuilder
import dev.chatter.app.chat.NicknameRepository
import dev.chatter.app.chat.RuleRepository
import dev.chatter.app.chat.SendResult
import dev.chatter.app.chat.WhisperInboxRepository
import dev.chatter.app.chat.WhisperResult
import dev.chatter.app.chat.WhisperSender
import dev.chatter.app.crash.CrashLog
import dev.chatter.app.emotes.EmoteRepository
import dev.chatter.app.emotes.SevenTvEventClient
import dev.chatter.app.emotes.SevenTvLiveUpdates
import dev.chatter.app.irc.ConnectionState
import dev.chatter.app.irc.IrcConnection
import dev.chatter.app.net.DataSaving
import dev.chatter.app.net.HelixApi
import dev.chatter.app.net.ServiceTrouble
import dev.chatter.app.net.ThirdPartyApi
import dev.chatter.app.service.BackgroundHealth
import dev.chatter.app.service.ChatNotifier
import dev.chatter.app.settings.BackupManager
import dev.chatter.app.settings.SettingsRepository
import dev.chatter.app.stats.StatsRepository
import dev.chatter.app.update.UpdateRepository
import dev.chatter.app.util.ChannelIcons
import dev.chatter.app.util.ChannelShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Context.authStore by store("auth")
private val Context.channelStore by store("channels")
private val Context.settingsStore by store("settings")
private val Context.nicknameStore by store("nicknames")
private val Context.inboxStore by store("inbox")
private val Context.ruleStore by store("rules")
private val Context.statsStore by store("stats")
private val Context.healthStore by store("background_health")

/**
 * A preference store that starts over empty if its file cannot be parsed. Without the handler every
 * read would throw and the app would crash on each start until all its data was cleared.
 */
private fun store(name: String) = preferencesDataStore(
    name,
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        Log.e("AppContainer", "The $name store could not be read and starts over empty", e)
        emptyPreferences()
    },
)

/**
 * Creates and wires all long-lived objects (manual dependency injection). Lives as long as the
 * process; reach it via `(application as ChatterApp).container`.
 */
class AppContainer(
    private val context: Context,
    /** The last few crashes, for bug reports; see [CrashLog]. */
    val crashLog: CrashLog,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // Emote and badge lists are a few hundred kilobytes and requested on every start and join.
        // All providers send an ETag, so a repeat request is a 304.
        .cache(Cache(context.cacheDir.resolve("http"), HTTP_CACHE_BYTES))
        .build()

    // Same pool, no read timeout: the chat socket may be silent for minutes.
    private val socketHttp: OkHttpClient = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    // Coil has its own disk cache; images in the response cache would be stored twice and push out
    // the lists it is meant for.
    private val imageHttp: OkHttpClient = http.newBuilder().cache(null).build()

    /** Collects unreachable services so the screen can report each once. */
    val trouble = ServiceTrouble()

    val settings = SettingsRepository(context.settingsStore, scope)
    val auth = AuthRepository(context.authStore, http)
    val helix = HelixApi(http) { auth.freshToken() }.also { auth.helix = it }
    val thirdParty = ThirdPartyApi(http)
    val emotes = EmoteRepository(helix, thirdParty, trouble)
    val badges = BadgeRepository(helix, thirdParty, trouble)
    val channels = ChannelRepository(context.channelStore, helix, scope)
    val blocked = BlockedUsersRepository(helix, scope)
    val nicknames = NicknameRepository(context.nicknameStore, scope)
    /** The active account's user id; null for a guest or nobody. */
    private val activeUserId: StateFlow<String?> =
        auth.state.map { (it as? AuthState.LoggedIn)?.account?.userId }.stateIn(scope, SharingStarted.Eagerly, null)
    val inbox = MentionInboxRepository(context.inboxStore, activeUserId, scope)
    val whisperInbox = WhisperInboxRepository(context.inboxStore, activeUserId, scope)
    val rules = RuleRepository(context.ruleStore, scope)
    val stats = StatsRepository(context.statsStore, scope)
    val backgroundHealth = BackgroundHealth(context, context.healthStore, scope)
    val backup = BackupManager(settings, rules, nicknames, channels)
    val changelog = ChangelogRepository(context, settings, BuildConfig.VERSION_NAME, scope)
    /** True while Chatter saves data; see [DataSaving]. */
    val dataSaving = DataSaving(context, settings.settings.map { it.mobileData }, scope)
    val updates = UpdateRepository(http, settings, BuildConfig.VERSION_NAME, BuildConfig.UPDATE_CHECK, dataSaving.active, scope)
    val irc = IrcConnection(socketHttp, scope, ::joinRank)
    val whisperSender = WhisperSender(context, helix, auth)
    private val chatters = ChatterRegistry()

    val chat = ChatRepository(
        context, irc, MessageBuilder(emotes, badges, chatters, ::emoteOptions), emotes, badges, channels, thirdParty, helix, auth,
        CommandExecutor(context, helix, auth, whisperSender), AppChatNotices(context), chatters, blocked, stats,
        rules.rules, settings.settings, scope,
    )

    // One disk cache for both loaders; two caches on one directory would corrupt it.
    private val diskCache by lazy {
        DiskCache.Builder()
            .directory(context.cacheDir.resolve("images"))
            .maxSizeBytes(100L * 1024 * 1024)
            .build()
    }

    private fun emoteOptions() = settings.settings.value.let {
        EmoteOptions(
            enabled = it.emotesEnabled,
            zeroWidth = it.zeroWidthEmotes,
            showUnlisted = it.showUnlisted7tv,
            providers = it.emoteProviders,
        )
    }

    private val sevenTvEvents = SevenTvEventClient(socketHttp, scope)
    private val sevenTvLive = SevenTvLiveUpdates(context, sevenTvEvents, emotes, badges, chat, settings.settings, scope)

    val imageLoader: ImageLoader = imageLoader(animated = true)
    /**
     * For animated emotes turned off: decodes the first frame only. Lazy, since it has its own
     * memory cache and is rarely needed.
     */
    val staticImageLoader: ImageLoader by lazy { imageLoader(animated = false) }

    // Needs the image loader: mention notifications use the channel avatar as icon.
    private val channelIcons = ChannelIcons(context, channels, imageLoader)
    val notifier = ChatNotifier(context, channels, helix, settings.settings, channelIcons, dataSaving.active, activeUserId)
    private val shortcuts = ChannelShortcuts(context, channels.identities, channelIcons, scope)

    private val _powerSaveMode = MutableStateFlow(false)
    /** True while Android's battery saver is on. */
    val powerSaveMode: StateFlow<Boolean> = _powerSaveMode

    fun start() {
        notifier.createChannels()
        chat.start()
        stats.start(chat.windows.anyVisible)
        backgroundHealth.start()
        changelog.start()
        // Read for every message, so mirrored onto the repository.
        scope.launch { settings.settings.collect { badges.enabled = it.badgeProviders } }
        sevenTvLive.start()
        shortcuts.start()
        // One notification channel per Twitch channel, so each can have its own sound.
        scope.launch { channels.identities.collect { notifier.syncChannels(it) } }
        // Every mention goes into the inbox of the account it was addressed to. Guests have no
        // inbox.
        scope.launch {
            chat.allMentions.collect { m -> activeUserId.value?.let { inbox.add(m.item, read = m.seen, account = it) } }
        }
        scope.launch { chat.whispers.collect { w -> activeUserId.value?.let { whisperInbox.add(w, account = it) } } }
        scope.launch {
            channels.loadCache()
            auth.restore()
            // Collected after restore(), so the list is the real one. Inbox and whispers of
            // accounts that are gone (logged out, ended by Twitch, key lost) are dropped; accounts
            // the keystore could not read at start still count.
            auth.knownUserIds.collect { ids ->
                inbox.keepOnly(ids)
                whisperInbox.keepOnly(ids)
            }
        }
        scope.launch {
            var userId: String? = null
            auth.state.collect { state ->
                when (state) {
                    is AuthState.LoggedIn -> {
                        // Also after every token refresh, to hand the new token to the connection.
                        connect()
                        if (state.account.userId != userId) {
                            // An account switch: the loaded block list, mentions and whispers
                            // belong to the previous account.
                            if (userId != null) {
                                blocked.clear()
                                notifier.clearConversations()
                            }
                            userId = state.account.userId
                            chat.resync()
                            launch { emotes.loadGlobal() }
                            launch { badges.retryMissing(supporterTitles()) }
                            launch { emotes.loadTwitchUserEmotes(state.account.userId) }
                            launch { channels.refreshUsers(channels.currentChannels()) }
                            launch { blocked.load(state.account.userId) }
                        }
                    }
                    AuthState.Guest -> {
                        // Only reachable from the login screen, so there is no account state to
                        // undo.
                        userId = null
                        connect()
                        chat.resync()
                        launch { emotes.loadGlobal() }
                        launch { badges.retryMissing(supporterTitles()) }
                    }
                    AuthState.LoggedOut -> {
                        userId = null
                        irc.disconnect()
                        chat.reset()
                        emotes.clear()
                        blocked.clear()
                        notifier.clearConversations()
                    }
                    AuthState.Loading -> Unit
                }
            }
        }
        // Renews the access token shortly before it expires.
        scope.launch {
            auth.state.collectLatest { state ->
                if (state is AuthState.LoggedIn && state.account.refreshToken != null) {
                    delay((state.account.expiresAt - System.currentTimeMillis() - 10 * 60_000L).coerceAtLeast(0))
                    auth.refresh(state.account)
                }
            }
        }
        scope.launch {
            irc.state.collect { state ->
                if (state == ConnectionState.AuthFailed) {
                    // Usually an expired or revoked token: refresh and reconnect. Without a refresh
                    // token (WebView login), or if Twitch rejects it, refresh() logs out.
                    val acc = auth.account
                    if (acc != null && auth.refresh(acc)) {
                        auth.account?.let { irc.connect(it.login, it.token) }
                        delay(5_000)
                        if (irc.state.value == ConnectionState.AuthFailed) auth.logout(expired = true)
                    }
                    // The user did not ask for this logout; without a notification the app would
                    // just go quiet.
                    if (auth.account == null) notifier.notifyNotListening(R.string.notif_not_listening_login)
                }
            }
        }
        // Lists that failed at start are retried when the app comes back; without the global set
        // there are no badges at all. Loaded data is left alone.
        scope.launch {
            chat.windows.anyVisible.collect { visible ->
                // Someone looking at "Connecting…" should not wait out a long backoff.
                if (visible) irc.retryNow()
                // The global set comes from Helix, which needs a login.
                if (visible && auth.account != null) {
                    badges.retryMissing(supporterTitles())
                    emotes.retryMissing(auth.account?.userId)
                }
            }
        }
        // Emotes are retried in the background with a growing interval, since a provider that is
        // down usually returns within the hour. Stops as soon as nothing is missing.
        scope.launch {
            emotes.waitingForProvider.collectLatest { waiting ->
                if (!waiting) return@collectLatest
                var wait = EMOTE_RETRY_FIRST_MS
                while (true) {
                    delay(wait)
                    emotes.retryMissing(auth.account?.userId)
                    wait = (wait * 2).coerceAtMost(EMOTE_RETRY_MAX_MS)
                }
            }
        }
        registerNetworkCallback()
        watchPowerSaveMode()
    }

    /** Null while sponsoring is off; see `sponsoring` in build.gradle.kts. */
    private fun supporterTitles() = if (!BuildConfig.SPONSORING) null else SupporterTitles(
        once = context.getString(R.string.badge_supporter),
        monthly = context.getString(R.string.badge_supporter_monthly),
        monthlyFor = { months ->
            context.resources.getQuantityString(R.plurals.badge_supporter_months, months, months)
        },
    )

    /**
     * JOIN priority when there are more channels than Twitch takes at once: the one on screen (or
     * the last read before a window reported), then notifying ones, then the rest. Only called once
     * connected, when [chat] exists.
     */
    private fun joinRank(channel: String): Int = when {
        chat.windows.isWatching(channel) || channel == channels.lastChannel.value -> 0
        channel !in channels.mutedChannels.value -> 1
        else -> 2
    }

    /** Opens the chat connection for an account or a guest. Safe to call repeatedly. */
    fun connect() {
        when (val state = auth.state.value) {
            is AuthState.LoggedIn -> irc.connect(state.account.login, state.account.token)
            AuthState.Guest -> irc.connectAnonymously()
            AuthState.LoggedOut, AuthState.Loading -> Unit
        }
    }

    /**
     * Sends a reply typed into a notification. The broadcast may have started the process, so this
     * waits a moment for login and connection instead of failing right away.
     */
    suspend fun sendFromNotification(account: String?, channel: String, text: String): Boolean {
        val loggedIn = withTimeoutOrNull(NOTIFICATION_SEND_TIMEOUT_MS) { auth.state.first { it is AuthState.LoggedIn } } != null
        // Only as the account the notification was for.
        if (!loggedIn || auth.account?.userId != account) return false
        val ready = withTimeoutOrNull(NOTIFICATION_SEND_TIMEOUT_MS) {
            connect()
            chat.rooms.ready.first { channel in it }
        } != null
        return ready && auth.account?.userId == account && chat.send(channel, text, replyTo = null) == SendResult.Ok
    }

    /**
     * Sends a whisper typed into a notification, waiting for the stored login like
     * [sendFromNotification].
     */
    suspend fun whisperFromNotification(account: String?, login: String, userId: String?, text: String): WhisperResult {
        val ready = withTimeoutOrNull(NOTIFICATION_SEND_TIMEOUT_MS) {
            auth.state.first { it is AuthState.LoggedIn }
        } != null
        if (!ready) return WhisperResult(sent = false, message = context.getString(R.string.error_not_connected))
        // A whisper sent as another account would reveal that account to a stranger.
        if (auth.account?.userId != account) {
            return WhisperResult(sent = false, message = context.getString(R.string.notif_reply_other_account))
        }
        return whisperSender.send(login, userId, text)
    }

    fun disconnect() = irc.disconnect()

    /**
     * Android's battery saver. While it is on, emotes are drawn as stills regardless of the
     * setting.
     */
    private fun watchPowerSaveMode() {
        val power = context.getSystemService(PowerManager::class.java)
        _powerSaveMode.value = power.isPowerSaveMode
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    _powerSaveMode.value = power.isPowerSaveMode
                }
            },
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
        )
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        /** Tells the chat whether there is a network and whether it reaches the internet. */
        fun tellChat(network: Network?) {
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            irc.setNetwork(up = network != null, validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            sevenTvEvents.setNetwork(up = network != null)
        }
        tellChat(cm.activeNetwork)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = tellChat(network)
            // A switch from Wi-Fi to mobile can report the loss after the new network, so the
            // current state is read instead of trusting the callback order.
            override fun onLost(network: Network) {
                tellChat(cm.activeNetwork)
                dataSaving.networkChanged()
            }
            // Validation, metering or hotspot state changed.
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                irc.setNetwork(up = true, validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                sevenTvEvents.setNetwork(up = true)
                dataSaving.networkChanged()
            }
        })
    }

    private companion object {
        /** First retry delay for an unreachable provider, and the ceiling it doubles up to. */
        const val EMOTE_RETRY_FIRST_MS = 30_000L
        const val EMOTE_RETRY_MAX_MS = 5 * 60_000L

        /** How long a notification reply waits for login and join. */
        const val NOTIFICATION_SEND_TIMEOUT_MS = 15_000L

        /** Enough for the emote and badge lists of many channels. */
        const val HTTP_CACHE_BYTES = 20L * 1024 * 1024
    }

    private fun imageLoader(animated: Boolean) = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { imageHttp }))
            if (animated) add(AnimatedImageDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, if (animated) 0.15 else 0.05).build() }
        .diskCache { diskCache }
        .crossfade(false)
        .build()
}
