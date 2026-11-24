/*
 * SPDX-FileCopyrightText: 2026
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.simple

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

object SimpleViewPreferences {
    const val KEY_SIMPLE_VIEW_ENABLED = "simple_view_enabled"
    const val KEY_SHOW_PERSISTENT_NOTIFICATION = "simple_show_persistent_notification"
    const val KEY_SHOW_ALERT_NOTIFICATIONS = "simple_show_alert_notifications"

    fun isSimpleViewEnabled(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_SIMPLE_VIEW_ENABLED, false)
    }

    fun setSimpleViewEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(KEY_SIMPLE_VIEW_ENABLED, enabled)
        }
    }

    /** Default ON — matches classic always-on status notification behavior. */
    fun isShowPersistentNotification(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_SHOW_PERSISTENT_NOTIFICATION, true)
    }

    fun setShowPersistentNotification(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(KEY_SHOW_PERSISTENT_NOTIFICATION, enabled)
        }
    }

    /** Default ON — keep file/pair/ping and other event notifications. */
    fun isShowAlertNotifications(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_SHOW_ALERT_NOTIFICATIONS, true)
    }

    fun setShowAlertNotifications(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(KEY_SHOW_ALERT_NOTIFICATIONS, enabled)
        }
    }
}
