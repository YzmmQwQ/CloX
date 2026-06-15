package com.ailife.clox.settings.fragments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.ailife.clox.R
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.applist.HideAppsFragment
import com.ailife.clox.icon.IconPackLoader
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.io.File
import java.io.FileOutputStream

class AppListSettingsFragment : BaseSettingsFragment() {

    private lateinit var tvBgImageStatus: TextView
    private lateinit var rowWallpaperDim: View
    private lateinit var tvWallpaperDim: TextView
    private lateinit var sliderWallpaperDim: Slider
    private lateinit var btnBgImageClear: MaterialButton
    private lateinit var btnBgSetWallpaper: MaterialButton
    private lateinit var tvSortCustomHint: TextView
    private lateinit var tvAppListStyle: TextView
    private lateinit var tvAppListSort: TextView
    private lateinit var tvIconPack: TextView
    private lateinit var rowHiddenApps: View
    private lateinit var rowAnimations: View

    private val appListPrefs by lazy { namedPrefs(AppListPrefs.PREFS_NAME) }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val ctx = requireContext()
        try {
            val src = ctx.contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalStateException("Could not decode image")
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
            updateBgState()
        } catch (e: Exception) {
            android.util.Log.e("CloX/BgImage", "Failed to copy bg image: $e")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_applist, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvBgImageStatus  = view.findViewById(R.id.tvBgImageStatus)
        rowWallpaperDim  = view.findViewById(R.id.rowWallpaperDim)
        tvWallpaperDim   = view.findViewById(R.id.tvWallpaperDim)
        sliderWallpaperDim = view.findViewById(R.id.sliderWallpaperDim)
        btnBgImageClear  = view.findViewById(R.id.btnBgImageClear)
        btnBgSetWallpaper = view.findViewById(R.id.btnBgSetWallpaper)
        tvSortCustomHint = view.findViewById(R.id.tvSortCustomHint)
        tvAppListStyle   = view.findViewById(R.id.tvAppListStyle)
        tvAppListSort    = view.findViewById(R.id.tvAppListSort)
        tvIconPack       = view.findViewById(R.id.tvIconPack)
        rowHiddenApps    = view.findViewById(R.id.rowHiddenApps)
        rowAnimations    = view.findViewById(R.id.rowAnimations)

        val styleEntries = resources.getStringArray(R.array.app_list_style_entries)
        val styleValues  = resources.getStringArray(R.array.app_list_style_values)
        val sortEntries  = resources.getStringArray(R.array.app_list_sort_entries)
        val sortValues   = resources.getStringArray(R.array.app_list_sort_values)
        val curveEntries = resources.getStringArray(R.array.curve_entries)
        val curveValues  = resources.getStringArray(R.array.curve_values)

        fun labelFor(entries: Array<String>, values: Array<String>, v: String) =
            entries.getOrElse(values.indexOf(v)) { v }

        // Style picker
        fun refreshStyle() {
            val v = appListPrefs.getString("style", AppListPrefs.STYLE_LIST) ?: AppListPrefs.STYLE_LIST
            tvAppListStyle.text = labelFor(styleEntries, styleValues, v)
            updateStyleCardVisibility(view, v)
        }
        refreshStyle()
        view.findViewById<View>(R.id.rowAppListStyle).setOnClickListener {
            showListPicker(getString(R.string.pref_app_list_style_title), styleEntries, styleValues,
                appListPrefs.getString("style", AppListPrefs.STYLE_LIST) ?: AppListPrefs.STYLE_LIST) { v, _ ->
                appListPrefs.edit().putString("style", v).apply()
                refreshStyle()
            }
        }

        // Sort picker
        fun refreshSort() {
            val v = appListPrefs.getString("sort", AppListPrefs.SORT_ALPHA) ?: AppListPrefs.SORT_ALPHA
            tvAppListSort.text = labelFor(sortEntries, sortValues, v)
            tvSortCustomHint.visibility = if (v == AppListPrefs.SORT_CUSTOM) View.VISIBLE else View.GONE
        }
        refreshSort()
        view.findViewById<View>(R.id.rowAppListSort).setOnClickListener {
            showListPicker(getString(R.string.pref_app_list_sort_title), sortEntries, sortValues,
                appListPrefs.getString("sort", AppListPrefs.SORT_ALPHA) ?: AppListPrefs.SORT_ALPHA) { v, _ ->
                appListPrefs.edit().putString("sort", v).apply()
                refreshSort()
            }
        }

        // Icon pack
        val iconPacks = IconPackLoader.getInstalledPacks(requireContext())
        val iconPackEntries = mutableListOf(getString(R.string.pref_icon_pack_none))
        val iconPackValues  = mutableListOf("")
        iconPacks.forEach { (pkg, label) -> iconPackEntries.add(label); iconPackValues.add(pkg) }
        fun refreshIconPack() {
            val v = appListPrefs.getString("icon_pack", "") ?: ""
            tvIconPack.text = iconPackEntries.getOrElse(iconPackValues.indexOf(v)) { getString(R.string.pref_icon_pack_none) }
        }
        refreshIconPack()
        view.findViewById<View>(R.id.rowIconPack).setOnClickListener {
            showListPicker(getString(R.string.pref_icon_pack_title),
                iconPackEntries.toTypedArray(), iconPackValues.toTypedArray(),
                appListPrefs.getString("icon_pack", "") ?: "") { v, _ ->
                appListPrefs.edit().putString("icon_pack", v).apply()
                IconPackLoader.setSelectedPack(requireContext(), v.ifEmpty { null })
                refreshIconPack()
            }
        }

        // Animations subcategory nav
        rowAnimations.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, AnimationSettingsFragment())
                .addToBackStack(null)
                .commit()
            (activity as? androidx.appcompat.app.AppCompatActivity)?.title =
                getString(R.string.applist_setting_animations_title)
        }

        // Hidden apps nav — only visible when no password set or already unlocked this session
        updateHiddenAppsVisibility()
        rowHiddenApps.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, HideAppsFragment())
                .addToBackStack(null)
                .commit()
        }

        // Background image
        view.findViewById<View>(R.id.rowBgImagePick).setOnClickListener { pickImage.launch("image/*") }
        btnBgImageClear.setOnClickListener {
            AppListPrefs.saveBgImagePath(requireContext(), null)
            updateBgState()
        }
        btnBgSetWallpaper.setOnClickListener { setDeviceWallpaperToMatch() }
        val dimVal = appListPrefs.getInt("wallpaper_dim", 0).toFloat()
        sliderWallpaperDim.value = dimVal.coerceIn(0f, 90f)
        tvWallpaperDim.text = "${dimVal.toInt()}"
        sliderWallpaperDim.addOnChangeListener { _, value, _ ->
            tvWallpaperDim.text = "${value.toInt()}"
            appListPrefs.edit().putInt("wallpaper_dim", value.toInt()).apply()
        }
        updateBgState()

        // Notification badges
        val switchBadge = view.findViewById<MaterialSwitch>(R.id.switchNotifBadge)
        val switchBadgeCount = view.findViewById<MaterialSwitch>(R.id.switchNotifBadgeCount)
        val rowBadgeCount = view.findViewById<View>(R.id.rowNotifBadgeCount)
        switchBadge.isChecked = appListPrefs.getBoolean("notif_badge", false)
        switchBadge.setOnCheckedChangeListener { _, checked ->
            appListPrefs.edit().putBoolean("notif_badge", checked).apply()
            rowBadgeCount.visibility = if (checked) View.VISIBLE else View.GONE
        }
        rowBadgeCount.visibility = if (switchBadge.isChecked) View.VISIBLE else View.GONE
        switchBadgeCount.isChecked = appListPrefs.getBoolean("notif_badge_count", true)
        switchBadgeCount.setOnCheckedChangeListener { _, checked ->
            appListPrefs.edit().putBoolean("notif_badge_count", checked).apply()
        }

        // Overscroll strength
        val tvOverscroll = view.findViewById<TextView>(R.id.tvOverscrollStiffness)
        val sliderOverscroll = view.findViewById<Slider>(R.id.sliderOverscrollStiffness)
        val overscrollVal = appListPrefs.getInt("overscroll", 5).toFloat()
        sliderOverscroll.value = overscrollVal.coerceIn(0f, 10f)
        tvOverscroll.text = "${overscrollVal.toInt()}"
        sliderOverscroll.addOnChangeListener { _, value, _ ->
            tvOverscroll.text = "${value.toInt()}"
            appListPrefs.edit().putInt("overscroll", value.toInt()).apply()
        }

        // Style-specific sliders and switches
        setupListStyleOptions(view, appListPrefs, curveEntries, curveValues)
        setupGridStyleOptions(view, appListPrefs)
        setupWaterfallStyleOptions(view, appListPrefs, curveEntries, curveValues)
        setupHoneycombStyleOptions(view, appListPrefs)
        setupGrid2dStyleOptions(view, appListPrefs)
        setupPagesStyleOptions(view, appListPrefs)
    }

    override fun onResume() {
        super.onResume()
        updateHiddenAppsVisibility()
    }

    private fun updateHiddenAppsVisibility() {
        rowHiddenApps.visibility =
            if (AppListPrefs.hideAppsPassword(requireContext()) == null) View.VISIBLE else View.GONE
    }

    private fun updateBgState() {
        val hasImage = AppListPrefs.bgImagePath(requireContext()) != null
        tvBgImageStatus.text = if (hasImage) getString(R.string.app_list_bg_set) else getString(R.string.app_list_bg_not_set)
        btnBgImageClear.visibility = if (hasImage) View.VISIBLE else View.GONE
        btnBgSetWallpaper.visibility = if (hasImage) View.VISIBLE else View.GONE
        rowWallpaperDim.visibility  = if (hasImage) View.VISIBLE else View.GONE
    }

    /**
     * Sets the device (home-screen) wallpaper to the same image used for the app list
     * background. This hides the brief wallpaper flash that the system shows during the
     * back gesture when returning to the launcher.
     */
    private fun setDeviceWallpaperToMatch() {
        val ctx = requireContext()
        val path = AppListPrefs.bgImagePath(ctx) ?: return
        Thread {
            val ok = runCatching {
                val bmp = BitmapFactory.decodeFile(path) ?: return@runCatching false
                val wm = android.app.WallpaperManager.getInstance(ctx)
                // Home/desktop wallpaper only — do NOT touch the lock screen (FLAG_SYSTEM, not FLAG_LOCK).
                wm.setBitmap(bmp, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
                true
            }.getOrElse {
                android.util.Log.e("CloX/BgImage", "Failed to set device wallpaper: $it")
                false
            }
            view?.post {
                if (!isAdded) return@post
                android.widget.Toast.makeText(
                    requireContext(),
                    if (ok) R.string.applist_setting_set_wallpaper_done
                    else R.string.applist_setting_set_wallpaper_failed,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    private fun updateStyleCardVisibility(view: View, style: String) {
        view.findViewById<View>(R.id.cardListOptions).visibility      = if (style == AppListPrefs.STYLE_LIST)      View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.cardGridOptions).visibility      = if (style == AppListPrefs.STYLE_GRID)      View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.cardWaterfallOptions).visibility = if (style == AppListPrefs.STYLE_WATERFALL) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.cardHoneycombOptions).visibility = if (style == AppListPrefs.STYLE_HONEYCOMB) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.cardGrid2dOptions).visibility    = if (style == AppListPrefs.STYLE_GRID2D)    View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.cardPagesOptions).visibility     = if (style == AppListPrefs.STYLE_PAGES)     View.VISIBLE else View.GONE
    }

    private fun setupListStyleOptions(v: View, p: android.content.SharedPreferences,
            curveEntries: Array<String>, curveValues: Array<String>) {
        val sw3d     = v.findViewById<MaterialSwitch>(R.id.switchList3d)
        val row3dOpt = v.findViewById<View>(R.id.rowList3dOptions)
        val slider   = v.findViewById<Slider>(R.id.sliderList3dScale)
        val tvScale  = v.findViewById<TextView>(R.id.tvList3dScale)
        val tvCurve  = v.findViewById<TextView>(R.id.tvList3dCurve)
        val rowCurve = v.findViewById<View>(R.id.rowList3dCurve)
        v.findViewById<MaterialSwitch>(R.id.switchListSearch).apply {
            isChecked = p.getBoolean("list_search", true)
            setOnCheckedChangeListener { _, c -> p.edit().putBoolean("list_search", c).apply() }
        }
        sw3d.isChecked = p.getBoolean("list_3d", false)
        row3dOpt.visibility = if (sw3d.isChecked) View.VISIBLE else View.GONE
        sw3d.setOnCheckedChangeListener { _, c ->
            p.edit().putBoolean("list_3d", c).apply()
            row3dOpt.visibility = if (c) View.VISIBLE else View.GONE
        }
        val scaleVal = p.getInt("list_3d_min_scale", 50).toFloat()
        slider.value = scaleVal.coerceIn(20f, 100f); tvScale.text = "${scaleVal.toInt()}"
        slider.addOnChangeListener { _, value, _ -> tvScale.text = "${value.toInt()}"; p.edit().putInt("list_3d_min_scale", value.toInt()).apply() }
        fun refreshCurve() { tvCurve.text = curveEntries.getOrElse(curveValues.indexOf(p.getString("list_3d_curve", "linear") ?: "linear")) { "linear" } }
        refreshCurve()
        rowCurve.setOnClickListener {
            showListPicker(getString(R.string.pref_3d_curve_title), curveEntries, curveValues,
                p.getString("list_3d_curve", "linear") ?: "linear") { val2, _ ->
                p.edit().putString("list_3d_curve", val2).apply(); refreshCurve()
            }
        }
    }

    private fun setupGridStyleOptions(v: View, p: android.content.SharedPreferences) {
        val slider = v.findViewById<Slider>(R.id.sliderGridCols)
        val tvCols = v.findViewById<TextView>(R.id.tvGridCols)
        val colVal = p.getInt("grid_cols", 4).toFloat()
        slider.value = colVal.coerceIn(2f, 6f); tvCols.text = "${colVal.toInt()}"
        slider.addOnChangeListener { _, value, _ -> tvCols.text = "${value.toInt()}"; p.edit().putInt("grid_cols", value.toInt()).apply() }
        v.findViewById<MaterialSwitch>(R.id.switchGridNames).apply {
            isChecked = p.getBoolean("grid_names", true)
            setOnCheckedChangeListener { _, c -> p.edit().putBoolean("grid_names", c).apply() }
        }
    }

    private fun setupWaterfallStyleOptions(v: View, p: android.content.SharedPreferences,
            curveEntries: Array<String>, curveValues: Array<String>) {
        val sw3d     = v.findViewById<MaterialSwitch>(R.id.switchWaterfall3d)
        val row3dOpt = v.findViewById<View>(R.id.rowWaterfall3dOptions)
        val slider   = v.findViewById<Slider>(R.id.sliderWaterfall3dScale)
        val tvScale  = v.findViewById<TextView>(R.id.tvWaterfall3dScale)
        val tvCurve  = v.findViewById<TextView>(R.id.tvWaterfall3dCurve)
        val rowCurve = v.findViewById<View>(R.id.rowWaterfall3dCurve)
        sw3d.isChecked = p.getBoolean("waterfall_3d", false)
        row3dOpt.visibility = if (sw3d.isChecked) View.VISIBLE else View.GONE
        sw3d.setOnCheckedChangeListener { _, c ->
            p.edit().putBoolean("waterfall_3d", c).apply()
            row3dOpt.visibility = if (c) View.VISIBLE else View.GONE
        }
        val scaleVal = p.getInt("waterfall_3d_min_scale", 50).toFloat()
        slider.value = scaleVal.coerceIn(20f, 100f); tvScale.text = "${scaleVal.toInt()}"
        slider.addOnChangeListener { _, value, _ -> tvScale.text = "${value.toInt()}"; p.edit().putInt("waterfall_3d_min_scale", value.toInt()).apply() }
        fun refreshCurve() { tvCurve.text = curveEntries.getOrElse(curveValues.indexOf(p.getString("waterfall_3d_curve", "linear") ?: "linear")) { "linear" } }
        refreshCurve()
        rowCurve.setOnClickListener {
            showListPicker(getString(R.string.pref_3d_curve_title), curveEntries, curveValues,
                p.getString("waterfall_3d_curve", "linear") ?: "linear") { val2, _ ->
                p.edit().putString("waterfall_3d_curve", val2).apply(); refreshCurve()
            }
        }
    }

    private fun setupHoneycombStyleOptions(v: View, p: android.content.SharedPreferences) {
        val sw3d     = v.findViewById<MaterialSwitch>(R.id.switchHoneycomb3d)
        val row3dOpt = v.findViewById<View>(R.id.rowHoneycomb3dOptions)
        val slider   = v.findViewById<Slider>(R.id.sliderHoneycomb3dScale)
        val tvScale  = v.findViewById<TextView>(R.id.tvHoneycomb3dScale)
        val sliderIcon = v.findViewById<Slider>(R.id.sliderHoneycombIconSize)
        val tvIcon     = v.findViewById<TextView>(R.id.tvHoneycombIconSize)
        sw3d.isChecked = p.getBoolean("honeycomb_3d", false)
        row3dOpt.visibility = if (sw3d.isChecked) View.VISIBLE else View.GONE
        sw3d.setOnCheckedChangeListener { _, c ->
            p.edit().putBoolean("honeycomb_3d", c).apply()
            row3dOpt.visibility = if (c) View.VISIBLE else View.GONE
        }
        val scaleVal = p.getInt("honeycomb_3d_min_scale", 50).toFloat()
        slider.value = scaleVal.coerceIn(20f, 100f); tvScale.text = "${scaleVal.toInt()}"
        slider.addOnChangeListener { _, value, _ -> tvScale.text = "${value.toInt()}"; p.edit().putInt("honeycomb_3d_min_scale", value.toInt()).apply() }
        val iconVal = p.getInt("honeycomb_icon_size", 0).toFloat()
        sliderIcon.value = iconVal.coerceIn(0f, 120f); tvIcon.text = "${iconVal.toInt()}"
        sliderIcon.addOnChangeListener { _, value, _ -> tvIcon.text = "${value.toInt()}"; p.edit().putInt("honeycomb_icon_size", value.toInt()).apply() }
    }

    private fun setupGrid2dStyleOptions(v: View, p: android.content.SharedPreferences) {
        val sw3d     = v.findViewById<MaterialSwitch>(R.id.switchGrid2d3d)
        val row3dOpt = v.findViewById<View>(R.id.rowGrid2d3dOptions)
        val slider   = v.findViewById<Slider>(R.id.sliderGrid2d3dScale)
        val tvScale  = v.findViewById<TextView>(R.id.tvGrid2d3dScale)
        val sliderIcon = v.findViewById<Slider>(R.id.sliderGrid2dIconSize)
        val tvIcon     = v.findViewById<TextView>(R.id.tvGrid2dIconSize)
        v.findViewById<MaterialSwitch>(R.id.switchGrid2dNames).apply {
            isChecked = p.getBoolean("grid2d_names", false)
            setOnCheckedChangeListener { _, c -> p.edit().putBoolean("grid2d_names", c).apply() }
        }
        sw3d.isChecked = p.getBoolean("grid2d_3d", false)
        row3dOpt.visibility = if (sw3d.isChecked) View.VISIBLE else View.GONE
        sw3d.setOnCheckedChangeListener { _, c ->
            p.edit().putBoolean("grid2d_3d", c).apply()
            row3dOpt.visibility = if (c) View.VISIBLE else View.GONE
        }
        val scaleVal = p.getInt("grid2d_3d_min_scale", 50).toFloat()
        slider.value = scaleVal.coerceIn(20f, 100f); tvScale.text = "${scaleVal.toInt()}"
        slider.addOnChangeListener { _, value, _ -> tvScale.text = "${value.toInt()}"; p.edit().putInt("grid2d_3d_min_scale", value.toInt()).apply() }
        val iconVal = p.getInt("grid2d_icon_size", 0).toFloat()
        sliderIcon.value = iconVal.coerceIn(0f, 120f); tvIcon.text = "${iconVal.toInt()}"
        sliderIcon.addOnChangeListener { _, value, _ -> tvIcon.text = "${value.toInt()}"; p.edit().putInt("grid2d_icon_size", value.toInt()).apply() }
    }

    private fun setupPagesStyleOptions(v: View, p: android.content.SharedPreferences) {
        val sliderCols = v.findViewById<Slider>(R.id.sliderPagesCols)
        val tvCols     = v.findViewById<TextView>(R.id.tvPagesCols)
        val sliderRows = v.findViewById<Slider>(R.id.sliderPagesRows)
        val tvRows     = v.findViewById<TextView>(R.id.tvPagesRows)
        val colVal = p.getInt("pages_cols", 4).toFloat()
        sliderCols.value = colVal.coerceIn(2f, 6f); tvCols.text = "${colVal.toInt()}"
        sliderCols.addOnChangeListener { _, value, _ -> tvCols.text = "${value.toInt()}"; p.edit().putInt("pages_cols", value.toInt()).apply() }
        val rowVal = p.getInt("pages_rows", 5).toFloat()
        sliderRows.value = rowVal.coerceIn(2f, 8f); tvRows.text = "${rowVal.toInt()}"
        sliderRows.addOnChangeListener { _, value, _ -> tvRows.text = "${value.toInt()}"; p.edit().putInt("pages_rows", value.toInt()).apply() }
    }
}
