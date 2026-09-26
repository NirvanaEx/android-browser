package org.mozilla.fenix.upgrid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.translate.Language
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.theme.Theme
import org.mozilla.fenix.translations.TranslationDialogBottomSheet
import org.mozilla.fenix.translations.TranslationsDialogState
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpgridTranslationSheetTest {
    @get:Rule val rule = createComposeRule()
    private val ru = Language("ru", "Русский")
    private val de = Language("de", "Немецкий")
    private val en = Language("en", "Английский")
    private lateinit var controller: UpgridTranslations
    private var state by mutableStateOf(TranslationsDialogState(initialFrom = en, initialTo = ru,
        fromLanguages = listOf(en, de, ru), toLanguages = listOf(en, de, ru)))
    private var translations = 0
    private var originals = 0
    private lateinit var rootView: android.view.View

    @Before fun setup() {
        testContext.getSharedPreferences("upgrid_translation", 0).edit().clear().commit()
        controller = UpgridTranslations(testContext, BrowserStore())
    }

    private fun show(theme: Theme = Theme.Dark) {
        rule.setContent {
            rootView = LocalView.current.rootView
            FirefoxTheme(theme) {
                TranslationDialogBottomSheet(onRequestDismiss = {}) {
                    UpgridTranslationSheet(state, "", true, false, {}, {},
                        { translations++ }, { originals++ },
                        { state = state.copy(initialFrom = it) }, { state = state.copy(initialTo = it) }, controller)
                }
            }
        }
    }

    @Test fun `translation uses detected source without opening a source picker`() {
        show()
        rule.onNodeWithText("Авто · Английский").assertExists()
        rule.onNodeWithText("Перевести").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, translations)
        assertEquals(ru, controller.target(listOf(en, ru, de)))
        proof("translation-dark")
    }

    @Test fun `destination is searchable by native name and remembered`() {
        show(Theme.Light)
        rule.onNodeWithText("Русский").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Deutsch")
        rule.onNodeWithText("Немецкий").assertIsDisplayed()
        proof("translation-language-search")
        rule.onNodeWithText("Немецкий").performClick()
        rule.onNodeWithText("Язык страницы определяется автоматически").assertExists()
        assertEquals(de, controller.target(listOf(en, ru, de)))
        assertEquals(de, state.initialTo)
        rule.onNodeWithText("Немецкий").assertExists()
    }

    @Test fun `automatic switch persists and unavailable destination disables translation`() {
        state = state.copy(initialTo = null)
        show()
        rule.onNodeWithText("Перевести").performScrollTo().assertIsNotEnabled()
        rule.onNode(isToggleable()).performClick()
        assertTrue(UpgridTranslations(testContext, BrowserStore()).automatic)
    }

    @Test fun `original remains available after a completed translation`() {
        state = state.copy(isTranslated = true)
        show(Theme.Light)
        rule.onNodeWithText("Показать оригинал").performScrollTo().performClick()
        assertEquals(1, originals)
    }

    private fun proof(name: String) {
        val directory = System.getenv("UPGRID_UI_PROOF_DIR") ?: return
        val file = java.io.File(directory, "$name.png").apply { parentFile?.mkdirs() }
        rule.runOnIdle {
            val bitmap = android.graphics.Bitmap.createBitmap(rootView.width, rootView.height, android.graphics.Bitmap.Config.ARGB_8888)
            rootView.draw(android.graphics.Canvas(bitmap))
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
