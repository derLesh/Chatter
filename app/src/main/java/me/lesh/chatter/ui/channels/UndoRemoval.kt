package me.lesh.chatter.ui.channels

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import me.lesh.chatter.R
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.pill.PillContent
import me.lesh.chatter.ui.pill.PillTone
import me.lesh.chatter.ui.pill.showPill
import kotlinx.coroutines.flow.collectLatest

/**
 * Offers undo through [snackbar] for every channel or combined chat removed while this screen is
 * up. The chat and the channel settings can both remove, and only one is on screen. A second
 * removal replaces the first snackbar, since cancelling [SnackbarHostState.showSnackbar] dismisses
 * it.
 */
@Composable
fun OfferUndoRemoval(vm: MainViewModel, snackbar: SnackbarHostState) {
    // Not context.resources, which does not follow a language change.
    val resources = LocalResources.current
    LaunchedEffect(vm, resources) {
        vm.removals.collectLatest { removal ->
            val result = snackbar.showPill(
                PillContent.filled(removal.name, PillTone.Plain) { resources.getString(R.string.page_removed, it) },
                actionLabel = resources.getString(R.string.undo),
            )
            if (result == SnackbarResult.ActionPerformed) vm.undoRemoval(removal)
        }
    }
}
