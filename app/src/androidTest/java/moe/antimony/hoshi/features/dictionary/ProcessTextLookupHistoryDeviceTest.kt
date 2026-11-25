package moe.antimony.hoshi.features.dictionary

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Actual external Activity, with only its history owner replaced by an isolated store. */
class ProcessTextLookupHistoryDeviceTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun externalActionsRecordOriginalTextAndRecreationDoesNotMoveItAgain() = withHistory { history ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sentence = "猫のいる文章。\n次の行。"
        listOf(Intent.ACTION_PROCESS_TEXT, Intent.ACTION_SEND, "android.intent.action.TRANSLATE", Intent.ACTION_VIEW).forEach { action ->
            val intent = Intent(context, ProcessTextLookupActivity::class.java).apply {
                this.action = action
                type = "text/plain"
                if (action == Intent.ACTION_VIEW) {
                    data = android.net.Uri.parse("hoshi://search?text=" + android.net.Uri.encode(" $sentence "))
                } else {
                    putExtra(if (action == Intent.ACTION_PROCESS_TEXT) Intent.EXTRA_PROCESS_TEXT else Intent.EXTRA_TEXT, " $sentence ")
                }
            }
            ActivityScenario.launch<ProcessTextLookupActivity>(intent).use { scenario ->
                assertEquals(sentence, history.state.value.entries.first())
                history.record("newer entry")
                scenario.recreate()
                assertEquals(listOf("newer entry", sentence), history.state.value.entries)
            }
        }
    }

    @Test fun externalOverlayDisplaysHistoryOperationError() = withHistory(showError = true) { _ ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, ProcessTextLookupActivity::class.java).apply {
            action = Intent.ACTION_PROCESS_TEXT
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "猫")
        }
        ActivityScenario.launch<ProcessTextLookupActivity>(intent).use {
            composeRule.waitForIdle()
            composeRule.onNodeWithText("history fixture denied", substring = true).assertIsDisplayed()
        }
    }

    private fun withHistory(showError: Boolean = false, test: (DictionaryLookupHistoryRepository) -> Unit) {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val history = DictionaryLookupHistoryRepository(DictionaryHistoryDeviceStore(), scope, Dispatchers.Unconfined)
        // Repository failure/diagnostic behavior is tested on the JVM. Supply its UI
        // state here without adding synthetic failures to installed runtime diagnostics.
        val activityHistory = if (showError) object : DictionaryLookupHistory by history {
            override val state = MutableStateFlow(DictionaryLookupHistoryState(
                isLoaded = true, operationError = UiText.Literal("history fixture denied"),
            ))
        } else history
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is ProcessTextLookupActivity) return
                // Hilt injection has completed in super.onCreate; the Activity has not yet
                // read its request or set Compose content. No production history is touched.
                val original = activity.dependencies
                activity.dependencies = ProcessTextLookupDependencies(
                    original.audioRequestHandler, original.readerSettingsRepository,
                    original.dictionaryRepository, original.dictionarySettingsRepository,
                    original.audioSettingsRepository, original.readerFontManager,
                    original.profileRepository, activityHistory,
                )
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        try { test(history) } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            scope.cancel()
        }
    }
}
