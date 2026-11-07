package me.lesh.chatter.debug

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import me.lesh.chatter.BuildConfig
import me.lesh.chatter.R
import me.lesh.chatter.auth.AuthRepository
import me.lesh.chatter.auth.AuthState
import me.lesh.chatter.channels.ChannelRepository
import me.lesh.chatter.chat.ChatRepository
import me.lesh.chatter.crash.CrashLog
import me.lesh.chatter.emotes.EmoteRepository
import me.lesh.chatter.irc.IrcConnection
import me.lesh.chatter.service.BatteryRestrictions
import me.lesh.chatter.storage.StorageUsage
import okhttp3.OkHttpClient
import java.io.File
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import me.lesh.chatter.util.shownVersion

class DebugRow(@param:StringRes val label: Int, val value: String)

class DebugSection(@param:StringRes val title: Int, val rows: List<DebugRow>)

/**
 * Everything the developer page shows about the running app: build, device, memory, the chat and
 * the caches. Values come formatted, since they are only ever read on screen or pasted.
 */
class DebugStats(
    private val context: Context,
    private val irc: IrcConnection,
    private val chat: ChatRepository,
    private val channels: ChannelRepository,
    private val emotes: EmoteRepository,
    private val auth: AuthRepository,
    private val storage: StorageUsage,
    private val http: OkHttpClient,
    private val images: ImageLoader,
    /** Null until animated emotes were first turned off; asking would create it. */
    private val staticImages: () -> ImageLoader?,
    private val crashLog: CrashLog,
    private val powerSaveMode: StateFlow<Boolean>,
    private val dataSaving: StateFlow<Boolean>,
    private val batteryRestrictions: StateFlow<BatteryRestrictions>,
) {
    private val activityManager = context.getSystemService(ActivityManager::class.java)

    suspend fun snapshot(): List<DebugSection> = withContext(Dispatchers.IO) {
        listOf(build(), device(), memory(), process(), chat(), caches(), crashes())
    }

    suspend fun collectGarbage() = withContext(Dispatchers.Default) { Runtime.getRuntime().gc() }

    private fun build(): DebugSection {
        val app = context.applicationInfo
        val pm = context.packageManager
        val pkg = pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        val installer = runCatching { pm.getInstallSourceInfo(context.packageName).installingPackageName }.getOrNull()
        val flags = listOf(
            "DEV_BUILD" to BuildConfig.DEV_BUILD,
            "NIGHTLY" to BuildConfig.NIGHTLY,
            "PROFILING" to BuildConfig.PROFILING,
            "UPDATE_CHECK" to BuildConfig.UPDATE_CHECK,
            "SPONSOR_LINK" to BuildConfig.SPONSOR_LINK,
            "ASSISTANT_ACCESS" to BuildConfig.ASSISTANT_ACCESS,
        ).filter { it.second }.joinToString { it.first }
        return DebugSection(
            R.string.debug_section_build,
            listOf(
                DebugRow(R.string.debug_version, shownVersion),
                DebugRow(R.string.debug_version_code, BuildConfig.VERSION_CODE.toString()),
                DebugRow(R.string.debug_build_type, BuildConfig.BUILD_TYPE),
                DebugRow(R.string.debug_app_id, context.packageName),
                DebugRow(R.string.debug_debuggable, yesNo(app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)),
                DebugRow(R.string.debug_flags, flags.ifEmpty { NONE }),
                DebugRow(R.string.debug_client_id, yesNo(BuildConfig.TWITCH_CLIENT_ID.isNotBlank())),
                DebugRow(R.string.debug_sdk, "${app.targetSdkVersion} / ${app.minSdkVersion}"),
                DebugRow(R.string.debug_installed, dateTime(pkg.firstInstallTime)),
                DebugRow(R.string.debug_updated, dateTime(pkg.lastUpdateTime)),
                DebugRow(R.string.debug_installer, installer ?: NONE),
            ),
        )
    }

    private fun device(): DebugSection {
        val metrics = context.resources.displayMetrics
        val ram = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        return DebugSection(
            R.string.debug_section_device,
            listOf(
                DebugRow(R.string.debug_model, "${Build.MANUFACTURER} ${Build.MODEL}"),
                DebugRow(R.string.debug_android, "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
                DebugRow(R.string.debug_security_patch, Build.VERSION.SECURITY_PATCH),
                DebugRow(R.string.debug_abis, Build.SUPPORTED_ABIS.joinToString()),
                DebugRow(R.string.debug_cpu_cores, Runtime.getRuntime().availableProcessors().toString()),
                DebugRow(R.string.debug_screen, "${metrics.widthPixels} × ${metrics.heightPixels} px, ${metrics.densityDpi} dpi"),
                DebugRow(R.string.debug_language, Locale.getDefault().toLanguageTag()),
                DebugRow(R.string.debug_free_ram, of(bytes(ram.availMem), bytes(ram.totalMem))),
                DebugRow(R.string.debug_low_memory, yesNo(ram.lowMemory)),
            ),
        )
    }

    private fun memory(): DebugSection {
        val runtime = Runtime.getRuntime()
        // Slow next to the rest (tens of milliseconds), which is why the snapshot is taken off the main thread.
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val status = processStatus()
        return DebugSection(
            R.string.debug_section_memory,
            listOf(
                DebugRow(R.string.debug_java_heap, of(bytes(runtime.totalMemory() - runtime.freeMemory()), bytes(runtime.maxMemory()))),
                DebugRow(R.string.debug_native_heap, of(bytes(Debug.getNativeHeapAllocatedSize()), bytes(Debug.getNativeHeapSize()))),
                DebugRow(R.string.debug_pss, kilobytes(info.totalPss.toLong())),
                DebugRow(R.string.debug_rss, status["VmRSS"]?.let(::kilobytesField) ?: NONE),
                DebugRow(R.string.debug_graphics, info.getMemoryStat("summary.graphics")?.let(::kilobytesField) ?: NONE),
                DebugRow(R.string.debug_code, info.getMemoryStat("summary.code")?.let(::kilobytesField) ?: NONE),
                DebugRow(
                    R.string.debug_heap_limit,
                    context.getString(R.string.debug_heap_limit_value, activityManager.memoryClass, activityManager.largeMemoryClass),
                ),
                DebugRow(
                    R.string.debug_gc,
                    context.getString(
                        R.string.debug_gc_value,
                        Debug.getRuntimeStat("art.gc.gc-count") ?: NONE,
                        Debug.getRuntimeStat("art.gc.gc-time") ?: NONE,
                    ),
                ),
            ),
        )
    }

    private fun process(): DebugSection {
        val upSeconds = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000
        val battery = batteryRestrictions.value
        return DebugSection(
            R.string.debug_section_process,
            listOf(
                DebugRow(R.string.debug_pid, Process.myPid().toString()),
                DebugRow(R.string.debug_uptime, DateUtils.formatElapsedTime(upSeconds)),
                DebugRow(R.string.debug_threads, processStatus()["Threads"] ?: NONE),
                DebugRow(R.string.debug_chat_visible, yesNo(chat.windows.anyVisible.value)),
                DebugRow(R.string.debug_power_saver, yesNo(powerSaveMode.value)),
                DebugRow(R.string.debug_data_saving, yesNo(dataSaving.value)),
                DebugRow(
                    R.string.debug_battery,
                    context.getString(
                        when {
                            battery.restricted -> R.string.debug_battery_restricted
                            battery.optimized -> R.string.debug_battery_optimized
                            else -> R.string.debug_battery_unrestricted
                        },
                    ),
                ),
            ),
        )
    }

    private suspend fun chat(): DebugSection {
        val account = when (val state = auth.state.value) {
            is AuthState.LoggedIn -> state.account.login
            AuthState.Guest -> context.getString(R.string.debug_guest)
            AuthState.LoggedOut, AuthState.Loading -> NONE
        }
        return DebugSection(
            R.string.debug_section_chat,
            listOf(
                DebugRow(R.string.debug_connection, irc.state.value.name),
                DebugRow(R.string.debug_account, account),
                DebugRow(
                    R.string.debug_channels,
                    of(chat.rooms.ready.value.size.toString(), channels.currentChannels().size.toString()),
                ),
                DebugRow(R.string.debug_messages, number(chat.messagesInMemory().toLong())),
                DebugRow(R.string.debug_emotes, number(emotes.loadedCount().toLong())),
                DebugRow(R.string.debug_emotes_waiting, yesNo(emotes.waitingForProvider.value)),
            ),
        )
    }

    private suspend fun caches(): DebugSection {
        val report = storage.measure()
        val lists = http.cache
        val stills = staticImages()?.memoryCache
        return DebugSection(
            R.string.debug_section_caches,
            listOfNotNull(
                images.memoryCache?.let { DebugRow(R.string.debug_image_memory, of(bytes(it.size), bytes(it.maxSize))) },
                DebugRow(
                    R.string.debug_still_memory,
                    stills?.let { of(bytes(it.size), bytes(it.maxSize)) } ?: context.getString(R.string.debug_not_used),
                ),
                DebugRow(R.string.debug_image_disk, bytes(report.images)),
                lists?.let { DebugRow(R.string.debug_list_disk, of(bytes(it.size()), bytes(it.maxSize()))) },
                lists?.let {
                    DebugRow(
                        R.string.debug_requests,
                        context.getString(R.string.debug_requests_value, number(it.requestCount().toLong()), number(it.hitCount().toLong())),
                    )
                },
                DebugRow(R.string.debug_saved, report.saved?.let(::bytes) ?: NONE),
                DebugRow(R.string.debug_app_size, report.app?.let(::bytes) ?: NONE),
            ),
        )
    }

    private fun crashes(): DebugSection {
        val crash = crashLog.latest()
        // The report opens with a header and a blank line; the trace's first line names the error.
        val error = crash?.text?.substringAfter("\n\n")?.lineSequence()?.firstOrNull { it.isNotBlank() }
        return DebugSection(
            R.string.debug_section_crashes,
            listOfNotNull(
                DebugRow(R.string.debug_last_crash, crash?.let { dateTime(it.at) } ?: context.getString(R.string.debug_no_crash)),
                error?.let { DebugRow(R.string.debug_crash_error, it.trim()) },
            ),
        )
    }

    /** `/proc/self/status` as name to value, e.g. `VmRSS` to `"123456 kB"`. */
    private fun processStatus(): Map<String, String> = runCatching {
        File("/proc/self/status").readLines().mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon < 0) null else line.substring(0, colon) to line.substring(colon + 1).trim()
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun yesNo(value: Boolean) = context.getString(if (value) R.string.debug_yes else R.string.debug_no)

    private fun of(part: String, whole: String) = context.getString(R.string.debug_of, part, whole)

    private fun bytes(value: Long) = Formatter.formatShortFileSize(context, value)

    private fun kilobytes(value: Long) = bytes(value * 1024)

    /** A field given in kilobytes, as `"123456 kB"` or `"123456"`. */
    private fun kilobytesField(field: String) = field.substringBefore(' ').toLongOrNull()?.let(::kilobytes) ?: field

    private fun number(value: Long) = NumberFormat.getIntegerInstance().format(value)

    private fun dateTime(at: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))

    private companion object {
        const val NONE = "—"
    }
}
