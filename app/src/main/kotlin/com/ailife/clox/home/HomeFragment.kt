package com.ailife.clox.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ailife.clox.MainActivity
import com.ailife.clox.R
import com.ailife.clox.cocos.CocosManager
import com.ailife.clox.cocos.LuaBridge
import com.ailife.clox.cocos.bridge.WatchfaceBridgeManager
import com.ailife.clox.watchface.WatchfacePrefs
import com.ailife.clox.watchface.WatchfaceRepository
import com.ailife.clox.watchface.loading.WatchfaceLoadingOverlay
import kotlinx.coroutines.launch
import java.io.File

class HomeFragment : Fragment() {

    private var bridgeManager: WatchfaceBridgeManager? = null
    private var loadingOverlay: WatchfaceLoadingOverlay? = null
    private var freshEngine = false

    // wf_loaded arrives on the GL thread — marshal to main before touching views.
    private val wfLoadedListener: (String, String) -> Unit = { event, _ ->
        if (event == "wf_loaded") {
            loadingOverlay?.post { loadingOverlay?.hide() }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_home, container, false) as FrameLayout
        // A brand-new engine always builds a face and fires wf_loaded; a reused
        // engine (activity recreation) already has the face on screen and never
        // will — only show the startup placeholder for a fresh engine.
        freshEngine = CocosManager.getGlView() == null
        val surfaceView = CocosManager.init(requireActivity())
        root.addView(
            surfaceView, 0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        loadingOverlay = WatchfaceLoadingOverlay(requireContext()).also {
            root.addView(
                it,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        return root
    }

    /** Quiesce/restore the bridge's periodic data + sensors with the host activity. */
    fun onHostPause()  { bridgeManager?.onHostPause() }
    fun onHostResume() { bridgeManager?.onHostResume() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (freshEngine) showLoadingPlaceholder()
        LuaBridge.addEventListner(wfLoadedListener)
        val stateManager = (requireActivity() as MainActivity).stateManager
        bridgeManager = WatchfaceBridgeManager(requireContext(), stateManager).also {
            it.onFaceLoadExpected = { _ ->
                loadingOverlay?.post { showLoadingPlaceholder() }
            }
            it.start()
        }
    }

    private fun showLoadingPlaceholder() {
        val overlay = loadingOverlay ?: return
        val ctx = context ?: return
        val path = WatchfacePrefs.getActivePath(ctx)
        val cached = path?.let { WatchfaceRepository.getPreviewCached(it) }
        overlay.show(cached, path)
        if (cached == null && path != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                WatchfaceRepository.loadPreview(File(path))?.let { overlay.setPreview(it) }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LuaBridge.removeEventListener(wfLoadedListener)
        loadingOverlay = null
        bridgeManager?.stop()
        bridgeManager = null
    }
}
