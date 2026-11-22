package com.allpdf.pro

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.allpdf.pro.ui.Tool
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launches the app and opens every tool screen to catch composition crashes. */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun openEveryTool() {
        rule.onNodeWithText("AllPDF Pro").assertExists()
        for (t in Tool.entries) {
            rule.onNode(hasScrollAction()).performScrollToNode(hasText(t.title))
            rule.onAllNodesWithText(t.title).onLast().performClick()
            rule.waitForIdle()
            rule.onAllNodesWithContentDescription("Back").onFirst().performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithText("Files").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Settings").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Dark").performClick(); rule.waitForIdle()
    }
}
