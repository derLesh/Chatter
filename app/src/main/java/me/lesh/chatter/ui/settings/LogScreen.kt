package me.lesh.chatter.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
import me.lesh.chatter.ui.FormSheet
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.pill.MessagePillHost
import me.lesh.chatter.ui.pill.PillTone
import me.lesh.chatter.ui.pill.showPill
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * The log over the whole screen, newest on top: today live, an earlier day picked from the row on
 * top. It can be searched and filtered by level and source. The eye switches between the private
 * log and the full one, and reporting, copying and saving take the shown day as the eye shows it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogScreen(vm: MainViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val live = rememberLiveLog()
    val device = rememberDevice(vm)

    val today = remember { LocalDate.now() }
    val startOfToday = remember(today) { today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    var days by remember { mutableStateOf(emptyList<LocalDate>()) }
    LaunchedEffect(Unit) { days = withContext(Dispatchers.IO) { AppLog.days() } }
    // Saved as the day's number, so it outlives turning the phone; null is today.
    var pickedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val day = pickedDay?.let(LocalDate::ofEpochDay)
    var earlier by remember(day) { mutableStateOf<List<AppLog.Entry>?>(null) }
    if (day != null) LaunchedEffect(day) { earlier = withContext(Dispatchers.IO) { AppLog.day(day).asReversed() } }
    val entries = if (day == null) remember(live, startOfToday) { live.filter { it.at >= startOfToday } } else earlier.orEmpty()

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    // Saved as names, so the choice outlives turning the phone.
    var levelNames by rememberSaveable { mutableStateOf(DEFAULT_LEVELS.map { it.name }) }
    var tags by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var withNames by rememberSaveable { mutableStateOf(false) }
    var deviceOpen by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmFullReport by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val sharing = rememberLogSharing(vm, device, snackbar)
    val levels = levelNames.mapNotNull { name -> AppLog.Level.entries.firstOrNull { it.name == name } }.toSet()
    // Oldest first, as a report and a file read.
    val dayLog = { entries.asReversed() }

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
    LaunchedEffect(day) { list.scrollToItem(0) }

    if (confirmClear) {
        FormSheet(
            title = stringResource(R.string.debug_log_clear_title),
            text = stringResource(R.string.debug_log_clear_text),
            confirmLabel = stringResource(R.string.debug_log_clear_confirm),
            destructive = true,
            onConfirm = {
                AppLog.clear()
                days = emptyList()
                pickedDay = null
            },
            onDismiss = { confirmClear = false },
        )
    }
    // An issue is public; names and links should not land there by accident.
    if (confirmFullReport) {
        FormSheet(
            title = stringResource(R.string.debug_log_report_full_title),
            text = stringResource(R.string.debug_log_report_full_text),
            confirmLabel = stringResource(R.string.debug_log_report),
            onConfirm = { sharing.report(dayLog(), true) },
            onDismiss = { confirmFullReport = false },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { MessagePillHost(snackbar) },
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
                            Text(
                                count,
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
                                        sharing.copy(dayLog(), withNames)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.debug_log_save)) },
                                    onClick = {
                                        menuOpen = false
                                        sharing.save(dayLog(), withNames, fileName(day, withNames))
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
                // Takes the day as the eye shows it.
                ExtendedFloatingActionButton(
                    onClick = { if (withNames) confirmFullReport = true else sharing.report(dayLog(), false) },
                    icon = { Icon(ImageVector.vectorResource(R.drawable.ic_bug_report), contentDescription = null) },
                    text = { Text(stringResource(R.string.debug_log_report)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (days.size > 1) {
                DayPicker(days, today, picked = day ?: today, onPick = { pickedDay = if (it == today) null else it.toEpochDay() })
            }
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
                    DeviceCard(device, open = deviceOpen, onToggle = { deviceOpen = !deviceOpen })
                }
                if (shown.isEmpty() && (day == null || earlier != null)) {
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
                        divider = !first,
                        modifier = Modifier
                            .animateItem()
                            .fillMaxWidth()
                            .background(card, lineShape(first, last))
                            .padding(horizontal = 14.dp)
                            .padding(top = if (first) 4.dp else 0.dp, bottom = if (last) 4.dp else 0.dp),
                    )
                }
            }
        }
    }
}

/** Today and the earlier days kept on the phone, newest first. */
@Composable
private fun DayPicker(days: List<LocalDate>, today: LocalDate, picked: LocalDate, onPick: (LocalDate) -> Unit) {
    val format = remember {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM"))
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        items(days, key = { it.toEpochDay() }) { date ->
            FilterChip(
                selected = date == picked,
                onClick = { onPick(date) },
                label = {
                    Text(
                        when (date) {
                            today -> stringResource(R.string.debug_log_today)
                            today.minusDays(1) -> stringResource(R.string.debug_log_yesterday)
                            else -> date.format(format)
                        },
                    )
                },
            )
        }
    }
}

