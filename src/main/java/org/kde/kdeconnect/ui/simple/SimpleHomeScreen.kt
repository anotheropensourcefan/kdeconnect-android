/*
 * SPDX-FileCopyrightText: 2026
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.simple

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.PairingHandler
import org.kde.kdeconnect.extensions.safeDrawingBottomPadding
import org.kde.kdeconnect.plugins.clipboard.ClipboardPlugin
import org.kde.kdeconnect.plugins.findremotedevice.FindRemoteDevicePlugin
import org.kde.kdeconnect.plugins.mousepad.ComposeSendActivity
import org.kde.kdeconnect.plugins.mousepad.KeyListenerView
import org.kde.kdeconnect.plugins.mousepad.MousePadPlugin
import org.kde.kdeconnect.plugins.mpris.MprisPlugin
import org.kde.kdeconnect.plugins.ping.PingPlugin
import org.kde.kdeconnect.plugins.runcommand.RunCommandPlugin
import org.kde.kdeconnect.plugins.share.SharePlugin
import org.kde.kdeconnect.plugins.systemvolume.Sink
import org.kde.kdeconnect.plugins.systemvolume.SystemVolumePlugin
import org.kde.kdeconnect.plugins.systemvolume.getDefaultSink
import org.kde.kdeconnect.ui.AppSettingsController
import org.kde.kdeconnect.ui.CustomDevicesActivity
import org.kde.kdeconnect.ui.MainActivity
import org.kde.kdeconnect.ui.PluginSettingsActivity
import org.kde.kdeconnect_tp.R
import kotlin.math.roundToInt

/** Position/length use milliseconds on the Android MPRIS side; ±10 seconds. */
private const val MEDIA_SEEK_STEP_MS = 10_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleHomeScreen(
    device: Device?,
) {
    val context = LocalContext.current
    val activity = context as Activity
    val bottomPadding = safeDrawingBottomPadding().calculateBottomPadding()

    val deviceReady = device != null && device.isPaired && device.isReachable
    val mousePadPlugin = if (deviceReady) device.getPlugin<MousePadPlugin>() else null
    val mprisPlugin = if (deviceReady) device.getPlugin<MprisPlugin>() else null
    val systemVolumePlugin = if (deviceReady) device.getPlugin<SystemVolumePlugin>() else null

    var showDevicesSheet by remember { mutableStateOf(false) }
    var showAudioSheet by remember { mutableStateOf(false) }
    var showSendSheet by remember { mutableStateOf(false) }
    var keyboardShown by remember { mutableStateOf(false) }
    var keyListenerView by remember { mutableStateOf<KeyListenerView?>(null) }

    var selectedSinkName by remember { mutableStateOf<String?>(null) }
    var sinks by remember { mutableStateOf<List<Sink>>(emptyList()) }
    var volume by remember { mutableFloatStateOf(0f) }
    var maxVolume by remember { mutableFloatStateOf(100f) }
    var trackingVolume by remember { mutableStateOf(false) }
    var trackingSheetSink by remember { mutableStateOf<String?>(null) }

    var controlledPlayer by remember {
        mutableStateOf(resolveControlledPlayer(mprisPlugin, preferredName = null))
    }
    var preferredPlayerName by remember { mutableStateOf(controlledPlayer?.playerName) }
    var mediaTick by remember { mutableStateOf(0) }

    val currentSelectedSinkName by rememberUpdatedState(selectedSinkName)
    val currentTrackingVolume by rememberUpdatedState(trackingVolume)
    val currentPreferredPlayerName by rememberUpdatedState(preferredPlayerName)
    val currentTrackingSheetSink by rememberUpdatedState(trackingSheetSink)

    DisposableEffect(systemVolumePlugin) {
        val plugin = systemVolumePlugin
        if (plugin == null) {
            sinks = emptyList()
            selectedSinkName = null
            onDispose { }
        } else {
            val sinkUpdateListener = object : Sink.UpdateListener {
                override fun updateSink(sink: Sink) {
                    if (sink.name == currentSelectedSinkName &&
                        !currentTrackingVolume &&
                        currentTrackingSheetSink != sink.name
                    ) {
                        volume = sink.volume.toFloat()
                        maxVolume = sink.maxVolume.toFloat().coerceAtLeast(1f)
                    }
                    // Force list recompose with updated sink fields
                    sinks = plugin.sinks.toList()
                }
            }
            var attachedSinks: List<Sink> = emptyList()
            val listener = object : SystemVolumePlugin.SinkListener {
                override fun sinksChanged() {
                    attachedSinks.forEach { it.removeListener(sinkUpdateListener) }
                    val list = plugin.sinks.toList()
                    sinks = list
                    attachedSinks = list
                    list.forEach { it.addListener(sinkUpdateListener) }
                    val selected = list.firstOrNull { it.name == currentSelectedSinkName }
                        ?: getDefaultSink(plugin)
                        ?: list.firstOrNull()
                    selectedSinkName = selected?.name
                    if (!currentTrackingVolume && selected != null &&
                        currentTrackingSheetSink != selected.name
                    ) {
                        volume = selected.volume.toFloat()
                        maxVolume = selected.maxVolume.toFloat().coerceAtLeast(1f)
                    }
                }
            }
            plugin.addSinkListener(listener)
            plugin.requestSinkList()
            onDispose {
                attachedSinks.forEach { it.removeListener(sinkUpdateListener) }
                plugin.removeSinkListener(listener)
            }
        }
    }

    DisposableEffect(mprisPlugin) {
        val plugin = mprisPlugin
        if (plugin == null) {
            controlledPlayer = null
            onDispose { }
        } else {
            val handlerId = "SimpleHomeScreen"
            val refresh: () -> Unit = {
                val next = resolveControlledPlayer(plugin, currentPreferredPlayerName)
                controlledPlayer = next
                if (next != null) {
                    preferredPlayerName = next.playerName
                }
                mediaTick += 1
            }
            plugin.setPlayerStatusUpdatedHandler(handlerId, refresh)
            plugin.setPlayerListUpdatedHandler(handlerId, refresh)
            onDispose {
                plugin.removePlayerStatusUpdatedHandler(handlerId)
                plugin.removePlayerListUpdatedHandler(handlerId)
            }
        }
    }

    // mediaTick bumps when MprisPlayer fields mutate in place so Compose recomposes
    val mediaPlayerForBar = controlledPlayer.takeIf { mediaTick >= 0 }
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val sendActions = if (deviceReady && device != null) {
        buildSendActions(device, activity, mousePadPlugin)
    } else {
        emptyList()
    }

    val onKeyboardClick = {
        if (deviceReady) {
            val view = keyListenerView
            if (view != null) {
                val imm = context.getSystemService<InputMethodManager>()
                if (keyboardShown) {
                    imm?.hideSoftInputFromWindow(view.windowToken, 0)
                    keyboardShown = false
                } else {
                    view.requestFocus()
                    imm?.showSoftInput(view, 0)
                    keyboardShown = true
                }
            }
        }
    }

    val onFullscreenClick = {
        val plugin = mousePadPlugin
        if (plugin != null) {
            if (mprisPlugin?.playingPlayer != null) {
                plugin.sendText("f")
            } else {
                plugin.sendF11()
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        val rootModifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(bottom = bottomPadding)

        if (isLandscape) {
            // Side icon rail keeps the remote pad tall in short landscape viewports.
            Row(modifier = rootModifier) {
                SimpleKeyListenerHost(device) { keyListenerView = it }
                TopIconRail(
                    deviceReady = deviceReady,
                    keyboardShown = keyboardShown,
                    sendEnabled = sendActions.isNotEmpty(),
                    fullscreenEnabled = mousePadPlugin != null,
                    onDevicesClick = { showDevicesSheet = true },
                    onAudioClick = { if (deviceReady) showAudioSheet = true },
                    onKeyboardClick = onKeyboardClick,
                    onFullscreenClick = onFullscreenClick,
                    onSendClick = { if (sendActions.isNotEmpty()) showSendSheet = true },
                    onSettingsClick = { openSimpleSettings(activity, device?.deviceId) },
                    modifier = Modifier.fillMaxHeight(),
                )
                SimpleHomeBody(
                    device = device,
                    mousePadPlugin = mousePadPlugin,
                    systemVolumePlugin = systemVolumePlugin,
                    selectedSinkName = selectedSinkName,
                    volume = volume,
                    maxVolume = maxVolume,
                    mediaPlayer = mediaPlayerForBar,
                    onTrackingChanged = { trackingVolume = it },
                    onVolumeChange = { newVolume ->
                        volume = newVolume
                        val plugin = systemVolumePlugin
                        val sinkName = selectedSinkName
                        if (plugin != null && sinkName != null) {
                            plugin.sendVolume(sinkName, newVolume.roundToInt())
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        } else {
            Column(modifier = rootModifier) {
                SimpleKeyListenerHost(device) { keyListenerView = it }
                TopIconRow(
                    deviceReady = deviceReady,
                    keyboardShown = keyboardShown,
                    sendEnabled = sendActions.isNotEmpty(),
                    fullscreenEnabled = mousePadPlugin != null,
                    onDevicesClick = { showDevicesSheet = true },
                    onAudioClick = { if (deviceReady) showAudioSheet = true },
                    onKeyboardClick = onKeyboardClick,
                    onFullscreenClick = onFullscreenClick,
                    onSendClick = { if (sendActions.isNotEmpty()) showSendSheet = true },
                    onSettingsClick = { openSimpleSettings(activity, device?.deviceId) },
                )
                SimpleHomeBody(
                    device = device,
                    mousePadPlugin = mousePadPlugin,
                    systemVolumePlugin = systemVolumePlugin,
                    selectedSinkName = selectedSinkName,
                    volume = volume,
                    maxVolume = maxVolume,
                    mediaPlayer = mediaPlayerForBar,
                    onTrackingChanged = { trackingVolume = it },
                    onVolumeChange = { newVolume ->
                        volume = newVolume
                        val plugin = systemVolumePlugin
                        val sinkName = selectedSinkName
                        if (plugin != null && sinkName != null) {
                            plugin.sendVolume(sinkName, newVolume.roundToInt())
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }
    }

    if (showDevicesSheet) {
        DevicesSheet(
            currentDeviceId = device?.deviceId,
            onDismiss = { showDevicesSheet = false },
            onSelectDevice = { deviceId ->
                showDevicesSheet = false
                val selected = KdeConnect.getInstance().getDevice(deviceId)
                if (selected != null && !selected.isPaired) {
                    selected.requestPairing()
                }
                (activity as? MainActivity)?.onDeviceSelected(deviceId, fromDeviceList = true)
            },
            onUnpairDevice = { unpaired ->
                unpaired.unpair()
                if (unpaired.deviceId == device?.deviceId) {
                    showDevicesSheet = false
                    (activity as? MainActivity)?.onDeviceSelected(null)
                }
            },
        )
    }

    if (showSendSheet && deviceReady && device != null) {
        SendActionsSheet(
            actions = buildSendActions(device, activity, mousePadPlugin),
            onDismiss = { showSendSheet = false },
        )
    }

    if (showAudioSheet && systemVolumePlugin != null) {
        AudioSheet(
            sinks = sinks,
            selectedSinkName = selectedSinkName,
            plugin = systemVolumePlugin,
            onDismiss = { showAudioSheet = false },
            onSelectSink = { sink ->
                selectedSinkName = sink.name
                volume = sink.volume.toFloat()
                maxVolume = sink.maxVolume.toFloat().coerceAtLeast(1f)
                systemVolumePlugin.sendEnable(sink.name)
            },
            onSheetTrackingChanged = { sinkName, tracking ->
                trackingSheetSink = if (tracking) sinkName else null
            },
            onSinkVolumeChange = { sink, newVolume ->
                if (sink.name == selectedSinkName) {
                    volume = newVolume.toFloat()
                }
                systemVolumePlugin.sendVolume(sink.name, newVolume)
            },
        )
    }
}

@Composable
private fun StatusMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnpairedStatus(
    device: Device,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when (device.pairStatus) {
                PairingHandler.PairState.Requested,
                PairingHandler.PairState.RequestedByPeer,
                -> stringResource(R.string.pair_requested)
                else -> stringResource(R.string.device_not_paired)
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.pairing_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        when (device.pairStatus) {
            PairingHandler.PairState.RequestedByPeer -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { device.acceptPairing() }) {
                        Text(stringResource(R.string.pairing_accept))
                    }
                    TextButton(onClick = { device.cancelPairing() }) {
                        Text(stringResource(R.string.pairing_reject))
                    }
                }
            }
            PairingHandler.PairState.Requested -> {
                TextButton(onClick = { device.cancelPairing() }) {
                    Text(stringResource(R.string.cancel_pairing))
                }
            }
            else -> {
                TextButton(onClick = { device.requestPairing() }) {
                    Text(stringResource(R.string.request_pairing))
                }
            }
        }
    }
}

@Composable
private fun SimpleKeyListenerHost(
    device: Device?,
    onViewReady: (KeyListenerView) -> Unit,
) {
    if (device == null) return
    AndroidView(
        factory = { ctx ->
            KeyListenerView(ctx).also {
                it.setDeviceId(device.deviceId)
                it.layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
                onViewReady(it)
            }
        },
        update = { it.setDeviceId(device.deviceId) },
        modifier = Modifier.size(1.dp),
    )
}

@Composable
private fun SimpleHomeBody(
    device: Device?,
    mousePadPlugin: MousePadPlugin?,
    systemVolumePlugin: SystemVolumePlugin?,
    selectedSinkName: String?,
    volume: Float,
    maxVolume: Float,
    mediaPlayer: MprisPlugin.MprisPlayer?,
    onTrackingChanged: (Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        device == null -> {
            StatusMessage(
                text = stringResource(R.string.simple_view_select_device),
                modifier = modifier.fillMaxWidth(),
            )
        }
        !device.isPaired -> {
            UnpairedStatus(
                device = device,
                modifier = modifier.fillMaxWidth(),
            )
        }
        !device.isReachable -> {
            StatusMessage(
                text = stringResource(R.string.unreachable_description),
                modifier = modifier.fillMaxWidth(),
            )
        }
        else -> {
            Column(modifier = modifier) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RemotePad(
                        plugin = mousePadPlugin,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    VerticalVolumeSlider(
                        enabled = systemVolumePlugin != null && selectedSinkName != null,
                        volume = volume,
                        maxVolume = maxVolume,
                        onTrackingChanged = onTrackingChanged,
                        onVolumeChange = onVolumeChange,
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight(),
                    )
                }

                mediaPlayer?.let { player ->
                    MediaControlsBar(
                        player = player,
                        isPlaying = player.isPlaying,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TopIconButtons(
    deviceReady: Boolean,
    keyboardShown: Boolean,
    sendEnabled: Boolean,
    fullscreenEnabled: Boolean,
    onDevicesClick: () -> Unit,
    onAudioClick: () -> Unit,
    onKeyboardClick: () -> Unit,
    onFullscreenClick: () -> Unit,
    onSendClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val iconTint = MaterialTheme.colorScheme.onSurface
    val disabledTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    IconButton(onClick = onDevicesClick) {
        Icon(
            painter = painterResource(R.drawable.ic_device_laptop_32dp),
            contentDescription = stringResource(R.string.devices),
            tint = iconTint,
        )
    }
    IconButton(onClick = onAudioClick, enabled = deviceReady) {
        Icon(
            painter = painterResource(R.drawable.ic_volume),
            contentDescription = stringResource(R.string.simple_view_audio_devices),
            tint = if (deviceReady) iconTint else disabledTint,
        )
    }
    IconButton(onClick = onKeyboardClick, enabled = deviceReady) {
        Icon(
            painter = painterResource(
                if (keyboardShown) R.drawable.ic_keyboard_hide_36dp else R.drawable.ic_action_keyboard_24dp
            ),
            contentDescription = stringResource(
                if (keyboardShown) R.string.simple_view_hide_keyboard else R.string.show_keyboard
            ),
            tint = if (deviceReady) iconTint else disabledTint,
        )
    }
    IconButton(onClick = onFullscreenClick, enabled = fullscreenEnabled) {
        Icon(
            painter = painterResource(R.drawable.ic_open_in_full_24dp),
            contentDescription = stringResource(R.string.presenter_fullscreen),
            tint = if (fullscreenEnabled) iconTint else disabledTint,
        )
    }
    IconButton(onClick = onSendClick, enabled = sendEnabled) {
        Icon(
            painter = painterResource(R.drawable.ic_baseline_send_24),
            contentDescription = stringResource(R.string.simple_view_send),
            tint = if (sendEnabled) iconTint else disabledTint,
        )
    }
    IconButton(onClick = onSettingsClick) {
        Icon(
            painter = painterResource(R.drawable.ic_settings_24dp),
            contentDescription = stringResource(R.string.settings),
            tint = iconTint,
        )
    }
}

@Composable
private fun TopIconRow(
    deviceReady: Boolean,
    keyboardShown: Boolean,
    sendEnabled: Boolean,
    fullscreenEnabled: Boolean,
    onDevicesClick: () -> Unit,
    onAudioClick: () -> Unit,
    onKeyboardClick: () -> Unit,
    onFullscreenClick: () -> Unit,
    onSendClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopIconButtons(
            deviceReady = deviceReady,
            keyboardShown = keyboardShown,
            sendEnabled = sendEnabled,
            fullscreenEnabled = fullscreenEnabled,
            onDevicesClick = onDevicesClick,
            onAudioClick = onAudioClick,
            onKeyboardClick = onKeyboardClick,
            onFullscreenClick = onFullscreenClick,
            onSendClick = onSendClick,
            onSettingsClick = onSettingsClick,
        )
    }
}

/** Vertical icon rail for landscape — same actions as [TopIconRow], more pad height. */
@Composable
private fun TopIconRail(
    deviceReady: Boolean,
    keyboardShown: Boolean,
    sendEnabled: Boolean,
    fullscreenEnabled: Boolean,
    onDevicesClick: () -> Unit,
    onAudioClick: () -> Unit,
    onKeyboardClick: () -> Unit,
    onFullscreenClick: () -> Unit,
    onSendClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TopIconButtons(
            deviceReady = deviceReady,
            keyboardShown = keyboardShown,
            sendEnabled = sendEnabled,
            fullscreenEnabled = fullscreenEnabled,
            onDevicesClick = onDevicesClick,
            onAudioClick = onAudioClick,
            onKeyboardClick = onKeyboardClick,
            onFullscreenClick = onFullscreenClick,
            onSendClick = onSendClick,
            onSettingsClick = onSettingsClick,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendActionsSheet(
    actions: List<SendAction>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val iconTint = MaterialTheme.colorScheme.onSurface
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            text = stringResource(R.string.simple_view_send),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(actions, key = { it.label }) { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            action.onClick()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .semantics { role = Role.Button },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(action.iconRes),
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private data class SendAction(
    val label: String,
    val iconRes: Int,
    val onClick: () -> Unit,
)

private fun buildSendActions(
    device: Device,
    activity: Activity,
    mousePadPlugin: MousePadPlugin?,
): List<SendAction> {
    val actions = mutableListOf<SendAction>()
    val context = activity

    device.getPlugin<ClipboardPlugin>()?.let { plugin ->
        val click = plugin.getUiButtons().firstOrNull()?.onClick
            ?: plugin.getUiMenuEntries().firstOrNull()?.onClick
        if (click != null) {
            actions.add(
                SendAction(
                    label = context.getString(R.string.send_clipboard),
                    iconRes = R.drawable.ic_baseline_content_paste_24,
                    onClick = { click(activity) },
                )
            )
        }
    }

    device.getPlugin<SharePlugin>()?.getUiButtons()?.firstOrNull()?.let { button ->
        actions.add(
            SendAction(
                label = button.name,
                iconRes = button.iconRes,
                onClick = { button.onClick(activity) },
            )
        )
    }

    if (mousePadPlugin?.isKeyboardEnabled == true) {
        actions.add(
            SendAction(
                label = context.getString(R.string.open_compose_send),
                iconRes = R.drawable.ic_edit_note_24dp,
                onClick = {
                    val intent = Intent(activity, ComposeSendActivity::class.java)
                    intent.putExtra(ComposeSendActivity.EXTRA_DEVICE_ID, device.deviceId)
                    activity.startActivity(intent)
                },
            )
        )
    }

    device.getPlugin<PingPlugin>()?.getUiMenuEntries()?.firstOrNull()?.let { entry ->
        actions.add(
            SendAction(
                label = entry.name,
                iconRes = R.drawable.ic_baseline_send_24,
                onClick = { entry.onClick(activity) },
            )
        )
    }

    device.getPlugin<FindRemoteDevicePlugin>()?.getUiMenuEntries()?.firstOrNull()?.let { entry ->
        actions.add(
            SendAction(
                label = entry.name,
                iconRes = R.drawable.ic_phonelink_36dp,
                onClick = { entry.onClick(activity) },
            )
        )
    }

    device.getPlugin<RunCommandPlugin>()?.getUiButtons()?.firstOrNull()?.let { button ->
        actions.add(
            SendAction(
                label = button.name,
                iconRes = button.iconRes,
                onClick = { button.onClick(activity) },
            )
        )
    }

    return actions
}

fun openSimpleSettings(activity: Activity, deviceId: String?) {
    val resolvedId = deviceId
        ?: KdeConnect.getInstance().devices.values.firstOrNull { it.isPaired }?.deviceId
        ?: KdeConnect.getInstance().devices.values.firstOrNull()?.deviceId
    if (resolvedId != null) {
        activity.startActivity(PluginSettingsActivity.createIntent(activity, resolvedId))
    } else {
        activity.startActivity(PluginSettingsActivity.createIntent(activity, deviceId = ""))
    }
}

@Composable
private fun VerticalVolumeSlider(
    enabled: Boolean,
    volume: Float,
    maxVolume: Float,
    onTrackingChanged: (Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val activeColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val fraction = if (maxVolume <= 0f) 0f else (volume / maxVolume).coerceIn(0f, 1f)
    var heightPx by remember { mutableFloatStateOf(0f) }

    fun volumeFromY(y: Float): Float {
        if (heightPx <= 0f) return volume
        val ratio = (1f - (y / heightPx)).coerceIn(0f, 1f)
        return ratio * maxVolume
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(trackColor)
            .onSizeChanged { heightPx = it.height.toFloat() }
            .pointerInput(enabled, maxVolume) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = {
                        onTrackingChanged(true)
                        onVolumeChange(volumeFromY(it.y))
                    },
                    onDragEnd = { onTrackingChanged(false) },
                    onDragCancel = { onTrackingChanged(false) },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        onVolumeChange(volumeFromY(change.position.y))
                    },
                )
            }
            .semantics { },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction)
                .background(activeColor.copy(alpha = 0.55f)),
        )
    }
}

/**
 * Prefer the currently playing player; otherwise keep the last controlled one if it still exists,
 * otherwise the first entry in the player list. Returns null when there is nothing to control.
 */
private fun resolveControlledPlayer(
    plugin: MprisPlugin?,
    preferredName: String?,
): MprisPlugin.MprisPlayer? {
    if (plugin == null) return null
    plugin.playingPlayer?.let { return it }
    preferredName?.let { name ->
        plugin.getPlayerStatus(name)?.let { return it }
    }
    val first = plugin.playerList.firstOrNull() ?: return null
    return plugin.getPlayerStatus(first)
}

private fun seekBy(player: MprisPlugin.MprisPlayer, deltaMs: Int) {
    if (!player.isSeekAllowed) return
    val target = (player.position + deltaMs).coerceIn(0L, player.length.coerceAtLeast(0L))
    player.sendSetPosition(target.toInt())
}

@Composable
private fun MediaControlsBar(
    player: MprisPlugin.MprisPlayer,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val iconTint = MaterialTheme.colorScheme.onSurface
    val seekAllowed = player.isSeekAllowed
    Row(
        modifier = modifier.height(56.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { seekBy(player, -MEDIA_SEEK_STEP_MS) },
            enabled = seekAllowed,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_rewind_black),
                contentDescription = stringResource(R.string.mpris_rew),
                tint = if (seekAllowed) iconTint else iconTint.copy(alpha = 0.38f),
            )
        }
        IconButton(onClick = { player.sendPlayPause() }) {
            Icon(
                painter = painterResource(
                    if (isPlaying) R.drawable.ic_pause_black else R.drawable.ic_play_black
                ),
                contentDescription = stringResource(
                    if (isPlaying) R.string.mpris_pause else R.string.mpris_play
                ),
                tint = iconTint,
            )
        }
        IconButton(
            onClick = { seekBy(player, MEDIA_SEEK_STEP_MS) },
            enabled = seekAllowed,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_fast_forward_black),
                contentDescription = stringResource(R.string.mpris_ff),
                tint = if (seekAllowed) iconTint else iconTint.copy(alpha = 0.38f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesSheet(
    currentDeviceId: String?,
    onDismiss: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onUnpairDevice: (Device) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as Activity
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var devices by remember { mutableStateOf(listedDevices()) }
    val iconTint = MaterialTheme.colorScheme.onSurface
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var bluetoothEnabled by remember {
        mutableStateOf(prefs.getBoolean(AppSettingsController.KEY_BLUETOOTH_ENABLED, false))
    }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            results[Manifest.permission.BLUETOOTH_CONNECT] == true &&
            results[Manifest.permission.BLUETOOTH_SCAN] == true
        ) {
            prefs.edit { putBoolean(AppSettingsController.KEY_BLUETOOTH_ENABLED, true) }
            bluetoothEnabled = true
            BackgroundService.ForceRefreshConnections(context)
        }
    }

    fun reloadDeviceLists() {
        devices = listedDevices()
    }

    fun setBluetoothEnabled(enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            val granted = permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            if (!granted) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.location_permission_needed_title)
                    .setMessage(R.string.bluetooth_permission_needed_desc)
                    .setPositiveButton(R.string.ok) { _, _ -> bluetoothPermissionLauncher.launch(permissions) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return
            }
        }
        prefs.edit { putBoolean(AppSettingsController.KEY_BLUETOOTH_ENABLED, enabled) }
        bluetoothEnabled = enabled
        BackgroundService.ForceRefreshConnections(context)
    }

    DisposableEffect(Unit) {
        val callbackKey = "SimpleHomeDevicesSheet"
        KdeConnect.getInstance().addDeviceListChangedCallback(callbackKey) {
            reloadDeviceLists()
        }
        BackgroundService.ForceRefreshConnections(context)
        onDispose {
            KdeConnect.getInstance().removeDeviceListChangedCallback(callbackKey)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.devices),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    context.startActivity(Intent(context, CustomDevicesActivity::class.java))
                },
            ) {
                Text(stringResource(R.string.simple_view_by_ip))
            }
            IconButton(onClick = { setBluetoothEnabled(!bluetoothEnabled) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_bluetooth_24dp),
                    contentDescription = stringResource(R.string.enable_bluetooth),
                    tint = if (bluetoothEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        iconTint.copy(alpha = 0.45f)
                    },
                )
            }
            IconButton(
                onClick = {
                    BackgroundService.ForceRefreshConnections(context)
                    reloadDeviceLists()
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_action_refresh_24dp),
                    contentDescription = stringResource(R.string.refresh),
                    tint = iconTint,
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            if (devices.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.simple_view_no_devices),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(devices, key = { it.deviceId }) { listedDevice ->
                    DeviceSheetRow(
                        device = listedDevice,
                        isCurrent = listedDevice.deviceId == currentDeviceId,
                        onClick = { onSelectDevice(listedDevice.deviceId) },
                        onUnpair = {
                            onUnpairDevice(listedDevice)
                            reloadDeviceLists()
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DeviceSheetRow(
    device: Device,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onUnpair: () -> Unit,
) {
    val iconTint = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(device.iconDrawable),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = iconTint,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = buildString {
                append(device.name)
                if (device.isPaired) append(" ✓")
                if (isCurrent) append(" •")
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (device.isPaired) {
            IconButton(onClick = onUnpair) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.device_menu_unpair),
                    tint = iconTint,
                )
            }
        }
    }
}

/** Reachable unpaired devices plus all paired devices (reachable or not). */
private fun listedDevices(): List<Device> =
    KdeConnect.getInstance().devices.values
        .filter { it.isPaired || it.isReachable }
        .sortedWith(
            compareByDescending<Device> { it.isPaired }
                .thenByDescending { it.isReachable }
                .thenBy { it.name },
        )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioSheet(
    sinks: List<Sink>,
    selectedSinkName: String?,
    plugin: SystemVolumePlugin,
    onDismiss: () -> Unit,
    onSelectSink: (Sink) -> Unit,
    onSheetTrackingChanged: (String, Boolean) -> Unit,
    onSinkVolumeChange: (Sink, Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val iconTint = MaterialTheme.colorScheme.onSurface
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            text = stringResource(R.string.simple_view_audio_devices),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (sinks.isEmpty()) {
            Text(
                text = stringResource(R.string.simple_view_no_audio_sinks),
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn {
                items(sinks, key = { it.name }) { sink ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = sink.description + if (sink.name == selectedSinkName) " ✓" else "",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSink(sink) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .semantics { role = Role.Button },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            IconButton(
                                onClick = { plugin.sendMute(sink.name, !sink.mute) },
                            ) {
                                Icon(
                                    painter = painterResource(
                                        if (sink.mute) R.drawable.ic_volume_mute else R.drawable.ic_volume
                                    ),
                                    contentDescription = stringResource(R.string.mute),
                                    tint = iconTint,
                                )
                            }
                            var localVolume by remember(sink.name, sink.volume) {
                                mutableFloatStateOf(sink.volume.toFloat())
                            }
                            Slider(
                                value = localVolume.coerceIn(0f, sink.maxVolume.toFloat().coerceAtLeast(1f)),
                                onValueChange = { value ->
                                    localVolume = value
                                    onSheetTrackingChanged(sink.name, true)
                                    onSinkVolumeChange(sink, value.roundToInt())
                                },
                                onValueChangeFinished = {
                                    onSheetTrackingChanged(sink.name, false)
                                    onSinkVolumeChange(sink, localVolume.roundToInt())
                                },
                                valueRange = 0f..sink.maxVolume.toFloat().coerceAtLeast(1f),
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Transparent,
                                    disabledThumbColor = Color.Transparent,
                                ),
                                thumb = {},
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
