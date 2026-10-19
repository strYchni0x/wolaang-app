package de.wolaang.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import de.wolaang.app.ui.map.MapScreen
import de.wolaang.app.ui.plan.PlanScreen
import de.wolaang.app.ui.record.RecordButton
import de.wolaang.app.ui.record.RecordButtonLabel
import de.wolaang.app.ui.settings.SettingsScreen
import de.wolaang.app.ui.tours.ToursScreen

sealed class Screen(val route: String, val label: String) {
    object Map : Screen("map", "Karte")
    object Tours : Screen("tours", "Touren")
    object Plan : Screen("plan", "Planen")
    object Settings : Screen("settings", "Einstellungen")
}

@Composable
fun WolaangNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    fun navigateTo(screen: Screen) {
        navController.navigate(screen.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    @Composable
    fun RowScope.TabItem(screen: Screen) {
        NavigationBarItem(
            icon = {
                Icon(
                    imageVector = when (screen) {
                        Screen.Map -> Icons.Default.Place
                        Screen.Tours -> Icons.AutoMirrored.Filled.List
                        Screen.Plan -> Icons.Default.Edit
                        Screen.Settings -> Icons.Default.Settings
                    },
                    contentDescription = screen.label
                )
            },
            label = { Text(screen.label) },
            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
            onClick = { navigateTo(screen) }
        )
    }

    Scaffold(
        bottomBar = {
            // The record button sticks out of the bar's middle; the Box doesn't clip it.
            Box {
                NavigationBar {
                    TabItem(Screen.Map)
                    TabItem(Screen.Tours)
                    // Placeholder slot under the raised button, with its label at the bottom.
                    Box(
                        contentAlignment = Alignment.BottomCenter,
                        modifier = Modifier.weight(1f).align(Alignment.Bottom).padding(bottom = 16.dp),
                    ) { RecordButtonLabel() }
                    TabItem(Screen.Plan)
                    TabItem(Screen.Settings)
                }
                RecordButton(
                    onStarted = { navigateTo(Screen.Map) },
                    onStopped = { navigateTo(Screen.Map) },
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = (-22).dp),
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Map.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Map.route) { MapScreen() }
            composable(Screen.Tours.route) { ToursScreen(
                    onOpenPlan = { navigateTo(Screen.Plan) },
                    onOpenMap = { navigateTo(Screen.Map) },
                ) }
            composable(Screen.Plan.route) { PlanScreen(onNavigate = { navigateTo(Screen.Map) }) }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
