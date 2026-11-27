package no.webeasy.app

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
internal fun PrimaryNavigation(rail: Boolean, selectedTab: Int, onSelect: (Int) -> Unit) {
    val destinations = listOf(
        "Pages" to R.drawable.nav_pages,
        "News" to R.drawable.nav_news,
        "Settings" to R.drawable.nav_settings,
        "Changes" to R.drawable.nav_changes,
        "Hosting" to R.drawable.nav_hosting,
    )
    val icon: @Composable (Int) -> Unit = { resource ->
        Icon(painterResource(resource), contentDescription = null, modifier = Modifier.size(if (rail) 32.dp else 24.dp))
    }
    if (rail) {
        NavigationRail(Modifier.fillMaxHeight()) {
            destinations.forEachIndexed { index, (name, resource) ->
                NavigationRailItem(selected = selectedTab == index, onClick = { onSelect(index) },
                    icon = { icon(resource) }, label = { Text(ui(name)) })
            }
        }
    } else {
        NavigationBar {
            destinations.forEachIndexed { index, (name, resource) ->
                NavigationBarItem(selected = selectedTab == index, onClick = { onSelect(index) },
                    icon = { icon(resource) }, label = { Text(ui(name)) })
            }
        }
    }
}
