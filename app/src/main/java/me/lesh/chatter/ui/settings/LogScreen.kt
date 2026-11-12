package me.lesh.chatter.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lesh.chatter.R
import me.lesh.chatter.auth.AuthState
import me.lesh.chatter.debug.AppLog
import me.lesh.chatter.debug.DeviceReport
import me.lesh.chatter.debug.LogReport
import me.lesh.chatter.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The log over the whole screen: live, newest on top, the phone above it. It can be searched and
 * filtered by level and source. The eye switches between the private log and the full one, and
 * reporting, copying and saving take the whole log as the eye shows it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries = rememberLiveLog()
    val device = rememberDevice(vm)

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    // Saved as names, so the choice outlives turning the phone.
    var levelNames by rememberSaveable { mutableStateOf(DEFAULT_LEVELS.map { it.name }) }
    var tags by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var withNames by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmFullReport by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val sharing = rememberLogSharing(vm, device)
    val levels = levelNames.mapNotNull { name -> AppLog.Level.entries.firstOrNull { it.name == name } }.toSet()

    if (searching) {
        NavigationBackHandler(
            state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
            onBackCompleted = { searching = false },
        )
    }
    val shown = remember(entries, query, levels, tags, withNames) {
        entries.filter { e ->
            e.level in levels && (tags.isEmpty() || e.tag in tags) &&
                (query.isBlank() || e.message(withNames).contains(query, ignoreCase = true) || e.tag.contains(query, ignoreCase = true))
        }
    }
    val filtered = shown.size != entries.size
    val list = rememberLazyListState()
    val awayFromNewest by remember { derivedStateOf { list.firstVisibleItemIndex > 1 } }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.debug_log_clear_title)) },
            text = { Text(stringResource(R.string.debug_log_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    AppLog.clear()
                    confirmClear = false
                }) { Text(stringResource(R.string.debug_log_clear_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // An issue is public; names and links should not land there by accident.
    if (confirmFullReport) {
        AlertDialog(
            onDismissRequest = { confirmFullReport = false },
            title = { Text(stringResource(R.string.debug_log_report_full_title)) },
            text = { Text(stringResource(R.string.debug_log_report_full_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmFullReport = false
                    sharing.report(true)
                }) { Text(stringResource(R.string.debug_log_report)) }
            },
            dismissButton = { TextButton(onClick = { confirmFullReport = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (searching) {
                SearchBar(query, onQuery = { query = it }, onClose = {
                    searching = false
                    query = ""
                })
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.debug_log))
                            val count = if (filtered) {
                                pluralStringResource(R.plurals.debug_log_count_filtered, entries.size, shown.size, entries.size)
                            } else {
                                pluralStringResource(R.plurals.debug_log_count, entries.size, entries.size)
                            }
                            val kind = stringResource(if (withNames) R.string.debug_log_state_full else R.string.debug_log_state_private)
                            Text(
                                "$count · $kind",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    },
                    actions = {
                        // Shows what the log looks like now: crossed out while names are hidden.
                        IconToggleButton(checked = withNames, onCheckedChange = { withNames = it }) {
                            Icon(
                                ImageVector.vectorResource(if (withNames) R.drawable.ic_visibility else R.drawable.ic_visibility_off),
                                stringResource(if (withNames) R.string.debug_log_hide_names else R.string.debug_log_show_names),
                            )
                        }
                        IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, stringResource(R.string.debug_log_search)) }
                        IconToggleButton(checked = filtersOpen, onCheckedChange = { filtersOpen = it }) {
                            Icon(
                                ImageVector.vectorResource(R.drawable.ic_filter_list),
                                stringResource(R.string.debug_log_filter),
                                // Marked while a filter hides something, even with the row closed.
                                tint = if (filtered && !filtersOpen) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            )
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more_options)) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.debug_log_copy)) },
                                    onClick = {
                                        menuOpen = false
                                        sharing.copy(withNames)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.debug_log_save)) },
                                    onClick = {
                                        menuOpen = false
                                        sharing.save(withNames)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.debug_log_clear)) },
                                    onClick = {
                                        menuOpen = false
                                        confirmClear = true
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimatedVisibility(awayFromNewest, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    SmallFloatingActionButton(onClick = { scope.launch { list.animateScrollToItem(0) } }) {
                        Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.debug_log_newest))
                    }
                }
                // Takes the log as the eye shows it.
                ExtendedFloatingActionButton(
                    onClick = { if (withNames) confirmFullReport = true else sharing.report(false) },
                    icon = { Icon(ImageVector.vectorResource(R.drawable.ic_bug_report), contentDescription = null) },
                    text = { Text(stringResource(R.string.debug_log_report)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(filtersOpen || searching && filtered, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Filters(
                    entries = entries,
                    levels = levels,
                    onLevels = { new -> levelNames = new.map { it.name } },
                    tags = tags,
                    onTags = { tags = it },
                )
            }
            val card = MaterialTheme.colorScheme.surfaceContainer
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                // Room below the last line for the report button.
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
            ) {
                // First, so the list holds on to it while new lines arrive above the rest.
                item(key = "device") {
                    SelectionContainer {
                        Text(
                            LogReport.deviceText(device).trimEnd(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(bottom = 12.dp)
                                .fillMaxWidth()
                                .background(card, RoundedCornerShape(CORNER))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
                if (shown.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(if (entries.isEmpty()) R.string.debug_log_empty else R.string.debug_log_no_match),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().background(card, RoundedCornerShape(CORNER)).padding(16.dp),
                        )
                    }
                }
                items(shown, key = { it.number }) { entry ->
                    val first = entry === shown.first()
                    val last = entry === shown.last()
                    LogLine(
                        entry,
                        withNames = withNames,
                        query = query,
                        modifier = Modifier
                            .animateItem()
                            .fillMaxWidth()
                            .background(card, lineShape(first, last))
                            .padding(horizontal = 14.dp)
                            .padding(top = if (first) 10.dp else 2.dp, bottom = if (last) 10.dp else 2.dp),
                    )
                }
            }
        }
    }
}

/** The whole log, newest first, read again a few times a second while lines arrive. */
@Composable
private fun rememberLiveLog(): List<AppLog.Entry> {
    var entries by remember { mutableStateOf(AppLog.entries().asReversed()) }
    LaunchedEffect(Unit) {
        // A busy chat writes many lines a second; a few redraws a second still read as live.
        AppLog.revision.collect {
            entries = AppLog.entries().asReversed()
            delay(REFRESH_MS)
        }
    }
    return entries
}

@Composable
private fun rememberDevice(vm: MainViewModel): List<Pair<String, String>> {
    val context = LocalContext.current
    val auth by vm.authState.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    // "Running for" would stand still otherwise.
    var minutes by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            minutes++
        }
    }
    return remember(auth, channels.size, minutes) { DeviceReport.lines(context, accountKind(auth), channels.size) }
}

