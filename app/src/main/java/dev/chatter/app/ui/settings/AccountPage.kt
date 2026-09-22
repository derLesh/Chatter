package dev.chatter.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
 * is connected at a time; the rest sit in the list until one is tapped.
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

    ProfileCard(active, profile, vm.imageLoader)
    DetailsGroup(active, profile, following)
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

/** The account at the top of the page: its picture at full size, its name, and what it wrote. */
@Composable
private fun ProfileCard(account: Account, profile: HelixUser?, imageLoader: ImageLoader) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Avatar(account, imageLoader, 96.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                account.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            // Only where it says something the name does not: "@lesh" under "Lesh" is noise.
            if (!account.login.equals(account.name, ignoreCase = true)) {
                Text(
                    "@${account.login}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            broadcasterType(profile)?.let { type ->
                Spacer(Modifier.height(10.dp))
                Text(
                    type,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            profile?.description?.takeIf { it.isNotBlank() }?.let { bio ->
                Spacer(Modifier.height(16.dp))
                Text(
                    bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun broadcasterType(profile: HelixUser?): String? = when (profile?.broadcasterType) {
    "partner" -> stringResource(R.string.user_partner)
    "affiliate" -> stringResource(R.string.user_affiliate)
    else -> null
}

/** What Twitch knows about the account beyond its name. A row is left out where the answer is. */
@Composable
private fun DetailsGroup(account: Account, profile: HelixUser?, following: Int?) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    SettingsGroup(R.string.settings_group_account_details) {
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.account_id)) },
                supportingContent = { Text(account.userId) },
                leadingContent = { CategoryIcon(Icons.Default.Person) },
                colors = transparentItem(),
                // The id is what a bug report or a supporter claim needs, and nobody types it
                // off a screen without getting a digit wrong.
                modifier = Modifier.clickable { clipboard.setText(AnnotatedString(account.userId)) },
            )
        }
        formatDate(profile?.createdAt)?.let { created ->
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.account_created)) },
                    supportingContent = { Text(created) },
                    leadingContent = { CategoryIcon(Icons.Default.DateRange) },
                    colors = transparentItem(),
                )
            }
        }
        following?.let { count ->
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.account_following)) },
                    supportingContent = { Text(pluralStringResource(R.plurals.account_following_count, count, count)) },
                    leadingContent = { CategoryIcon(Icons.Default.Favorite) },
                    colors = transparentItem(),
                )
            }
        }
        item {
            // Not LinkItem: every other row of this group wears an icon, and one row without
            // one leaves a hole down the left edge.
            val context = LocalContext.current
            val profileUrl = "https://twitch.tv/${account.login}"
            ListItem(
                headlineContent = { Text(stringResource(R.string.account_on_twitch)) },
                supportingContent = { Text("twitch.tv/${account.login}") },
                leadingContent = { CategoryIcon(Icons.Default.AccountBox) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                colors = transparentItem(),
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(profileUrl)))
                },
            )
        }
    }
}

/** Every login the app holds: tap one to chat as it, or add another. */
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
        accounts.forEach { account ->
            val isActive = account.userId == active.userId
            item {
                ListItem(
                    headlineContent = {
                        Text(
                            account.name,
                            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            if (isActive) stringResource(R.string.account_active) else "@${account.login}",
                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = { Avatar(account, imageLoader, 40.dp, checked = isActive) },
                    trailingContent = {
                        IconButton(onClick = { onLogout(account) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = stringResource(R.string.logout),
                            )
                        }
                    },
                    colors = transparentItem(),
                    modifier = Modifier.clickable(enabled = !isActive) { onSwitch(account.userId) },
                )
            }
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.account_add)) },
                supportingContent = { Text(stringResource(R.string.account_add_hint)) },
                leadingContent = { CategoryIcon(Icons.Default.Add) },
                colors = transparentItem(),
                modifier = Modifier.clickable(onClick = onAdd),
            )
        }
    }
}

/**
 * An account's Twitch picture, with a tick over the corner of the one in use — which is what
 * makes the list a switcher rather than a list.
 */
@Composable
private fun Avatar(account: Account, imageLoader: ImageLoader, size: Dp, checked: Boolean = false) {
    Box {
        if (account.avatarUrl.isEmpty()) {
            PlaceholderAvatar(size)
        } else {
            AsyncImage(
                model = account.avatarUrl,
                contentDescription = null,
                imageLoader = imageLoader,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
        if (checked) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.account_active),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/** Stands in until Twitch has said what the account's picture is (a fresh install, or offline). */
@Composable
private fun PlaceholderAvatar(size: Dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Icon(
            Icons.Default.AccountCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.7f),
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