/** The phone in one line, e.g. "Chatter 0.14.0 · Android 17 · Google Pixel 9"; a tap shows all of it. */
@Composable
private fun DeviceCard(device: List<Pair<String, String>>, open: Boolean, onToggle: () -> Unit) {
    val short = remember(device) {
        val values = device.toMap()
        listOfNotNull(
            values["Chatter"]?.let { "Chatter " + it.substringBefore(" (") },
            values["Android"]?.let { "Android " + it.substringBefore(" (") },
            values["Phone"],
        ).joinToString(" · ")
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .padding(bottom = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(CORNER))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onToggle)
            // Grows and shrinks, so the lines below slide along instead of jumping.
            .animateContentSize()
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
    ) {
        if (open) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    LogReport.deviceText(device).trimEnd(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                short,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Icon(
            if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/** Report, copy and save the lines given, oldest first, with the private or the full log. */
private class LogSharing(
    val report: (entries: List<AppLog.Entry>, full: Boolean) -> Unit,
    val copy: (entries: List<AppLog.Entry>, full: Boolean) -> Unit,
    val save: (entries: List<AppLog.Entry>, full: Boolean, fileName: String) -> Unit,
)

@Composable
private fun rememberLogSharing(vm: MainViewModel, device: List<Pair<String, String>>, snackbar: SnackbarHostState): LogSharing {
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.debug_log_clip_label)
    val copy = { text: String -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, text))) } }
    // What the file picker was opened for; it hands back only where to write.
    var saving by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = saving ?: return@rememberLauncherForActivityResult
        saving = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("no output stream")
                }.isSuccess
            }
            if (saved) snackbar.showPill(resources.getString(R.string.debug_log_saved), PillTone.Success)
            else snackbar.showPill(resources.getString(R.string.debug_log_save_failed), PillTone.Error)
        }
    }
    return LogSharing(
        report = { entries, full ->
            val logs = LogReport.forIssue(device, entries, full)
            // A link holds only so much; the rest goes into the clipboard for pasting.
            if (!logs.whole) {
                copy(LogReport.text(device, entries, full))
                toast(context, R.string.debug_log_report_clipboard)
            }
            context.startActivity(Intent(Intent.ACTION_VIEW, bugReportUrl(vm.device, logs.text).toUri()))
        },
        copy = { entries, full -> copy(LogReport.text(device, entries, full)) },
        save = { entries, full, fileName ->
            saving = LogReport.text(device, entries, full)
            save.launch(fileName)
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

/** Time and source above, the message below; warnings and errors also name their level. */
@Composable
private fun LogLine(entry: AppLog.Entry, withNames: Boolean, query: String, divider: Boolean, modifier: Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val levelColor = when (entry.level) {
        AppLog.Level.Error -> MaterialTheme.colorScheme.error
        AppLog.Level.Warn -> MaterialTheme.colorScheme.tertiary
        // Only warnings and errors stand out; everything else is what usually happens.
        AppLog.Level.Info -> MaterialTheme.colorScheme.onSurface
        AppLog.Level.Debug, AppLog.Level.Verbose -> muted
    }
    val levelName = if (entry.level >= AppLog.Level.Warn) stringResource(levelLabel(entry.level)) else null
    val match = SpanStyle(background = MaterialTheme.colorScheme.primaryContainer, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
    val head = remember(entry, levelName, muted, levelColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = muted, fontFeatureSettings = "tnum")) { append(shortTime(entry.at)) }
            append("  ")
            withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.Medium)) {
                append(entry.tag)
                if (levelName != null) append(" · $levelName")
            }
        }
    }
    val message = remember(entry, withNames, query) { highlighted(entry.message(withNames), query, match) }
    Column(modifier) {
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        SelectionContainer(Modifier.padding(vertical = 8.dp)) {
            Column {
                Text(head, style = MaterialTheme.typography.labelMedium)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (entry.level == AppLog.Level.Verbose) muted else Color.Unspecified,
                )
            }
        }
    }
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

private fun levelLabel(level: AppLog.Level) =
    if (level == AppLog.Level.Error) R.string.debug_log_line_error else R.string.debug_log_line_warn

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

/** The phone's local time, to the second; the copied text says UTC to the millisecond, so reports from anywhere line up. */
private fun shortTime(at: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))

/** Today's file is named by the minute it was saved, an earlier day's by its date. */
private fun fileName(day: LocalDate?, full: Boolean): String =
    "chatter-log-" + (if (full) "full-" else "") + (day?.toString() ?: SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date())) + ".txt"

private fun toast(context: Context, text: Int) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

/** Details are many a minute (every image, every chat command); shown when asked for. */
private val DEFAULT_LEVELS = AppLog.Level.entries - AppLog.Level.Verbose

private val CORNER = 20.dp

private const val REFRESH_MS = 250L
