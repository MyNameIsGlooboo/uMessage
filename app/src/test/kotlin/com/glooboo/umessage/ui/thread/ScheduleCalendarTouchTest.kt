package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.theme.UMessageTheme
import java.time.LocalDate
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** UI-touch: the days of the schedule calendar are at least 48dp high, also in a narrow dialog. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h740dp")
class ScheduleCalendarTouchTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a day cell is a touch target at least 48dp high in a 300dp wide calendar`() {
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                Box(Modifier.width(300.dp)) {
                    ScheduleCalendar(
                        selected = LocalDate.of(2026, 11, 10),
                        minDate = LocalDate.of(2026, 11, 1),
                        jalali = false,
                        locale = Locale.US,
                        onSelect = {},
                    )
                }
            }
        }
        compose.onNode(hasText("15") and hasClickAction()).assertHeightIsAtLeast(48.dp)
    }
}
