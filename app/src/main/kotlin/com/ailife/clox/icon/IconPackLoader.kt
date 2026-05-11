package com.ailife.clox.icon

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser

object IconPackLoader {

    private const val ICON_PACK_ACTION = "org.adw.launcher.THEMES"
    private const val PREFS = "icon_settings"
    private const val KEY_PACK = "selected_icon_pack"

    // Cache: packPackage → (packageName → drawable). Cleared when selection changes.
    @Volatile private var cachedPackPackage: String? = null
    @Volatile private var cachedMap: Map<String, Drawable> = emptyMap()

    fun loadIcon(context: Context, packageName: String): Drawable? {
        val packPackage = getSelectedPack(context) ?: return null
        return getPackMap(context, packPackage)[packageName]
    }

    fun getSelectedPack(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PACK, null)

    fun setSelectedPack(context: Context, packPackage: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PACK, packPackage).apply()
        // Invalidate cache when selection changes
        cachedPackPackage = null
        cachedMap = emptyMap()
    }

    fun getInstalledPacks(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(ICON_PACK_ACTION)
        return pm.queryIntentActivities(intent, PackageManager.GET_META_DATA).map { ri ->
            ri.activityInfo.packageName to ri.loadLabel(pm).toString()
        }
    }

    /** Returns the full drawable map for a pack, building and caching it on first call. */
    fun getPackMap(context: Context, packPackage: String): Map<String, Drawable> {
        if (cachedPackPackage == packPackage) return cachedMap
        val map = buildPackMap(context, packPackage)
        cachedPackPackage = packPackage
        cachedMap = map
        return map
    }

    /**
     * Parse appfilter.xml once and build a map of targetPackage → Drawable.
     * Each <item component="ComponentInfo{pkg/activity}" drawable="name"/> entry
     * is indexed by the package portion of the component string.
     */
    private fun buildPackMap(context: Context, packPackage: String): Map<String, Drawable> {
        return try {
            val pm = context.packageManager
            val packRes: Resources = pm.getResourcesForApplication(packPackage)
            val packContext = context.createPackageContext(packPackage, 0)
            val appfilter = packContext.assets.open("appfilter.xml")
            val parser = android.util.Xml.newPullParser()
            parser.setInput(appfilter, "UTF-8")

            val result = mutableMapOf<String, Drawable>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "item") {
                    val component = parser.getAttributeValue(null, "component") ?: ""
                    val drawableName = parser.getAttributeValue(null, "drawable") ?: ""
                    if (drawableName.isNotEmpty()) {
                        // Extract package name from "ComponentInfo{pkg/activity}" or just "pkg"
                        val pkg = component
                            .removePrefix("ComponentInfo{")
                            .substringBefore("/")
                            .substringBefore("}")
                            .trim()
                        if (pkg.isNotEmpty() && !result.containsKey(pkg)) {
                            try {
                                val resId = packRes.getIdentifier(drawableName, "drawable", packPackage)
                                if (resId != 0) {
                                    result[pkg] = packRes.getDrawable(resId, null)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
                parser.next()
            }
            appfilter.close()
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
