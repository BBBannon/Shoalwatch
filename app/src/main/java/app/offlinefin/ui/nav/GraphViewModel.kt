package app.offlinefin.ui.nav

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
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
