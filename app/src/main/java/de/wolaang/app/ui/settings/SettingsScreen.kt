package de.wolaang.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.wolaang.app.WolaangApp
import de.wolaang.app.data.ThemeMode

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as WolaangApp).container.settings }
    val themeMode by settings.themeMode.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Design",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Column(modifier = Modifier.selectableGroup()) {
            ThemeOption("Wie System", ThemeMode.SYSTEM, themeMode) { settings.setThemeMode(it) }
            ThemeOption("Hell", ThemeMode.LIGHT, themeMode) { settings.setThemeMode(it) }
            ThemeOption("Dunkel", ThemeMode.DARK, themeMode) { settings.setThemeMode(it) }
        }
    }
}

@Composable
private fun ThemeOption(
    label: String,
    mode: ThemeMode,
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = mode == current, role = Role.RadioButton) { onSelect(mode) }
            .padding(vertical = 8.dp),
    ) {
        RadioButton(selected = mode == current, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}
