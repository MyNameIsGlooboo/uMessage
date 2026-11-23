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
