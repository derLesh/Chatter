package me.lesh.chatter.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.lesh.chatter.BuildConfig
import me.lesh.chatter.R
import me.lesh.chatter.auth.ForeignTokenException
import me.lesh.chatter.auth.TokenOwner
import me.lesh.chatter.net.HttpException
import java.io.IOException

/**
 * The fallback login, opened by holding the logo on the login screen or "Add" on the account page:
 * get a token on the Chatter website, then paste it or scan its QR code. The account it belongs to
 * shows above the login button before anything is saved. A token for another account adds it.
 */
@Composable
fun TokenLoginScreen(vm: MainViewModel, onBack: () -> Unit, onLoggedIn: () -> Unit = {}) {
    // Not saveable: a token has no place in the saved state, which Android may write to disk.
    var text by remember { mutableStateOf("") }
    var scanned by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<TokenCheck>(TokenCheck.Empty) }
    var retries by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val focus = LocalFocusManager.current

    // Ignored while saving rather than disabled, which would hand the gesture to the screen below.
    NavigationBackHandler(
        state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
        onBackCompleted = { if (!saving) onBack() },
    )

    LaunchedEffect(text, retries) {
        if (text.isBlank()) {
            check = TokenCheck.Empty
            return@LaunchedEffect
        }
        // Cleared at once, so an account shown for the text before cannot be logged in.
        check = TokenCheck.Empty
        // Waits for typing to stop, so not every key asks Twitch.
        if (!scanned) delay(CHECK_DELAY_MS)
        check = TokenCheck.Checking
        val result = vm.ownerOfPastedToken(text)
        check = when {
            result == null -> TokenCheck.Failed(if (scanned) TokenProblem.NotATokenCode else TokenProblem.NotAToken)
            result.isFailure -> TokenCheck.Failed(problemOf(result.exceptionOrNull()))
            else -> TokenCheck.Found(result.getOrThrow(), checkedText = text)
        }
    }

    fun logIn() {
        val found = check as? TokenCheck.Found ?: return
        // The field may have changed since the account below was shown.
        if (saving || found.checkedText != text) return
        focus.clearFocus()
        saving = true
        scope.launch {
            val result = vm.logInWithPastedToken(found.checkedText)
            saving = false
            when {
                result == null -> check = TokenCheck.Failed(TokenProblem.NotAToken)
                result.isFailure -> check = TokenCheck.Failed(problemOf(result.exceptionOrNull()), retryLogin = found)
                else -> onLoggedIn()
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
            ) {
                IconButton(onClick = onBack, enabled = !saving) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Text(
                    stringResource(R.string.login_token_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    stringResource(R.string.login_token_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
                )
                WebsiteCard(enabled = !saving, onClick = { uriHandler.openUri(TOKEN_PAGE_URL) })

                Spacer(Modifier.height(28.dp))
                TokenField(
                    text = text,
                    onTextChange = { text = it; scanned = false },
                    enabled = !saving,
                    problem = (check as? TokenCheck.Failed)?.problem?.takeIf { it.inToken }?.text(),
                    onDone = { focus.clearFocus() },
                )

                OrDivider()
                QrScanCard(
                    caption = stringResource(R.string.login_token_scan_caption),
                    scanned = scanned,
                    // A failed token makes way for the one about to be scanned.
                    onCameraOn = { if (check is TokenCheck.Failed) { text = ""; scanned = false } },
                    onScanned = { code -> scanned = true; text = code.trim() },
                    enabled = !saving,
                )
                Spacer(Modifier.height(16.dp))
            }

            LoginBar(
                check = check,
                saving = saving,
                imageLoader = vm.imageLoader,
                onRetry = {
                    val failedLogin = (check as? TokenCheck.Failed)?.retryLogin
                    if (failedLogin != null) {
                        check = failedLogin
                        logIn()
                    } else {
                        retries++
                    }
                },
                onLogIn = ::logIn,
            )
        }
    }
}

/** The first step: where the token comes from. A heading and one line, opening the website. */
@Composable
private fun WebsiteCard(enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.secondaryContainer)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.login_token_website),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSecondaryContainer,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.login_token_website_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSecondaryContainer.copy(alpha = 0.8f),
            )
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            painterResource(R.drawable.ic_open_in_new),
            contentDescription = null,
            tint = colors.onSecondaryContainer,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** The token field: only a placeholder shows, but TalkBack still hears what it is for. */
@Composable
private fun TokenField(text: String, onTextChange: (String) -> Unit, enabled: Boolean, problem: String?, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.login_token_field)
    val shape = RoundedCornerShape(28.dp)
    TextField(
        value = text,
        onValueChange = onTextChange,
        enabled = enabled,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.login_token_placeholder)) },
        isError = problem != null,
        // Password, so the keyboard neither suggests nor learns the token.
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        shape = shape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceContainerHigh,
            unfocusedContainerColor = colors.surfaceContainerHigh,
            disabledContainerColor = colors.surfaceContainerHigh,
            errorContainerColor = colors.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (problem != null) Modifier.border(1.5.dp, colors.error, shape) else Modifier)
            .semantics {
                contentDescription = label
                if (problem != null) error(problem)
            },
    )
}

