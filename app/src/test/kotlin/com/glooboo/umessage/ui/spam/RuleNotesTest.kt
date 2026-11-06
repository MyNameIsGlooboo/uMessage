package com.glooboo.umessage.ui.spam

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.ui.main.MainTab
import com.glooboo.umessage.ui.main.MainTabPolicy
import com.glooboo.umessage.helpers.MainTabLinks
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Bug 4: notes the app writes are codes, shown in the app's language; old English and Persian words map to them. */
@RunWith(AndroidJUnit4::class)
class RuleNotesTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun senderBlockingStoresCodesNotWords() {
        assertThat(SenderBlocking.NOTE).isEqualTo(RuleNotes.BLOCKED_BY_YOU)
        assertThat(SenderBlocking.ALLOW_NOTE).isEqualTo(RuleNotes.ALLOWED_BY_YOU)
    }

    @Test
    fun oldEnglishAndPersianNotesMapToTheirCodes() {
        assertThat(RuleNotes.codeOf("Blocked by you")).isEqualTo(RuleNotes.BLOCKED_BY_YOU)
        assertThat(RuleNotes.codeOf(" Allowed by you ")).isEqualTo(RuleNotes.ALLOWED_BY_YOU)
        assertThat(RuleNotes.codeOf("Blocked from message")).isEqualTo(RuleNotes.BLOCKED_FROM_MESSAGE)
        assertThat(RuleNotes.codeOf("مسدودشده از روی پیام")).isEqualTo(RuleNotes.BLOCKED_FROM_MESSAGE)
        assertThat(RuleNotes.codeOf("گزارش‌شده به‌عنوان هرزنامه")).isEqualTo(RuleNotes.REPORTED_SPAM)
        assertThat(RuleNotes.codeOf("From the system block list")).isEqualTo(RuleNotes.SYSTEM_LIST)
        assertThat(RuleNotes.codeOf(RuleNotes.OLD_WORD_LIST)).isEqualTo(RuleNotes.OLD_WORD_LIST)
    }

    @Test
    fun aTypedNoteIsTheUsersOwn() {
        assertThat(RuleNotes.codeOf("My bank")).isNull()
        assertThat(RuleNotes.isAutomatic("")).isFalse()
        assertThat(RuleNotes.display(app.resources, "My bank")).isEqualTo("My bank")
    }

    @Test
    fun codesAndOldWordsAreShownInEnglish() {
        val text = app.getString(R.string.e_note_allowed_by_you)
        assertThat(RuleNotes.display(app.resources, RuleNotes.ALLOWED_BY_YOU)).isEqualTo(text)
        assertThat(RuleNotes.display(app.resources, "Allowed by you")).isEqualTo(text)
    }

    @Test
    @Config(qualifiers = "fa")
    fun oldEnglishNotesAreShownInPersian() {
        val shown = RuleNotes.display(app.resources, "Blocked by you")
        assertThat(shown).isEqualTo(app.getString(R.string.e_note_blocked_by_you))
        assertThat(shown).isNotEqualTo("Blocked by you")
    }

    @Test
    fun searchDoesNotMatchTheCodeOfAnAppNote() {
        val rule = SpamRule(1, ListKind.BLOCK, RuleType.NUMBER, "+447121234567", note = RuleNotes.BLOCKED_BY_YOU)
        assertThat(RuleSearch.matches(rule, "spamless")).isFalse()
        assertThat(RuleSearch.matches(rule.copy(note = "gym"), "gym")).isTrue()
    }

    @Test
    fun theOldBlockedScreenLinkOpensTheBlockedTab() {
        // BR-12: the notification and Settings rows reach the one Blocked page, the tab.
        assertThat(MainTabPolicy.fromLink(MainTabLinks.TAB_BLOCKED)).isEqualTo(MainTab.Blocked)
    }
}
