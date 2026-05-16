package com.ailife.clox.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.ailife.clox.MainActivity
import com.ailife.clox.R
import com.ailife.clox.cocos.CocosManager
import com.ailife.clox.cocos.bridge.WatchfaceBridgeManager

class HomeFragment : Fragment() {

    private var bridgeManager: WatchfaceBridgeManager? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_home, container, false) as FrameLayout
        val surfaceView = CocosManager.init(requireActivity())
        root.addView(
            surfaceView, 0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val stateManager = (requireActivity() as MainActivity).stateManager
        bridgeManager = WatchfaceBridgeManager(requireContext(), stateManager).also { it.start() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bridgeManager?.stop()
        bridgeManager = null
    }
}
