package dev.chatter.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
import dev.chatter.app.service.MentionNotifier
import dev.chatter.app.settings.BackupManager
import dev.chatter.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Context.authStore by preferencesDataStore("auth")
private val Context.channelStore by preferencesDataStore("channels")
private val Context.settingsStore by preferencesDataStore("settings")
private val Context.nicknameStore by preferencesDataStore("nicknames")
private val Context.inboxStore by preferencesDataStore("inbox")
private val Context.ruleStore by preferencesDataStore("rules")

/**
 * Creates and wires every long-lived object of the app (manual dependency injection).
 * Lives as long as the process; get it via `(application as ChatterApp).container`.
 */
class AppContainer(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // Same connection pool, but no read timeout: the chat socket may be silent for minutes.
    private val socketHttp: OkHttpClient = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

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
    val rules = RuleRepository(context.ruleStore, scope)
    val backup = BackupManager(settings, rules, nicknames, channels)
    val changelog = ChangelogRepository(context, settings, BuildConfig.VERSION_NAME, scope)
    val irc = IrcConnection(socketHttp, scope)
    private val chatters = ChatterRegistry()

    val chat = ChatRepository(
        context, irc, MessageBuilder(emotes, badges, chatters, ::emoteOptions), emotes, badges, channels, thirdParty, helix, auth,
        CommandExecutor(context, helix, auth), chatters, blocked, rules.rules, settings.settings, scope,
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
    /** Used when animated emotes are turned off: decodes only the first frame. */
    val staticImageLoader: ImageLoader = imageLoader(animated = false)

    // After the image loader: mention notifications carry the channel avatar as their icon.
    val notifier = MentionNotifier(context, channels, helix, settings.settings, imageLoader)

    fun start() {
        notifier.createChannels()
        chat.start()
        changelog.start()
        // Read on every message, so it is mirrored onto the repository instead of passed around.
        scope.launch { settings.settings.collect { badges.enabled = it.badgeProviders } }
        sevenTvLive.start()
        // Every mention lands in the inbox, whether or not it was worth a notification.
        scope.launch { chat.allMentions.collect { inbox.add(it.item, read = it.seen) } }
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

    fun disconnect() = irc.disconnect()

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = irc.onNetworkAvailable()
        })
    }

    private companion object {
        /** How long a notification reply waits for login and join before giving up. */
        const val NOTIFICATION_SEND_TIMEOUT_MS = 15_000L
    }

    private fun imageLoader(animated: Boolean) = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { http }))
            if (animated) add(AnimatedImageDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, if (animated) 0.15 else 0.05).build() }
        .diskCache { diskCache }
        .crossfade(false)
        .build()
}
