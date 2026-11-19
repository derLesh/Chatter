package me.lesh.chatter.ui.settings

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import me.lesh.chatter.BuildConfig
import me.lesh.chatter.R
import me.lesh.chatter.crash.Crash
import me.lesh.chatter.crash.CrashIssue
import me.lesh.chatter.crash.DeviceInfo
import me.lesh.chatter.settings.Settings
import me.lesh.chatter.ui.FormSheet
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.SettingsSubPage
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lesh.chatter.util.shownVersion

/**
 * Supporting Chatter. GitHub Sponsors handles the money but cannot know a sponsor's Twitch account,
 * so the second row lets them tell us, with the Twitch id filled in.
 */
@Composable
internal fun SupportPage(vm: MainViewModel) {
    SettingsGroup {
        item { LinkItem(R.string.settings_sponsor, R.string.settings_sponsor_summary, SPONSOR_URL) }
        item {
            LinkItem(
                stringResource(R.string.settings_sponsor_claim),
                stringResource(R.string.settings_sponsor_claim_summary),
                claimUrl(vm.ownTwitchId, vm.ownLogin),
            )
        }
    }
    Text(
        stringResource(R.string.settings_sponsor_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp),
    )
}

/**
 * A new issue with the Twitch account filled in. GitHub vouches for who opens it, so the badge can
 * be granted automatically.
 */
private fun claimUrl(twitchId: String?, login: String): String =
    "$REPO_URL/issues/new".toUri().buildUpon()
        .appendQueryParameter("template", "supporter.yml")
        .appendQueryParameter("title", "Supporter badge for $login")
        .appendQueryParameter("twitch-id", twitchId.orEmpty())
        .appendQueryParameter("twitch-name", login)
        .build()
        .toString()

/**
 * Opens the bug report form with version, Android and device filled in. After a crash it first
 * asks whether to add it; see [rememberReportIssue].
 */
@Composable
private fun ReportIssueItem(reportIssue: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_report_issue)) },
        supportingContent = { Text(stringResource(R.string.settings_report_issue_summary)) },
        trailingContent = { LeavesAppMark() },
        colors = transparentItem(),
        modifier = Modifier.tileClickable(onClick = reportIssue),
    )
}

/**
 * What a tap on "Report a problem" runs: opens the bug report, after a crash first asking in a
 * sheet whether to put it into the form. Shows that sheet itself.
 */
@Composable
private fun rememberReportIssue(vm: MainViewModel): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reportCrash = rememberCrashReport(vm.device)
    var crash by remember { mutableStateOf<Crash?>(null) }
    // Off, so a report about something else does not send a crash nobody looked at.
    var withCrash by remember { mutableStateOf(false) }
    val open = { context.startActivity(Intent(Intent.ACTION_VIEW, bugReportUrl(vm.device).toUri())) }
    crash?.let { last ->
        FormSheet(
            title = stringResource(R.string.settings_report_issue),
            text = stringResource(R.string.crash_attach_text),
            confirmLabel = stringResource(R.string.crash_attach_open),
            onConfirm = { if (withCrash) reportCrash(last) else open() },
            onDismiss = { crash = null },
        ) {
            item(key = "crash") {
                val at = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last.at))
                // A row rather than a ListItem, so it lines up with the sheet's title and text.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .toggleable(value = withCrash, role = Role.Switch, onValueChange = { withCrash = it }),
                ) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(stringResource(R.string.crash_attach_switch), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.crash_attach_switch_summary, last.errorKind, at),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = withCrash, onCheckedChange = null)
                }
            }
        }
    }
    return {
        scope.launch {
            val last = vm.lastCrash()
            if (last == null) {
                open()
            } else {
                withCrash = false
                crash = last
            }
        }
    }
}

/**
 * The bug report template URL with app and device fields filled in, the log field if [logs] is
 * given, and with a [crash] its title, its own version and its log.
 */
internal fun bugReportUrl(device: DeviceInfo, logs: String? = null, crash: CrashIssue? = null): String =
    "$REPO_URL/issues/new".toUri().buildUpon()
        .appendQueryParameter("template", "bug.yml")
        .apply { if (crash != null) appendQueryParameter("title", crash.title) }
        .appendQueryParameter("version", crash?.version ?: "${device.appVersion} (${device.versionCode})")
        .appendQueryParameter("android", crash?.android ?: "${device.android} (API ${device.sdk})")
        .appendQueryParameter("device", device.device)
        .apply { if (crash != null) appendQueryParameter("crash", crash.crash) }
        .apply { (logs ?: crash?.log?.ifBlank { null })?.let { appendQueryParameter("logs", it) } }
        .build()
        .toString()

