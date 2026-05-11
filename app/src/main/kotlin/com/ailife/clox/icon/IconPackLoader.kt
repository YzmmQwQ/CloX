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

    fun loadIcon(context: Context, packageName: String): Drawable? {
        val packPackage = getSelectedPack(context) ?: return null
        return loadFromPack(context, packPackage, packageName)
    }

    fun getSelectedPack(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PACK, null)

    fun setSelectedPack(context: Context, packPackage: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PACK, packPackage).apply()
    }

    fun getInstalledPacks(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(ICON_PACK_ACTION)
        return pm.queryIntentActivities(intent, PackageManager.GET_META_DATA).map { ri ->
            ri.activityInfo.packageName to ri.loadLabel(pm).toString()
        }
    }

    private fun loadFromPack(context: Context, packPackage: String, targetPackage: String): Drawable? {
        return try {
            val pm = context.packageManager
            val packRes: Resources = pm.getResourcesForApplication(packPackage)
            val packContext = context.createPackageContext(packPackage, 0)

            // Load appfilter.xml to find the icon for targetPackage
            val appfilter = packContext.assets.open("appfilter.xml")
            val parser = android.util.Xml.newPullParser()
            parser.setInput(appfilter, "UTF-8")

            var drawableName: String? = null
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG
                    && parser.name == "item"
                ) {
                    val component = parser.getAttributeValue(null, "component") ?: ""
                    if (component.contains(targetPackage)) {
                        drawableName = parser.getAttributeValue(null, "drawable")
                        break
                    }
                }
                parser.next()
            }
            appfilter.close()

            drawableName?.let {
                val resId = packRes.getIdentifier(it, "drawable", packPackage)
                if (resId != 0) packRes.getDrawable(resId, null) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