@Composable
private fun OrDivider() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 20.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            stringResource(R.string.login_token_or),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

/**
 * Stays at the bottom edge, above the keyboard: what the token check found, and the button that
 * logs in with it. Pasting and scanning both answer here, next to the button.
 */
@Composable
private fun LoginBar(check: TokenCheck, saving: Boolean, imageLoader: ImageLoader, onRetry: () -> Unit, onLogIn: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
        AnimatedContent(
            targetState = check,
            transitionSpec = { fadeIn() togetherWith fadeOut() using SizeTransform(clip = false) },
            contentKey = { it.javaClass },
            label = "token check",
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) { shown ->
            when (shown) {
                // Holds the row's place, so the button does not move when a result comes in.
                TokenCheck.Empty -> Spacer(Modifier.fillMaxWidth().height(STATUS_ROW_HEIGHT + 12.dp))
                TokenCheck.Checking -> StatusRow {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(AVATAR_SIZE)) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    }
                    Text(
                        stringResource(R.string.login_token_checking),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is TokenCheck.Found -> StatusRow {
                    OwnerAvatar(shown.owner, imageLoader)
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(if (shown.owner.alreadyAdded) R.string.login_token_as_again else R.string.login_token_as),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            shown.owner.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Display names can be in another script, so the login tells two apart.
                        if (!shown.owner.displayName.equals(shown.owner.login, ignoreCase = true)) {
                            Text(
                                "@${shown.owner.login}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                is TokenCheck.Failed -> StatusRow(
                    background = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(24.dp))
                    Text(shown.problem.text(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (!shown.problem.inToken) {
                        TextButton(
                            onClick = onRetry,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                        ) { Text(stringResource(R.string.login_token_retry), style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
        }
        Button(
            onClick = onLogIn,
            enabled = check is TokenCheck.Found && !saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            if (saving) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = LocalContentColor.current, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.login_token_logging_in), style = MaterialTheme.typography.titleSmall)
            } else {
                Text(stringResource(R.string.login_token_confirm), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun StatusRow(
    background: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurface,
    row: @Composable RowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides content) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(background)
                .heightIn(min = STATUS_ROW_HEIGHT)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            content = row,
        )
    }
}

@Composable
private fun OwnerAvatar(owner: TokenOwner, imageLoader: ImageLoader) {
    val shape = Modifier.size(AVATAR_SIZE).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
    if (owner.avatarUrl.isEmpty()) {
        Icon(
            Icons.Default.AccountCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = shape,
        )
    } else {
        AsyncImage(model = owner.avatarUrl, contentDescription = null, imageLoader = imageLoader, modifier = shape)
    }
}

private val AVATAR_SIZE = 44.dp
private val STATUS_ROW_HEIGHT = 68.dp

/** How long typing has to pause before the token is checked, in milliseconds. */
private const val CHECK_DELAY_MS = 400L

/**
 * The login page on the Chatter website. `app` tells it which Chatter to open afterwards, since
 * Nightly installs next to the release with its own login.
 */
private const val TOKEN_PAGE_URL = "https://chatter.lesh.me/token?app=${BuildConfig.APPLICATION_ID}"

/**
 * What is known about the token in the field. [Found.checkedText] is the text that was checked;
 * [Failed.retryLogin] is set when the check passed but the login itself failed.
 */
private sealed interface TokenCheck {
    data object Empty : TokenCheck
    data object Checking : TokenCheck
    data class Found(val owner: TokenOwner, val checkedText: String) : TokenCheck
    data class Failed(val problem: TokenProblem, val retryLogin: Found? = null) : TokenCheck
}

/**
 * Why a token did not go through; resolved to text while drawing, so it follows the language.
 * [inToken]: the token itself is at fault, so the field is marked. Otherwise a second try may work.
 */
private enum class TokenProblem(val inToken: Boolean) {
    NotAToken(true),
    NotATokenCode(true),
    Rejected(true),
    Foreign(true),
    Unreachable(false),
    Other(false),
}

private fun problemOf(e: Throwable?): TokenProblem = when {
    e is HttpException && e.code == 401 -> TokenProblem.Rejected
    e is ForeignTokenException -> TokenProblem.Foreign
    e is IOException -> TokenProblem.Unreachable
    else -> TokenProblem.Other
}

@Composable
private fun TokenProblem.text(): String = tokenLoginError(LocalContext.current, this)

/** Why Twitch did not take a token, for the token login and the login from a link. */
fun tokenLoginError(context: Context, e: Throwable?): String = tokenLoginError(context, problemOf(e))

private fun tokenLoginError(context: Context, problem: TokenProblem): String = when (problem) {
    TokenProblem.NotAToken -> context.getString(R.string.login_token_invalid)
    TokenProblem.NotATokenCode -> context.getString(R.string.login_token_code_invalid)
    TokenProblem.Rejected -> context.getString(R.string.login_token_rejected)
    TokenProblem.Foreign -> context.getString(R.string.login_token_foreign)
    TokenProblem.Unreachable -> context.getString(R.string.login_token_unreachable)
    TokenProblem.Other -> context.getString(R.string.login_token_unexpected)
}
