package com.quickssh.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.res.painterResource
import com.quickssh.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSshBottomBar(
    selectedTab: String,
    activeSessionCount: Int,
    activeTunnelCount: Int = 0,
    onHomeClicked: () -> Unit,
    onSessionsClicked: () -> Unit,
    onTransferClicked: () -> Unit,
    onTunnelsClicked: () -> Unit = {},
    onSettingsClicked: () -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
    ) {
        QuickSshNavItem(
            selected = selectedTab == "LIST",
            onClick = onHomeClicked,
            icon = Icons.Default.Home,
            contentDescription = LocalQuickSshLanguage.current.text("主机", "Hosts"),
            label = LocalQuickSshLanguage.current.text("主机", "Hosts")
        )
        val sessionsInteractionSource = remember { MutableInteractionSource() }
        val sessionsPressed by sessionsInteractionSource.collectIsPressedAsState()
        val sessionsScale by animateFloatAsState(
            targetValue = if (sessionsPressed) 0.92f else if (selectedTab == "SESSIONS") 1.06f else 1f,
            label = "bottomSessionsPressScale"
        )
        NavigationBarItem(
            selected = selectedTab == "SESSIONS",
            onClick = onSessionsClicked,
            interactionSource = sessionsInteractionSource,
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            icon = {
                BadgedBox(
                    modifier = Modifier.scale(sessionsScale),
                    badge = {
                        if (activeSessionCount > 0) {
                            Badge { Text(activeSessionCount.toString()) }
                        }
                    }
                ) {
                    Icon(imageVector = Icons.Default.List, contentDescription = LocalQuickSshLanguage.current.text("会话", "Sessions"))
                }
            },
            label = { Text(LocalQuickSshLanguage.current.text("会话", "Sessions")) }
        )
        QuickSshNavItem(
            selected = selectedTab == "TRANSFER",
            onClick = onTransferClicked,
            icon = Icons.Default.Send,
            contentDescription = LocalQuickSshLanguage.current.text("传输", "Transfers"),
            label = LocalQuickSshLanguage.current.text("传输", "Transfers")
        )
        val tunnelsInteractionSource = remember { MutableInteractionSource() }
        val tunnelsPressed by tunnelsInteractionSource.collectIsPressedAsState()
        val tunnelsScale by animateFloatAsState(
            targetValue = if (tunnelsPressed) 0.92f else if (selectedTab == "TUNNELS") 1.06f else 1f,
            label = "bottomTunnelsPressScale"
        )
        NavigationBarItem(
            selected = selectedTab == "TUNNELS",
            onClick = onTunnelsClicked,
            interactionSource = tunnelsInteractionSource,
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            icon = {
                BadgedBox(
                    modifier = Modifier.scale(tunnelsScale),
                    badge = {
                        if (activeTunnelCount > 0) {
                            Badge { Text(activeTunnelCount.toString()) }
                        }
                    }
                ) {
                    Icon(painter = painterResource(R.drawable.ic_link), contentDescription = LocalQuickSshLanguage.current.text("隧道", "Tunnels"))
                }
            },
            label = { Text(LocalQuickSshLanguage.current.text("隧道", "Tunnels")) }
        )
        QuickSshNavItem(
            selected = selectedTab == "SETTINGS",
            onClick = onSettingsClicked,
            icon = Icons.Default.Settings,
            contentDescription = LocalQuickSshLanguage.current.text("设置", "Settings"),
            label = LocalQuickSshLanguage.current.text("设置", "Settings")
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowScope.QuickSshNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    label: String
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val iconScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else if (selected) 1.06f else 1f,
        label = "bottomNavPressScale"
    )
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        interactionSource = interactionSource,
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.scale(iconScale)
            )
        },
        label = { Text(label) }
    )
}
