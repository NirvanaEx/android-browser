package org.mozilla.fenix.upgrid

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.View
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.databinding.UpgridViewFullscreenControlsBinding
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpgridPlayerControlsTest {
    @Test fun `invalid media values cannot close the player while seeking`() {
        withControls { controls, binding ->
            controls.renderState(JSONObject().put("pos", JSONObject.NULL).put("dur", 100))
            assertEquals(0, binding.fsSeek.progress)
            assertTrue(binding.fsSeek.isEnabled)

            controls.renderState(JSONObject().put("pos", "NaN").put("dur", "Infinity"))
            assertEquals(0, binding.fsSeek.progress)
            assertFalse(binding.fsSeek.isEnabled)

            controls.renderState(JSONObject().put("pos", 250).put("dur", 100))
            assertEquals(1000, binding.fsSeek.progress)
            assertTrue(binding.fsSeek.isEnabled)
            controls.renderState(JSONObject().put("pos", -1).put("dur", -1))
            assertEquals(0, binding.fsSeek.progress)
            assertFalse(binding.fsSeek.isEnabled)
        }
    }

    @Test fun `portrait controls follow the video and return to the bottom after rotation or scale change`() {
        withControls { controls, binding ->
            fun layout(width: Int, height: Int) {
                binding.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                binding.root.layout(0, 0, width, height)
            }
            val state = JSONObject().put("pos", 10).put("dur", 100)
                .put("videoWidth", 1920).put("videoHeight", 1080).put("scale", "contain")
            layout(1080, 1920)
            controls.renderState(state)
            assertEquals(1264f, binding.fsBottomBar.y, 1f)

            controls.renderState(state.put("scale", "cover"))
            assertEquals(0f, binding.fsBottomBar.translationY, 0f)
            controls.renderState(state.put("scale", "contain"))
            layout(1920, 1080)
            assertEquals(0f, binding.fsBottomBar.translationY, 0f)

            layout(1080, 1920)
            controls.renderState(state)
            assertTrue(binding.fsBottomBar.translationY < 0f)
            controls.setVisible(false)
            assertEquals(0f, binding.fsBottomBar.translationY, 0f)
        }
    }

    @Test fun `tall and unknown videos keep controls visible within the viewport`() {
        assertEquals(1770, playerControlsTop(1080, 1920, 1080.0, 1920.0, 150, true))
        assertEquals(1770, playerControlsTop(1080, 1920, Double.NaN, 1080.0, 150, true))
        assertEquals(1770, playerControlsTop(1080, 1920, 1920.0, Double.POSITIVE_INFINITY, 150, true))
        assertEquals(1770, playerControlsTop(1080, 1920, 0.0, 0.0, 150, true))
        assertEquals(0, playerControlsTop(0, 0, 1920.0, 1080.0, 150, true))
    }

    private fun withControls(block: (PlayerOverlayController, UpgridViewFullscreenControlsBinding) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val binding = UpgridViewFullscreenControlsBinding.inflate(activity.layoutInflater)
        val controls = PlayerOverlayController(binding, mockk(relaxed = true), { 5 }, activity.window,
            activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
            onExit = {}, onPip = {}, onRotate = {})
        try {
            controls.setVisible(true)
            block(controls, binding)
        } finally {
            controls.dispose()
            activity.finish()
        }
    }
}
