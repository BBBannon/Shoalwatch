package app.shoalwatch.ui.nav

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * A plain `ViewModel` built from [create] (which reads what it needs from `AppGraph`), scoped to the current
 * navigation entry, so it is cleared when the screen is popped. No DI framework.
 */
@Composable
internal inline fun <reified VM : ViewModel> graphViewModel(key: String? = null, crossinline create: () -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer { create() } })

/**
 * Like [graphViewModel], for a screen that keeps a few small things (an id, a flag) across the app being recreated: [create]
 * gets the entry's `SavedStateHandle`. Never put an item, a name or anything secret in it.
 */
@Composable
internal inline fun <reified VM : ViewModel> graphStateViewModel(key: String? = null, crossinline create: (SavedStateHandle) -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer { create(createSavedStateHandle()) } })