/** Report, copy and save, each with the private or the full log, and always all of it. */
private class LogSharing(val report: (full: Boolean) -> Unit, val copy: (full: Boolean) -> Unit, val save: (full: Boolean) -> Unit)

@Composable
private fun rememberLogSharing(vm: MainViewModel, device: List<Pair<String, String>>): LogSharing {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.debug_log_clip_label)
    val copy = { text: String -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, text))) } }
    val fileText = { full: Boolean -> LogReport.text(device, AppLog.entries(), full) }
    // Which log the file picker was opened for; it hands back only where to write.
    var saveFull by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = fileText(saveFull)
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("no output stream")
                }.isSuccess
            }
            toast(context, if (saved) R.string.debug_log_saved else R.string.debug_log_save_failed)
        }
    }
    return LogSharing(
        report = { full ->
            val logs = LogReport.forIssue(device, AppLog.entries(), full)
            // A link holds only so much; the rest goes into the clipboard for pasting.
            if (!logs.whole) {
                copy(fileText(full))
                toast(context, R.string.debug_log_report_clipboard)
            }
            context.startActivity(Intent(Intent.ACTION_VIEW, bugReportUrl(vm.device, logs.text).toUri()))
        },
        copy = { copy(fileText(it)) },
        save = {
            saveFull = it
            save.launch(fileName(it))
        },
    )
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal).asPaddingValues())
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.debug_log_search)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, stringResource(R.string.settings_search_clear)) }
            }
        }
    }
}