@Composable
internal fun AboutPage(settings: Settings, vm: MainViewModel, open: (SettingsSubPage) -> Unit) {
    val reportIssue = rememberReportIssue(vm)
    // The wordmark already shows the name, so no heading below.
    EnterInOrder {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Image(
                painterResource(R.drawable.ic_chatter_wordmark),
                contentDescription = stringResource(R.string.app_name),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.width(208.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.settings_version, shownVersion),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // New bugs show up in Nightly first, so reporting one moves up from the list below.
            if (BuildConfig.NIGHTLY) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = reportIssue) { Text(stringResource(R.string.settings_report_issue)) }
            }
        }
    }
    SettingsGroup {
        item(R.string.settings_changelog) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_changelog)) },
                supportingContent = { Text(stringResource(R.string.settings_changelog_summary)) },
                trailingContent = { NavChevron() },
                colors = transparentItem(),
                modifier = Modifier.tileClickable { open(SettingsSubPage.Changelog) },
            )
        }
        item(R.string.settings_source_code) { LinkItem(R.string.settings_source_code, R.string.settings_source_code_summary, REPO_URL) }
        // A security hole goes to a private report, not into a public issue like a bug.
        item(R.string.settings_security) { LinkItem(R.string.settings_security, R.string.settings_security_summary, SECURITY_URL) }
        item(R.string.settings_privacy) { LinkItem(R.string.settings_privacy, R.string.settings_privacy_summary, PRIVACY_URL) }
        item(R.string.settings_credits) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_credits)) },
                supportingContent = { Text(stringResource(R.string.settings_credits_summary)) },
                trailingContent = { NavChevron() },
                colors = transparentItem(),
                modifier = Modifier.tileClickable { open(SettingsSubPage.Credits) },
            )
        }
    }
    // Asked for a crash or the log, anyone finds them here.
    SettingsGroup(R.string.settings_problems) {
        if (!BuildConfig.NIGHTLY) item(R.string.settings_report_issue) { ReportIssueItem(reportIssue) }
        item(R.string.crashes_title) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.crashes_title)) },
                trailingContent = { NavChevron() },
                colors = transparentItem(),
                modifier = Modifier.tileClickable { open(SettingsSubPage.Crashes) },
            )
        }
        item(R.string.debug_log) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.debug_log)) },
                trailingContent = { NavChevron() },
                colors = transparentItem(),
                modifier = Modifier.tileClickable { open(SettingsSubPage.Log) },
            )
        }
    }
    // Only the GitHub APK has this; Play updates its own installs.
    if (BuildConfig.UPDATE_CHECK) SettingsGroup {
        item(R.string.settings_update_check) {
            SwitchItem(
                R.string.settings_update_check,
                settings.updateCheck,
                vm::setUpdateCheck,
                R.string.settings_update_check_hint,
            )
        }
    }
    // Twitch's branding rules require third-party clients to say they are not Twitch's.
    Text(
        stringResource(R.string.settings_twitch_disclaimer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp),
    )
}

/** The services Chatter uses and the libraries it ships, on a page of their own. */
@Composable
internal fun CreditsPage() {
    var shownLicense by remember { mutableStateOf<Dependency?>(null) }
    SettingsGroup(R.string.settings_credits_services) {
        CREDITS.forEach { (title, summary, url) ->
            item { LinkItem(title, summary, url) }
        }
    }
    SettingsGroup(R.string.settings_licenses) {
        DEPENDENCIES.forEach { dependency ->
            item {
                ListItem(
                    headlineContent = { Text(dependency.name) },
                    supportingContent = { Text(stringResource(dependency.license)) },
                    trailingContent = { NavChevron() },
                    colors = transparentItem(),
                    modifier = Modifier.tileClickable { shownLicense = dependency },
                )
            }
        }
    }
    shownLicense?.let { dependency ->
        LicenseSheet(dependency, onDismiss = { shownLicense = null })
    }
}

/** A shipped library for the license list, with its license name and text. */
private data class Dependency(
    val name: String,
    val url: String,
    val license: Int = R.string.license_apache2,
    val text: Int = R.raw.license_apache_2_0,
)

/** The shipped libraries. Most share the Apache 2.0 text. */
private val DEPENDENCIES = listOf(
    Dependency("Kotlin", "https://kotlinlang.org"),
    Dependency("Kotlin Coroutines", "https://github.com/Kotlin/kotlinx.coroutines"),
    Dependency("kotlinx.serialization", "https://github.com/Kotlin/kotlinx.serialization"),
    Dependency("AndroidX (Core, Activity, Lifecycle, DataStore)", "https://developer.android.com/jetpack/androidx"),
    Dependency("Jetpack Compose", "https://developer.android.com/jetpack/compose"),
    Dependency("OkHttp", "https://square.github.io/okhttp/"),
    Dependency("Coil", "https://coil-kt.github.io/coil/"),
    Dependency("RE2/J", "https://github.com/google/re2j", R.string.license_bsd3, R.raw.license_re2j),
    // The emoji list and its shortcodes; see assets/emoji.tsv.
    Dependency("gemoji", "https://github.com/github/gemoji", R.string.license_mit, R.raw.license_gemoji),
)

/** The full license text of a dependency and a link to its project. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseSheet(dependency: Dependency, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    // About 11 kB, read off the first frame so the sheet opens instantly.
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        text = withContext(Dispatchers.IO) {
            runCatching {
                resources.openRawResource(dependency.text).bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(dependency.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                stringResource(dependency.license),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, dependency.url.toUri())) },
                modifier = Modifier.padding(top = 12.dp),
            ) { Text(stringResource(R.string.license_open_project)) }
            // Monospace keeps the license's indentation and line breaks.
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

/** The services Chatter uses, each linking to its source. */
private val CREDITS = listOf(
    Triple(R.string.settings_credits_twitch, R.string.settings_credits_twitch_summary, "https://twitch.tv"),
    Triple(R.string.settings_credits_seventv, R.string.settings_credits_seventv_summary, "https://7tv.app"),
    Triple(R.string.settings_credits_bttv, R.string.settings_credits_bttv_summary, "https://betterttv.com"),
    Triple(R.string.settings_credits_ffz, R.string.settings_credits_ffz_summary, "https://frankerfacez.com"),
    Triple(R.string.settings_credits_recent, R.string.settings_credits_recent_summary, "https://recent-messages.robotty.de"),
)

internal const val REPO_URL = "https://github.com/derLesh/Chatter"
private const val SPONSOR_URL = "https://github.com/sponsors/derLesh"
private const val SECURITY_URL = "$REPO_URL/security/policy"
private const val PRIVACY_URL = "https://derlesh.github.io/Chatter/privacy-policy.html"
