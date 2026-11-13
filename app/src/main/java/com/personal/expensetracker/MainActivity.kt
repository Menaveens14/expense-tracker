package com.personal.expensetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncEngine.schedule(this)
        setContent {
            val vm: Vm = viewModel()
            AppTheme(vm.prefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    NavHost(nav, "home") {
                        composable("home") { Home(vm, nav) }
                        composable("all") { All(vm, nav) }
                        composable("stats") { Stats(vm, nav) }
                        composable("settings") { SettingsScreen(vm, nav) }
                        composable("appearance") { Appearance(vm, nav) }
                        composable("sync") { SyncScreen(vm, nav) }
                        composable(
                            "edit?id={id}",
                            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })
                        ) { Edit(vm, nav, it.arguments?.getString("id")) }
                    }
                }
            }
        }
    }
}
