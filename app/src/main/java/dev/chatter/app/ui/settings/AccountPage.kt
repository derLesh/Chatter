package dev.chatter.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.chatter.app.R
import dev.chatter.app.auth.Account
import dev.chatter.app.auth.AuthState
import dev.chatter.app.net.HelixUser
import dev.chatter.app.ui.MainViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Who the app is chatting as, and who else it could be chatting as. Chatter holds several Twitch
 * logins at once — a main account and a bot, two streamers sharing a phone — and only one of them
 * is connected at a time; the rest wait in the row of faces until one is tapped.
 *
 * What is shown about the account comes from two places: the name and the picture are stored with
 * the login and are there offline, the rest is asked of Twitch while the page is open.
 */
@Composable
fun AccountPage(vm: MainViewModel, onAddAccount: () -> Unit) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    // The state, not the account list: switching swaps which account is the active one without
    // changing the list at all, and this page is mostly about which one that is.
    val auth by vm.authState.collectAsStateWithLifecycle()
    val active = (auth as? AuthState.LoggedIn)?.account ?: return
    var profile by remember(active.userId) { mutableStateOf<HelixUser?>(null) }
    var following by remember(active.userId) { mutableStateOf<Int?>(null) }
    var loggingOut by remember { mutableStateOf<Account?>(null) }

    // Everything but the name and the picture is asked for fresh, because an account page is
    // where somebody looks to find out what is true right now.
    LaunchedEffect(active.userId) {
        vm.refreshAccounts()
        profile = vm.ownProfile()
        following = vm.followedChannels()
    }

    ProfileCard(active, profile, following, vm.imageLoader)
    AccountsGroup(
        accounts = accounts,
        active = active,
        imageLoader = vm.imageLoader,
        onSwitch = vm::switchAccount,
        onAdd = onAddAccount,
        onLogout = { loggingOut = it },
    )

    loggingOut?.let { target ->
        val last = accounts.size == 1
        AlertDialog(
            onDismissRequest = { loggingOut = null },
            title = { Text(stringResource(R.string.account_logout_title, target.name)) },
            text = {
                Text(stringResource(if (last) R.string.account_logout_last_text else R.string.account_logout_text))
            },
            confirmButton = {
                TextButton(onClick = { vm.removeAccount(target.userId); loggingOut = null }) {
                    Text(stringResource(R.string.logout))
                }
            },
            dismissButton = {
                TextButton(onClick = { loggingOut = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/**
 * The account in one card: its picture, its name with the Twitch id beside it, and the two
 * things Twitch knows that a chatter cannot see anywhere else — how old the account is and
 * whether it is an affiliate or a partner. Tapping it opens the profile on Twitch; holding it
 * copies the id, which is what a bug report or a supporter claim asks for.
 */
@Composable
private fun ProfileCard(account: Account, profile: HelixUser?, following: Int?, imageLoader: ImageLoader) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val subtitle = listOfNotNull(
        broadcasterType(profile),
        following?.let { pluralStringResource(R.plurals.account_following_summary, it, it) },
    ).joinToString(" · ")

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        val url = "https://twitch.tv/${account.login}"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                    onLongClick = { clipboard.setText(AnnotatedString(account.userId)) },
                    onClickLabel = stringResource(R.string.account_on_twitch),
                    onLongClickLabel = stringResource(R.string.account_copy_id),
                )
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Avatar(account, imageLoader, 72.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        account.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.account_id, account.userId),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                formatDate(profile?.createdAt)?.let { created ->
                    Text(
                        stringResource(R.string.user_created, created),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun broadcasterType(profile: HelixUser?): String? = when (profile?.broadcasterType) {
    "partner" -> stringResource(R.string.user_partner)
    "affiliate" -> stringResource(R.string.user_affiliate)
    else -> null
}

/**
 * Every login the app holds as a row of faces, the way the app icon is picked: which one is in
 * use is a ring around it rather than a word, so switching is one tap on a face the user knows.
 * Holding one logs it out; the row scrolls sideways once there are more than fit.
 */
@Composable
private fun AccountsGroup(
    accounts: List<Account>,
    active: Account,
    imageLoader: ImageLoader,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
    onLogout: (Account) -> Unit,
) {
    SettingsGroup(R.string.settings_group_accounts) {
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                accounts.forEach { account ->
                    AccountFace(
                        account = account,
                        selected = account.userId == active.userId,
                        imageLoader = imageLoader,
                        onSwitch = { onSwitch(account.userId) },
                        onLogout = { onLogout(account) },
                    )
                }
                AddFace(onAdd)
            }
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.logout)) },
                supportingContent = { Text(active.name) },
                leadingContent = { CategoryIcon(Icons.AutoMirrored.Filled.ExitToApp) },
                colors = transparentItem(),
                modifier = Modifier.clickable { onLogout(active) },
            )
        }
    }
}

/** One account in the switcher: the face, a ring when it is the one in use, and its name. */
@Composable
private fun AccountFace(
    account: Account,
    selected: Boolean,
    imageLoader: ImageLoader,
    onSwitch: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = { if (!selected) onSwitch() },
                onLongClick = onLogout,
                onClickLabel = stringResource(R.string.account_switch_to, account.name),
                onLongClickLabel = stringResource(R.string.logout),
            )
            .padding(8.dp)
            .widthIn(max = 88.dp),
    ) {
        Avatar(
            account = account,
            imageLoader = imageLoader,
            size = 64.dp,
            contentDescription = if (selected) stringResource(R.string.account_active) else null,
            modifier = Modifier.border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                CircleShape,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            account.name,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The last face in the row, which is not a face yet: one more Twitch login. */
@Composable
private fun AddFace(onAdd: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onAdd)
            .padding(8.dp)
            .widthIn(max = 88.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.account_add_short),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** An account's Twitch picture, or a stand-in until Twitch has said what it is. */
@Composable
private fun Avatar(
    account: Account,
    imageLoader: ImageLoader,
    size: Dp,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val shape = Modifier
        .size(size)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .then(modifier)
    if (account.avatarUrl.isEmpty()) {
        Box(contentAlignment = Alignment.Center, modifier = shape) {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.7f),
            )
        }
    } else {
        AsyncImage(
            model = account.avatarUrl,
            contentDescription = contentDescription,
            imageLoader = imageLoader,
            modifier = shape,
        )
    }
}

private fun formatDate(iso: String?): String? = iso?.takeIf { it.isNotEmpty() }?.let {
    runCatching {
        Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDate()
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }.getOrNull()
}

/** The row on the settings home, which wears the account's own picture instead of an icon. */
@Composable
fun AccountRowIcon(account: Account?, imageLoader: ImageLoader) {
    if (account == null) CategoryIcon(Icons.Default.AccountCircle)
    else Avatar(account, imageLoader, 40.dp)
}
