package dev.chatter.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
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
import dev.chatter.app.changelog.ChangelogRepository
import dev.chatter.app.channels.BlockedUsersRepository
import dev.chatter.app.channels.ChannelRepository
import dev.chatter.app.chat.ChatRepository
import dev.chatter.app.chat.SendResult
import dev.chatter.app.chat.ChatterRegistry
import dev.chatter.app.chat.MentionInboxRepository
import dev.chatter.app.chat.NicknameRepository
import dev.chatter.app.chat.RuleRepository
import dev.chatter.app.chat.WhisperInboxRepository
import dev.chatter.app.chat.WhisperResult
import dev.chatter.app.chat.WhisperSender
import dev.chatter.app.chat.CommandExecutor
import dev.chatter.app.chat.EmoteOptions
import dev.chatter.app.chat.MessageBuilder
import dev.chatter.app.emotes.EmoteRepository
import dev.chatter.app.emotes.SevenTvEventClient
import dev.chatter.app.emotes.SevenTvLiveUpdates
import dev.chatter.app.irc.ConnectionState
import dev.chatter.app.irc.IrcConnection
import dev.chatter.app.net.HelixApi
import dev.chatter.app.net.ThirdPartyApi
import dev.chatter.app.service.ChatNotifier
import dev.chatter.app.settings.BackupManager
import dev.chatter.app.settings.SettingsRepository
import dev.chatter.app.stats.StatsRepository
import dev.chatter.app.util.ChannelIcons
import dev.chatter.app.util.ChannelShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Context.authStore by preferencesDataStore("auth")
private val Context.channelStore by preferencesDataStore("channels")
private val Context.settingsStore by preferencesDataStore("settings")
private val Context.nicknameStore by preferencesDataStore("nicknames")
private val Context.inboxStore by preferencesDataStore("inbox")
private val Context.ruleStore by preferencesDataStore("rules")
private val Context.statsStore by preferencesDataStore("stats")

/**
 * Creates and wires every long-lived object of the app (manual dependency injection).
 * Lives as long as the process; get it via `(application as ChatterApp).container`.
 */
