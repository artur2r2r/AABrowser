/*
 * Copyright (C) 2025 AABrowser Contributors (https://github.com/kododake/AABrowser)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package com.kododake.aabrowser.split

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kododake.aabrowser.R

/** Observable state for the split pane toolbar. */
class SplitPaneToolbarState {
    var title by mutableStateOf("")
    var canGoBack by mutableStateOf(false)
    var isDesktopMode by mutableStateOf(true)
    var progress by mutableFloatStateOf(1f)
}

data class SplitPaneToolbarActions(
    val onBack: () -> Unit,
    val onReload: () -> Unit,
    val onHome: () -> Unit,
    val onEditUrl: () -> Unit,
    val onToggleDesktop: () -> Unit,
    val onSwap: () -> Unit,
    val onClose: () -> Unit
)

@Composable
fun SplitPaneToolbar(
    state: SplitPaneToolbarState,
    actions: SplitPaneToolbarActions
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolbarButton(Icons.AutoMirrored.Rounded.ArrowBack, R.string.split_screen_back, actions.onBack, enabled = state.canGoBack)
            ToolbarButton(Icons.Rounded.Refresh, R.string.split_screen_reload, actions.onReload)
            ToolbarButton(Icons.Rounded.Home, R.string.split_screen_home, actions.onHome)
            // Title shrinks to nothing when the pane is narrow so buttons always fit.
            Text(
                text = state.title,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ToolbarButton(Icons.Rounded.Edit, R.string.split_screen_edit_url, actions.onEditUrl)
            ToolbarButton(
                Icons.Rounded.Computer,
                R.string.split_screen_desktop,
                actions.onToggleDesktop,
                highlighted = state.isDesktopMode
            )
            ToolbarButton(Icons.Rounded.SwapHoriz, R.string.split_screen_swap, actions.onSwap)
            ToolbarButton(Icons.Rounded.Close, R.string.split_screen_close, actions.onClose)
        }
        if (state.progress < 1f) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
            )
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: ImageVector,
    labelRes: Int,
    onClick: () -> Unit,
    enabled: Boolean = true,
    highlighted: Boolean = false
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(labelRes),
            modifier = Modifier.size(22.dp)
        )
    }
}
