package com.ailife.clox.settings.fragments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SeekBarPreference
import com.ailife.clox.R
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.icon.IconPackLoader
import java.io.File
import java.io.FileOutputStream

class AppListSettingsFragment : BasePreferenceFragment() {

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val ctx = requireContext()
        try {
            val src = ctx.contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalStateException("Could not decode image")

            // Center-crop to screen dimensions so it's ready to display without distortion
            val dm = resources.displayMetrics
            val vw = dm.widthPixels; val vh = dm.heightPixels
            val scale = maxOf(vw.toFloat() / src.width, vh.toFloat() / src.height)
            val sw = (src.width * scale).toInt(); val sh = (src.height * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
            val cropped = Bitmap.createBitmap(scaled, (sw - vw) / 2, (sh - vh) / 2, vw, vh)

            val dir = File(ctx.filesDir, "backgrounds").apply { mkdirs() }
            val dest = File(dir, "app_list_bg.jpg")
            FileOutputStream(dest).use { cropped.compress(Bitmap.CompressFormat.JPEG, 90, it) }

            AppListPrefs.saveBgImagePath(ctx, dest.absolutePath)
            updateBgPrefs()
        } catch (e: Exception) {
            android.util.Log.e("CloX/BgImage", "Failed to copy bg image: $e")
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = AppListPrefs.PREFS_NAME
        setPreferencesFromResource(R.xml.preferences_app_list, rootKey)

        val currentStyle = AppListPrefs.style(requireContext())
        updateVisibility(currentStyle)
        updateSortHint(AppListPrefs.sort(requireContext()))
        populateIconPacks()
        updateBgPrefs()

        findPreference<ListPreference>("style")?.setOnPreferenceChangeListener { _, newValue ->
            updateVisibility(newValue as String); true
        }
        findPreference<ListPreference>("sort")?.setOnPreferenceChangeListener { _, newValue ->
            updateSortHint(newValue as String); true
        }
        findPreference<Preference>("bg_image_pick")?.setOnPreferenceClickListener {
            pickImage.launch("image/*"); true
        }
        findPreference<Preference>("bg_image_clear")?.setOnPreferenceClickListener {
            AppListPrefs.saveBgImagePath(requireContext(), null)
            updateBgPrefs(); true
        }
    }

    // ── Background image state ────────────────────────────────────────────────

    private fun updateBgPrefs() {
        val hasImage = AppListPrefs.bgImagePath(requireContext()) != null
        findPreference<Preference>("bg_image_pick")?.summary =
            if (hasImage) "Image set — tap to replace" else "Tap to choose an image"
        findPreference<Preference>("bg_image_clear")?.isVisible = hasImage
        findPreference<SeekBarPreference>("wallpaper_dim")?.isVisible = hasImage
    }

    // ── Show/hide style sub-settings ─────────────────────────────────────────

    private val styleCategories = mapOf(
        AppListPrefs.STYLE_LIST      to "cat_list",
        AppListPrefs.STYLE_GRID      to "cat_grid",
        AppListPrefs.STYLE_WATERFALL to "cat_waterfall",
        AppListPrefs.STYLE_HONEYCOMB to "cat_honeycomb",
        AppListPrefs.STYLE_GRID2D    to "cat_grid2d",
        AppListPrefs.STYLE_PAGES     to "cat_pages"
    )

    private fun updateVisibility(activeStyle: String) {
        styleCategories.forEach { (style, key) ->
            findPreference<PreferenceCategory>(key)?.isVisible = (style == activeStyle)
        }
    }

    // ── Sort hint ─────────────────────────────────────────────────────────────

    private fun updateSortHint(sort: String) {
        findPreference<Preference>("sort_custom_hint")?.isVisible =
            (sort == AppListPrefs.SORT_CUSTOM)
    }

    // ── Icon pack list ────────────────────────────────────────────────────────

    private fun populateIconPacks() {
        val pref = findPreference<ListPreference>("icon_pack") ?: return
        val packs = IconPackLoader.getInstalledPacks(requireContext())
        val entries = mutableListOf<CharSequence>(getString(R.string.pref_icon_pack_none))
        val values  = mutableListOf<CharSequence>("")
        packs.forEach { (pkg, label) -> entries.add(label); values.add(pkg) }
        pref.entries = entries.toTypedArray()
        pref.entryValues = values.toTypedArray()
        pref.setOnPreferenceChangeListener { _, newValue ->
            IconPackLoader.setSelectedPack(requireContext(), (newValue as String).ifEmpty { null })
            true
        }
    }
}
