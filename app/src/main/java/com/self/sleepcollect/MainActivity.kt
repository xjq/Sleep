package com.self.sleepcollect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.self.sleepcollect.ui.HomeScreen
import com.self.sleepcollect.ui.HomeViewModel
import com.self.sleepcollect.ui.theme.MiSleepTheme

class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels { HomeViewModelFactory(application as MiSleepApp) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiSleepTheme {
                HomeScreen(viewModel = homeViewModel)
            }
        }
    }
}

private class HomeViewModelFactory(
    private val app: MiSleepApp,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            return HomeViewModel(app, app.repository, app.preferences) as T
        }
        throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    }
}