/** Levels in one row, the sources that wrote something in a second. */
@Composable
private fun Filters(
    entries: List<AppLog.Entry>,
    levels: Set<AppLog.Level>,
    onLevels: (Set<AppLog.Level>) -> Unit,
    tags: List<String>,
    onTags: (List<String>) -> Unit,
) {
    // Busiest first: that is where one usually wants to look, or what one wants to hide.
    val sources = remember(entries) { entries.groupingBy { it.tag }.eachCount().entries.sortedByDescending { it.value } }
    val row = PaddingValues(horizontal = 12.dp)
    Column(verticalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        LazyRow(contentPadding = row, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AppLog.Level.entries.reversed()) { level ->
                FilterChip(
                    selected = level in levels,
                    onClick = { onLevels(if (level in levels) levels - level else levels + level) },
                    label = { Text(stringResource(levelName(level))) },
                )
            }
        }
        LazyRow(contentPadding = row, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources, key = { it.key }) { (tag, count) ->
                FilterChip(
                    selected = tag in tags,
                    onClick = { onTags(if (tag in tags) tags - tag else tags + tag) },
                    label = { Text("$tag $count") },
                )
            }
        }
    }
}

@Composable
private fun LogLine(entry: AppLog.Entry, withNames: Boolean, query: String, modifier: Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val levelColor = when (entry.level) {
        AppLog.Level.Error -> MaterialTheme.colorScheme.error
        AppLog.Level.Warn -> MaterialTheme.colorScheme.tertiary
        AppLog.Level.Info -> MaterialTheme.colorScheme.primary
        AppLog.Level.Debug -> MaterialTheme.colorScheme.onSurface
        AppLog.Level.Verbose -> muted
    }
    val match = SpanStyle(background = MaterialTheme.colorScheme.primaryContainer, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
    val text = remember(entry, withNames, query, muted, levelColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = muted)) { append("${shortTime(entry.at)} ") }
            withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.Medium)) { append("${entry.level.letter} ${entry.tag} ") }
            val message = entry.message(withNames)
            withStyle(SpanStyle(color = if (entry.level == AppLog.Level.Verbose) muted else Color.Unspecified)) {
                append(highlighted(message, query, match))
            }
        }
    }
    SelectionContainer(modifier) { Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
}

private fun highlighted(text: String, query: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    if (query.isBlank()) return@buildAnnotatedString
    var from = text.indexOf(query, ignoreCase = true)
    while (from >= 0) {
        addStyle(style, from, from + query.length)
        from = text.indexOf(query, from + query.length, ignoreCase = true)
    }
}

private fun lineShape(first: Boolean, last: Boolean): RoundedCornerShape {
    val top = if (first) CORNER else 0.dp
    val bottom = if (last) CORNER else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

private fun levelName(level: AppLog.Level) = when (level) {
    AppLog.Level.Error -> R.string.debug_log_level_error
    AppLog.Level.Warn -> R.string.debug_log_level_warn
    AppLog.Level.Info -> R.string.debug_log_level_info
    AppLog.Level.Debug -> R.string.debug_log_level_debug
    AppLog.Level.Verbose -> R.string.debug_log_level_verbose
}

/** How the app is used, without whose account it is. */
private fun accountKind(auth: AuthState): String = when (auth) {
    is AuthState.LoggedIn -> "logged in"
    AuthState.Guest -> "guest"
    AuthState.LoggedOut -> "logged out"
    AuthState.Loading -> "loading"
}

/** The phone's local time; the copied text says UTC instead, so reports from anywhere line up. */
private fun shortTime(at: Long): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(at))

private fun fileName(full: Boolean): String =
    "chatter-log-" + (if (full) "full-" else "") + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date()) + ".txt"

private fun toast(context: Context, text: Int) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

/** Details are many a minute (every image, every chat command); shown when asked for. */
private val DEFAULT_LEVELS = AppLog.Level.entries - AppLog.Level.Verbose

private val CORNER = 20.dp

private const val REFRESH_MS = 250L