class AppContainer(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // The emote and badge lists of a channel are a few hundred kilobytes each and are asked
        // for again on every start and every join. All three providers answer with an ETag, so
        // the second ask costs a 304 and no download at all.
        .cache(Cache(context.cacheDir.resolve("http"), HTTP_CACHE_BYTES))
        .build()

    // Same connection pool, but no read timeout: the chat socket may be silent for minutes.
    private val socketHttp: OkHttpClient = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    // Coil keeps a disk cache of its own, so the response cache above would hold every emote a
    // second time — and a few busy channels of images would crowd out the very lists it is for.
    private val imageHttp: OkHttpClient = http.newBuilder().cache(null).build()

    val settings = SettingsRepository(context.settingsStore, scope)
    val auth = AuthRepository(context.authStore, http)
    val helix = HelixApi(http) { auth.freshToken() }.also { auth.helix = it }
    val thirdParty = ThirdPartyApi(http)
    val emotes = EmoteRepository(helix, thirdParty)
    val badges = BadgeRepository(helix, thirdParty)
    val channels = ChannelRepository(context.channelStore, helix, scope)
    val blocked = BlockedUsersRepository(helix, scope)
    val nicknames = NicknameRepository(context.nicknameStore, scope)
    val inbox = MentionInboxRepository(context.inboxStore, scope)
    val whisperInbox = WhisperInboxRepository(context.inboxStore, scope)
    val rules = RuleRepository(context.ruleStore, scope)
    val stats = StatsRepository(context.statsStore, scope)
    val backup = BackupManager(settings, rules, nicknames, channels)
    val changelog = ChangelogRepository(context, settings, BuildConfig.VERSION_NAME, scope)
    val irc = IrcConnection(socketHttp, scope)
    val whisperSender = WhisperSender(context, helix, auth)
    private val chatters = ChatterRegistry()

    val chat = ChatRepository(
        context, irc, MessageBuilder(emotes, badges, chatters, ::emoteOptions), emotes, badges, channels, thirdParty, helix, auth,
        CommandExecutor(context, helix, auth, whisperSender), chatters, blocked, stats, rules.rules, settings.settings, scope,
    )

    // One disk cache shared by both loaders (two caches on the same directory would corrupt it).
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

    private val sevenTvLive = SevenTvLiveUpdates(context, SevenTvEventClient(socketHttp, scope), emotes, chat, settings.settings, scope)

    val imageLoader: ImageLoader = imageLoader(animated = true)
    /**
     * Used when animated emotes are turned off: decodes only the first frame. Built on demand,
     * because it carries a memory cache of its own and most people never turn them off.
     */
    val staticImageLoader: ImageLoader by lazy { imageLoader(animated = false) }

    // After the image loader: mention notifications carry the channel avatar as their icon.
    private val channelIcons = ChannelIcons(context, channels, imageLoader)
    val notifier = ChatNotifier(context, channels, helix, settings.settings, channelIcons)
    private val shortcuts = ChannelShortcuts(context, channels.identities, channelIcons, scope)

    private val _powerSaveMode = MutableStateFlow(false)
    /** True while Android's battery saver is on. */
    val powerSaveMode: StateFlow<Boolean> = _powerSaveMode

    fun start() {
        notifier.createChannels()
        chat.start()
        stats.start()
        changelog.start()
        // Read on every message, so it is mirrored onto the repository instead of passed around.
        scope.launch { settings.settings.collect { badges.enabled = it.badgeProviders } }
        sevenTvLive.start()
        shortcuts.start()
        // One notification channel per Twitch channel, so each can be given its own sound.
        scope.launch { channels.identities.collect { notifier.syncChannels(it) } }
        // Every mention lands in the inbox, whether or not it was worth a notification.
        scope.launch { chat.allMentions.collect { inbox.add(it.item, read = it.seen) } }
        scope.launch { chat.whispers.collect { whisperInbox.add(it) } }
        scope.launch {
            channels.loadCache()
            auth.restore()
        }
        scope.launch {
            var userId: String? = null
            auth.state.collect { state ->
                when (state) {
                    is AuthState.LoggedIn -> {
                        // Also runs after every token refresh: hands the new token to the connection.
                        connect()
                        if (state.account.userId != userId) {
                            userId = state.account.userId
                            chat.resync()
                            launch { emotes.loadGlobal() }
                            launch { badges.loadGlobal() }
                            launch { badges.loadThirdParty(context.getString(R.string.badge_supporter)) }
                            launch { emotes.loadTwitchUserEmotes(state.account.userId) }
                            launch { channels.refreshUsers(channels.currentChannels()) }
                            launch { blocked.load(state.account.userId) }
                        }
                    }
                    AuthState.LoggedOut -> {
                        userId = null
                        irc.disconnect()
                        chat.reset()
                        emotes.clear()
                        blocked.clear()
                    }
                    AuthState.Loading -> Unit
                }
            }
        }
        // Renew the access token shortly before it expires.
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
                    // Most likely an expired or revoked token: renew it and reconnect. Without a
                    // refresh token (WebView login) or if Twitch rejects it, refresh() logs out.
                    val acc = auth.account
                    if (acc != null && auth.refresh(acc)) {
                        auth.account?.let { irc.connect(it.login, it.token) }
                        delay(5_000)
                        if (irc.state.value == ConnectionState.AuthFailed) auth.logout()
                    }
                }
            }
        }
        registerNetworkCallback()
        watchPowerSaveMode()
    }

    /** Opens the chat connection if a user is logged in. Safe to call repeatedly. */
    fun connect() {
        auth.account?.let { irc.connect(it.login, it.token) }
    }

    /**
     * Sends a reply typed into a notification. The broadcast that brings it may have started the
     * process, in which case the token is still being restored and nothing is connected yet — so
     * this waits for the connection for a moment rather than reporting failure right away.
     */
    suspend fun sendFromNotification(channel: String, text: String): Boolean {
        val ready = withTimeoutOrNull(NOTIFICATION_SEND_TIMEOUT_MS) {
            auth.state.first { it is AuthState.LoggedIn }
            connect()
            chat.readyChannels.first { channel in it }
        } != null
        return ready && chat.send(channel, text, replyTo = null) == SendResult.Ok
    }

    /**
     * Sends a whisper typed into a notification. Like a channel reply, the broadcast may be what
     * started the process, so this waits for the stored login to come back before giving up.
     */
    suspend fun whisperFromNotification(login: String, userId: String?, text: String): WhisperResult {
        val ready = withTimeoutOrNull(NOTIFICATION_SEND_TIMEOUT_MS) {
            auth.state.first { it is AuthState.LoggedIn }
        } != null
        if (!ready) return WhisperResult(sent = false, message = context.getString(R.string.error_not_connected))
        return whisperSender.send(login, userId, text)
    }

    fun disconnect() = irc.disconnect()

    /**
     * Android's battery saver. Someone who turned it on has asked the whole phone to do less, so
     * the chat draws its emotes as stills for as long as it lasts, whatever the setting says.
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
        irc.setNetworkAvailable(cm.activeNetwork != null)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = irc.setNetworkAvailable(true)
            // A switch from Wi-Fi to mobile can report the loss after the arrival, so this asks
            // what is there now rather than trusting the order the two callbacks come in.
            override fun onLost(network: Network) = irc.setNetworkAvailable(cm.activeNetwork != null)
        })
    }

    private companion object {
        /** How long a notification reply waits for login and join before giving up. */
        const val NOTIFICATION_SEND_TIMEOUT_MS = 15_000L

        /** Room for the emote and badge lists of a good number of channels, and little else. */
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
