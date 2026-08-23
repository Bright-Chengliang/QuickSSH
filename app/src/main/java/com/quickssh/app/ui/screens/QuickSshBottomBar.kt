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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
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
    NavigationBar {
        QuickSshNavItem(
            selected = selectedTab == "LIST",
            onClick = onHomeClicked,
            icon = Icons.Default.Home,
            contentDescription = "主页",
            label = "主页"
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
            icon = {
                BadgedBox(
                    modifier = Modifier.scale(sessionsScale),
                    badge = {
                        if (activeSessionCount > 0) {
                            Badge { Text(activeSessionCount.toString()) }
                        }
                    }
                ) {
                    Icon(imageVector = Icons.Default.List, contentDescription = "后台")
                }
            },
            label = { Text("后台") }
        )
        QuickSshNavItem(
            selected = selectedTab == "TRANSFER",
            onClick = onTransferClicked,
            icon = Icons.Default.Send,
            contentDescription = "传输",
            label = "传输"
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
            icon = {
                BadgedBox(
                    modifier = Modifier.scale(tunnelsScale),
                    badge = {
                        if (activeTunnelCount > 0) {
                            Badge { Text(activeTunnelCount.toString()) }
                        }
                    }
                ) {
                    Icon(painter = painterResource(R.drawable.ic_link), contentDescription = "隧道")
                }
            },
            label = { Text("隧道") }
        )
        QuickSshNavItem(
            selected = selectedTab == "SETTINGS",
            onClick = onSettingsClicked,
            icon = Icons.Default.Settings,
            contentDescription = "设置",
            label = "设置"
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
