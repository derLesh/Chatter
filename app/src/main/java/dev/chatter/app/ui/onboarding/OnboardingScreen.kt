package dev.chatter.app.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import dev.chatter.app.R
import dev.chatter.app.chat.PostSite
import dev.chatter.app.settings.Settings
import dev.chatter.app.ui.IntroPoint
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.ui.settings.HighlightColorPicker
import dev.chatter.app.ui.settings.KeywordsPage
import dev.chatter.app.ui.settings.PostPreview
import dev.chatter.app.ui.settings.SettingsGroup
import dev.chatter.app.ui.settings.SiteItem
import dev.chatter.app.ui.settings.SwitchItem
import dev.chatter.app.ui.settings.TextSizeItem
import dev.chatter.app.ui.settings.ThemeModeItem
import dev.chatter.app.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.launch

private enum class OnboardingStep { Welcome, Chat, Look, Highlights, Links }

/**
 * Shown once, right after the first login: how the chat works, and the settings worth picking
 * before the first channel. Every choice is saved at once, so skipping keeps what was set so far.
 */
@Composable
fun OnboardingScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val steps = OnboardingStep.entries
    val pager = rememberPagerState { steps.size }
    val scope = rememberCoroutineScope()
    val step = pager.currentPage
    val onLastStep = step == steps.lastIndex
    val toStep = { target: Int -> scope.launch { pager.animateScrollToPage(target) }; Unit }

    NavigationBackHandler(
        state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
        isBackEnabled = step > 0,
        onBackCompleted = { toStep(step - 1) },
    )
    // A Surface, not a background: the text takes its color from it.
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Hidden rather than removed on the last step, so the pages below keep their place.
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextButton(onClick = vm::finishOnboarding, enabled = !onLastStep, modifier = Modifier.alpha(if (onLastStep) 0f else 1f)) {
                    Text(stringResource(R.string.onboarding_skip))
                }
            }
            HorizontalPager(pager, Modifier.weight(1f), verticalAlignment = Alignment.Top) { page ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    when (steps[page]) {
                        OnboardingStep.Welcome -> WelcomeStep()
                        OnboardingStep.Chat -> ChatStep()
                        OnboardingStep.Look -> LookStep(settings, vm)
                        OnboardingStep.Highlights -> HighlightsStep(settings, vm)
                        OnboardingStep.Links -> LinksStep(settings, vm)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    if (step > 0) TextButton(onClick = { toStep(step - 1) }) { Text(stringResource(R.string.back)) }
                }
                StepDots(count = steps.size, current = step)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Button(onClick = { if (onLastStep) vm.finishOnboarding() else toStep(step + 1) }) {
                        Text(stringResource(if (onLastStep) R.string.onboarding_start else R.string.onboarding_next))
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    StepHeader(R.string.onboarding_welcome_title, R.string.onboarding_welcome_text) {
        Image(
            painterResource(R.drawable.ic_chatter_wordmark),
            contentDescription = stringResource(R.string.app_name),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.width(200.dp).padding(vertical = 16.dp),
        )
    }
    StepPoints {
        IntroPoint(ImageVector.vectorResource(R.drawable.ic_chat), R.string.onboarding_welcome_channels)
        IntroPoint(Icons.Default.Face, R.string.onboarding_welcome_emotes)
        IntroPoint(ImageVector.vectorResource(R.drawable.ic_image), R.string.onboarding_welcome_links)
    }
}

@Composable
private fun ChatStep() {
    StepHeader(R.string.onboarding_chat_title, R.string.onboarding_chat_text) { StepIcon(ImageVector.vectorResource(R.drawable.ic_chat)) }
    StepPoints {
        IntroPoint(Icons.Default.Add, R.string.onboarding_chat_add)
        IntroPoint(ImageVector.vectorResource(R.drawable.ic_swap_horiz), R.string.onboarding_chat_swipe)
        IntroPoint(Icons.AutoMirrored.Filled.Send, R.string.onboarding_chat_tap)
        IntroPoint(Icons.Default.MailOutline, R.string.onboarding_chat_inbox)
        IntroPoint(Icons.Default.Settings, R.string.onboarding_chat_settings)
    }
}

@Composable
private fun LookStep(settings: Settings, vm: MainViewModel) {
    StepHeader(R.string.onboarding_look_title, R.string.onboarding_look_text) { StepIcon(ImageVector.vectorResource(R.drawable.ic_palette)) }
    val dark = isAppInDarkTheme()
    SettingsGroup {
        item { ThemeModeItem(settings.themeMode, vm::setThemeMode) }
        item { SwitchItem(R.string.settings_dynamic_color, settings.dynamicColor, vm::setDynamicColor, R.string.settings_dynamic_color_hint) }
        // A light theme has no black.
        if (dark) item { SwitchItem(R.string.settings_pure_black, settings.pureBlack, vm::setPureBlack, R.string.settings_pure_black_hint) }
    }
    SettingsGroup { item { TextSizeItem(settings, vm) } }
}

@Composable
private fun HighlightsStep(settings: Settings, vm: MainViewModel) {
    StepHeader(R.string.onboarding_highlights_title, R.string.onboarding_highlights_text) { StepIcon(Icons.Default.Star) }
    SettingsGroup {
        item { HighlightColorPicker(settings.highlightColor, vm::setHighlightColor) }
        item {
            SwitchItem(
                R.string.settings_first_messages, settings.highlightFirstMessages,
                vm::setHighlightFirstMessages, R.string.settings_first_messages_hint,
            )
        }
    }
    KeywordsPage(
        words = settings.mentionKeywords,
        emptyText = R.string.keywords_none,
        hint = R.string.settings_keywords_hint,
        addTitle = R.string.keyword_add_mention,
        onAdd = vm::addMentionKeyword,
        onRemove = vm::removeMentionKeyword,
    )
}

@Composable
private fun LinksStep(settings: Settings, vm: MainViewModel) {
    StepHeader(R.string.onboarding_links_title, R.string.onboarding_links_text) { StepIcon(ImageVector.vectorResource(R.drawable.ic_image)) }
    SettingsGroup {
        item {
            SwitchItem(R.string.settings_inline_images, settings.inlineImages, vm::setInlineImages, R.string.settings_inline_images_hint)
        }
        PostSite.entries.forEach { site ->
            item { SiteItem(site, site in settings.postSites) { vm.setPostSite(site, it) } }
        }
    }
    PostPreview(settings, vm, text = R.string.onboarding_post_preview)
}

/** What a step is about, above its points or settings; [art] goes on top. */
@Composable
private fun StepHeader(title: Int, text: Int, art: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        art()
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The same round icon the notification intro opens with, a little smaller. */
@Composable
private fun StepIcon(icon: ImageVector) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
    }
}

@Composable
private fun StepPoints(points: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        points()
    }
}

/** Where in the onboarding the user is; the current step is a wider dot. */
@Composable
private fun StepDots(count: Int, current: Int) {
    val description = stringResource(R.string.onboarding_step, current + 1, count)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 20.dp else 8.dp, label = "dot-width")
            val color by animateColorAsState(
                if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                label = "dot-color",
            )
            Box(Modifier.size(width = width, height = 8.dp).background(color, CircleShape))
        }
    }
}
