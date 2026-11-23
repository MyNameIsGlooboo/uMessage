package com.glooboo.umessage.ui.setup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.ChannelIssue
import com.glooboo.umessage.helpers.ChannelProblem
import com.glooboo.umessage.helpers.NOTIFICATION_CHANNEL_ID
import com.glooboo.umessage.ui.main.NotificationsBlock
import com.glooboo.umessage.ui.onboarding.permissionItems
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** N7 and R7: the notifications row names a quiet channel from the problem the page read once. */
@RunWith(AndroidJUnit4::class)
class SetupRowsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val actions = SetupActions(requestPermissions = {}, requestDefaultSms = {}, openNotifications = {})

    private fun notificationsNote(issue: ChannelIssue): String? {
        val live = SetupLive(
            items = emptyList(),
            isDefault = true,
            roleDenied = false,
            block = NotificationsBlock.CHANNEL,
            denied = emptySet(),
            channelProblem = ChannelProblem(NOTIFICATION_CHANNEL_ID, CHANNEL_NAME, issue),
        )
        return buildSetupRows(app, live, actions).last().note
    }

    @Test
    fun aQuietChannelIsNamedInTheNote() {
        assertThat(notificationsNote(ChannelIssue.QUIET))
            .isEqualTo(app.getString(R.string.notifications_quiet_body, CHANNEL_NAME))
    }

    @Test
    fun aBlockedChannelHasNoNote() {
        assertThat(notificationsNote(ChannelIssue.BLOCKED)).isNull()
    }

    @Test
    fun `J23 the SMS row without the role starts the role request`() {
        var role = 0
        var perms = 0
        val live = SetupLive(
            items = permissionItems(),
            isDefault = false,
            roleDenied = false,
            block = null,
            denied = emptySet(),
            channelProblem = null,
        )
        val rows = buildSetupRows(
            app,
            live,
            SetupActions(requestPermissions = { perms++ }, requestDefaultSms = { role++ }, openNotifications = {}),
        )
        val sms = rows.first { it.title == app.getString(R.string.onb_perm_sms) }
        sms.onAction()
        assertThat(role).isEqualTo(1)
        assertThat(perms).isEqualTo(0)
    }

    private fun smsRow(isDefault: Boolean, roleDenied: Boolean = false, roleAvailable: Boolean = true): SetupRowUi {
        val shadow = org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.role.RoleManager::class.java))
        // A fresh Robolectric phone has no roles: the SMS role exists only when the test adds it.
        if (roleAvailable) shadow.addAvailableRole(android.app.role.RoleManager.ROLE_SMS)
        val live = SetupLive(
            items = permissionItems(),
            isDefault = isDefault,
            roleDenied = roleDenied,
            block = null,
            denied = emptySet(),
            channelProblem = null,
        )
        return buildSetupRows(app, live, actions).first { it.title == app.getString(R.string.onb_perm_sms) }
    }

    @Test
    fun `J23 the SMS row is unavailable when the phone has no SMS role`() {
        assertThat(smsRow(isDefault = false, roleAvailable = false).unavailable).isTrue()
        assertThat(smsRow(isDefault = false).unavailable).isFalse()
    }

    @Test
    fun `J23 a refused role gives the SMS row Open settings and no duplicate note`() {
        val row = smsRow(isDefault = false, roleDenied = true)
        assertThat(row.actionRes).isEqualTo(R.string.onb_perm_open_settings)
        assertThat(row.note).isNull()
    }

    @Test
    fun `J23 the SMS row with the role asks for the permissions`() {
        var role = 0
        var perms = 0
        val live = SetupLive(
            items = permissionItems(),
            isDefault = true,
            roleDenied = false,
            block = null,
            denied = emptySet(),
            channelProblem = null,
        )
        val rows = buildSetupRows(
            app,
            live,
            SetupActions(requestPermissions = { perms++ }, requestDefaultSms = { role++ }, openNotifications = {}),
        )
        rows.first { it.title == app.getString(R.string.onb_perm_sms) }.onAction()
        assertThat(role).isEqualTo(0)
        assertThat(perms).isEqualTo(1)
    }

    private companion object {
        const val CHANNEL_NAME = "Received messages"
    }
}
