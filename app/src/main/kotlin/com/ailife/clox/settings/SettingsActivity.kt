package com.ailife.clox.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.ailife.clox.R
import com.ailife.clox.databinding.ActivitySettingsBinding
import com.ailife.clox.settings.fragments.RootSettingsFragment
import com.ailife.clox.settings.fragments.TapActionsSettingsFragment
import com.ailife.clox.update.OtaManager

class SettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.settings_title)

        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.settingsContainer.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                replace(R.id.settingsContainer, RootSettingsFragment(), "root")
            }
            OtaManager(this).checkUpdate(silent = true)

            // Deep link: open straight to a specific screen (e.g. from the tap-action
            // "Configure now" error dialog), pushing it above root so Up returns here.
            if (intent?.getStringExtra(EXTRA_OPEN_FRAGMENT) == FRAGMENT_TAP_ACTIONS) {
                openTapActions()
            }
        }

        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                title = getString(R.string.settings_title)
            }
        }
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        val fragment = supportFragmentManager.fragmentFactory
            .instantiate(classLoader, pref.fragment ?: return false)
        fragment.arguments = pref.extras

        supportFragmentManager.commit {
            setReorderingAllowed(true)
            setCustomAnimations(
                R.animator.settings_frag_enter,
                R.animator.settings_frag_exit,
                R.animator.settings_frag_pop_enter,
                R.animator.settings_frag_pop_exit
            )
            replace(R.id.settingsContainer, fragment)
            addToBackStack(pref.key)
        }

        title = pref.title
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return true
        }
        finish()
        return true
    }

    private fun openTapActions() {
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            setCustomAnimations(
                R.animator.settings_frag_enter,
                R.animator.settings_frag_exit,
                R.animator.settings_frag_pop_enter,
                R.animator.settings_frag_pop_exit
            )
            replace(R.id.settingsContainer, TapActionsSettingsFragment())
            addToBackStack("nav_tap_actions")
        }
        title = getString(R.string.pref_tap_title)
    }

    companion object {
        private const val EXTRA_OPEN_FRAGMENT = "clox.settings.open_fragment"
        const val FRAGMENT_TAP_ACTIONS = "tap_actions"

        /** Intent that opens Settings directly on the Tap Actions editor. */
        fun tapActionsIntent(ctx: Context): Intent =
            Intent(ctx, SettingsActivity::class.java)
                .putExtra(EXTRA_OPEN_FRAGMENT, FRAGMENT_TAP_ACTIONS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
