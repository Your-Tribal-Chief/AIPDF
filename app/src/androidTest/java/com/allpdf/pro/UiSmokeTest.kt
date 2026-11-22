package com.allpdf.pro

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.allpdf.pro.ui.Tool
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launches the app and composes every tool screen to catch runtime/composition crashes. */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun openEveryTool() {
        rule.onNodeWithText("AllPDF Pro").assertExists()
        for (t in Tool.entries) {
            rule.runOnUiThread { rule.activity.nav.openTool(t) }
            rule.waitForIdle()
            val n = rule.onAllNodesWithText(t.title).fetchSemanticsNodes().size
            assertTrue("Screen for ${t.title} did not show its title", n >= 1)
            rule.runOnUiThread { rule.activity.nav.pop() }
            rule.waitForIdle()
        }
        rule.onNodeWithText("Files").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Settings").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Dark").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Tools").performClick(); rule.waitForIdle()
        rule.onNodeWithText("AllPDF Pro").assertExists()
    }
}
