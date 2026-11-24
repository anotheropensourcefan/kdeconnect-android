/*
 * SPDX-FileCopyrightText: 2026
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.simple

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.kde.kdeconnect_tp.R

/** Content tabs driven by the first four simple-settings top-bar icons (Home is not a tab). */
enum class SimpleSettingsTab {
    PLUGINS,
    PERMISSIONS,
    ABOUT,
    SWITCH,
}

@Composable
fun SimpleSettingsTopBar(
    selectedTab: SimpleSettingsTab,
    onTabSelected: (SimpleSettingsTab) -> Unit,
    onHomeClick: () -> Unit,
) {
    // Match SimpleHomeScreen: status-bar insets on the column, then SpaceEvenly icon row.
    val idleTint = MaterialTheme.colorScheme.onSurface
    val selectedTint = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabIconButton(
                selected = selectedTab == SimpleSettingsTab.PLUGINS,
                selectedTint = selectedTint,
                idleTint = idleTint,
                drawableRes = R.drawable.touchpad_plugin_action_24dp,
                contentDescription = stringResource(R.string.device_menu_plugins),
                onClick = { onTabSelected(SimpleSettingsTab.PLUGINS) },
            )
            TabIconButton(
                selected = selectedTab == SimpleSettingsTab.PERMISSIONS,
                selectedTint = selectedTint,
                idleTint = idleTint,
                drawableRes = R.drawable.ic_warning,
                contentDescription = stringResource(R.string.simple_view_permissions_section),
                onClick = { onTabSelected(SimpleSettingsTab.PERMISSIONS) },
            )
            TabIconButton(
                selected = selectedTab == SimpleSettingsTab.ABOUT,
                selectedTint = selectedTint,
                idleTint = idleTint,
                drawableRes = R.drawable.ic_baseline_info_24,
                contentDescription = stringResource(R.string.about),
                onClick = { onTabSelected(SimpleSettingsTab.ABOUT) },
            )
            TabIconButton(
                selected = selectedTab == SimpleSettingsTab.SWITCH,
                selectedTint = selectedTint,
                idleTint = idleTint,
                drawableRes = R.drawable.ic_open_in_full_24dp,
                contentDescription = stringResource(R.string.simple_view_switch_to_classic),
                onClick = { onTabSelected(SimpleSettingsTab.SWITCH) },
            )
            IconButton(onClick = onHomeClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_home_black_24dp),
                    contentDescription = stringResource(R.string.simple_view_home),
                    tint = idleTint,
                )
            }
        }
    }
}

@Composable
private fun TabIconButton(
    selected: Boolean,
    selectedTint: Color,
    idleTint: Color,
    drawableRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(drawableRes),
            contentDescription = contentDescription,
            tint = if (selected) selectedTint else idleTint,
        )
    }
}
