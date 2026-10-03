package dev.chatter.app.ui.channels

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import dev.chatter.app.R
import dev.chatter.app.ui.MainViewModel
import kotlinx.coroutines.flow.collectLatest

/**
 * Offers every channel or combined chat removed while this screen is up back through [snackbar].
 *
 * Both the chat and the channel settings can remove one, and only one of them is ever on screen,
 * so whichever it is says so. A second removal replaces the snackbar of the first: cancelling
 * [SnackbarHostState.showSnackbar] takes it away.
 */
@Composable
fun OfferUndoRemoval(vm: MainViewModel, snackbar: SnackbarHostState) {
    // Not context.resources, which does not follow a change of language.
    val resources = LocalResources.current
    LaunchedEffect(vm, resources) {
        vm.removals.collectLatest { removal ->
            val result = snackbar.showSnackbar(
                message = resources.getString(R.string.page_removed, removal.name),
                actionLabel = resources.getString(R.string.undo),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) vm.undoRemoval(removal)
        }
    }
}
