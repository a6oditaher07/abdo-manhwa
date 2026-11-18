package com.abdo.manhwa.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.abdo.manhwa.ui.theme.AccentPrimary
import com.abdo.manhwa.ui.theme.SurfaceDark

enum class BottomTab { Library, AddManga }

@Composable
fun AbdoBottomNavBar(selected: BottomTab, onSelect: (BottomTab) -> Unit) {
    NavigationBar(containerColor = SurfaceDark) {
        NavigationBarItem(
            selected = selected == BottomTab.Library,
            onClick = { onSelect(BottomTab.Library) },
            icon = { Icon(Icons.Filled.LibraryBooks, contentDescription = "المكتبة") },
            label = { Text("المكتبة") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AccentPrimary,
                selectedTextColor = AccentPrimary,
                indicatorColor = AccentPrimary.copy(alpha = 0.15f)
            )
        )
        NavigationBarItem(
            selected = selected == BottomTab.AddManga,
            onClick = { onSelect(BottomTab.AddManga) },
            icon = { Icon(Icons.Filled.AutoStories, contentDescription = "إضافة مانهوا") },
            label = { Text("إضافة مانهوا") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AccentPrimary,
                selectedTextColor = AccentPrimary,
                indicatorColor = AccentPrimary.copy(alpha = 0.15f)
            )
        )
    }
}
