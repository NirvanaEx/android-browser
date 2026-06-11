package com.upgrid.browser.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.upgrid.browser.BrowserApplication
import com.upgrid.browser.R
import com.upgrid.browser.databinding.FragmentSettingsBinding
import com.upgrid.browser.prefs.BrowserPreferences
import com.upgrid.browser.search.SearchEngine
import com.upgrid.browser.search.SearchHistory

/**
 * Single-screen settings as a bottom sheet. For now: pick search engine and
 * clear search history. Replace with a full Activity once we have enough
 * settings to need scroll/grouping.
 */
class SettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val app get() = requireActivity().application as BrowserApplication
    private val prefs by lazy { BrowserPreferences(app) }
    private val history by lazy { SearchHistory(app) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        renderEngines()
        renderSeekSteps()
        renderHistorySection()

        binding.btnClearHistory.setOnClickListener {
            history.clear()
            renderHistorySection()
        }
    }

    /**
     * Build a radio button per [SearchEngine] in the order the enum declares.
     * The engine list is short and stable — programmatic inflation is simpler
     * than a RecyclerView for 4 rows.
     */
    private fun renderEngines() {
        val current = prefs.searchEngine
        binding.engineGroup.removeAllViews()
        SearchEngine.entries.forEach { engine ->
            val rb = RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = engine.displayName
                textSize = 15f
                setPadding(
                    paddingLeft + dp(12),
                    dp(10),
                    paddingRight,
                    dp(10),
                )
                isChecked = engine == current
                setOnCheckedChangeListener { _, checked ->
                    if (checked) prefs.searchEngine = engine
                }
            }
            binding.engineGroup.addView(
                rb,
                RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT,
                )
            )
        }
    }

    /**
     * One radio button per seek-step option for the built-in player's
     * double-tap / skip-button seek. Same programmatic pattern as the
     * search-engine picker above.
     */
    private fun renderSeekSteps() {
        val current = prefs.playerSeekSeconds
        binding.seekStepGroup.removeAllViews()
        BrowserPreferences.PLAYER_SEEK_OPTIONS.forEach { seconds ->
            val rb = RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = getString(R.string.settings_seek_option, seconds)
                textSize = 15f
                setPadding(
                    paddingLeft + dp(12),
                    dp(10),
                    paddingRight,
                    dp(10),
                )
                isChecked = seconds == current
                setOnCheckedChangeListener { _, checked ->
                    if (checked) prefs.playerSeekSeconds = seconds
                }
            }
            binding.seekStepGroup.addView(
                rb,
                RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT,
                )
            )
        }
    }

    private fun renderHistorySection() {
        val count = history.recent().size
        binding.historyCount.text = resources.getQuantityString(
            R.plurals.settings_history_count, count, count
        )
        binding.btnClearHistory.isEnabled = count > 0
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
