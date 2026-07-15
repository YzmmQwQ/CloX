package com.ailife.clox.watchface.selector

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.ailife.clox.util.safeStartActivity
import com.ailife.clox.watchface.WatchfaceConfigPrefs
import com.ailife.clox.watchface.WatchfaceItem
import com.ailife.clox.watchface.WatchfacePrefs
import com.ailife.clox.watchface.WatchfaceRepository
import com.ailife.clox.watchface.WatchfaceSnapshot
import com.ailife.clox.watchface.loading.WatchfaceLoadingOverlay
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class WatchfaceSelectorActivity : AppCompatActivity() {

    private lateinit var snapshotOverlay: ImageView
    private var overlaySquareSize = 0   // min(screenW, screenH) — the watchface's square render dimension
    private var mode = WatchfacePrefs.SelectorMode.PAGER

    // Pager mode (RecyclerView + custom fling)
    private var pagerRecycler: RecyclerView? = null
    private var pagerLm: LinearLayoutManager? = null
    private var pagerAdapter: PagerAdapter? = null

    // Grid mode
    private var recyclerView: RecyclerView? = null
    private var gridAdapter: GridAdapter? = null
    private var gridToolbar: LinearLayout? = null
    private var gridActions: LinearLayout? = null
    private var searchBar: EditText? = null
    private var btnSearch: ImageButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        overridePendingTransition(R.anim.no_animation, R.anim.no_animation)
        setContentView(R.layout.activity_watchface_selector)
        hideSystemBars()

        snapshotOverlay = findViewById(R.id.snapshotOverlay)

        // Make overlay square match the watchface's render canvas: the stage fits
        // the 512 canvas to the SMALLER screen dimension, centred (watchface.lua
        // applyStageTransform) — full-bleed on the square watch screen, black bars
        // on the longer axis of a portrait/landscape screen.
        val (winW, winH) = windowSize()
        overlaySquareSize = minOf(winW, winH)
        snapshotOverlay.layoutParams = FrameLayout.LayoutParams(overlaySquareSize, overlaySquareSize).apply {
            gravity = Gravity.CENTER
        }

        mode = WatchfacePrefs.getSelectorMode(this)

        val pagerContainer = findViewById<View>(R.id.pagerContainer)
        val gridContainer = findViewById<View>(R.id.gridContainer)

        when (mode) {
            WatchfacePrefs.SelectorMode.PAGER -> {
                pagerContainer.visibility = View.VISIBLE
                gridContainer.visibility = View.GONE
                setupPagerMode()
            }
            WatchfacePrefs.SelectorMode.GRID -> {
                pagerContainer.visibility = View.GONE
                gridContainer.visibility = View.VISIBLE
                setupGridMode()
            }
        }

        val snapshot = WatchfaceSnapshot.consume()
        if (snapshot != null) {
            snapshotOverlay.setImageBitmap(snapshot)
            // Cover the face exactly as it renders: the overlay square starts on
            // the active face's aligned canvas rect (zoom/offset config applied).
            applyCanvasTransform(snapshotOverlay, WatchfacePrefs.getActivePath(this))
            snapshotOverlay.visibility = View.VISIBLE
            snapshotOverlay.alpha = 1f
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ── Pager mode: RecyclerView, custom fling (boosted + fast-stop), card effects ──

    private fun setupPagerMode() {
        val rv = findViewById<RecyclerView>(R.id.pagerRecycler)
        pagerRecycler = rv

        val screenW = resources.displayMetrics.widthPixels
        val sidePadding = (screenW * 0.22f).toInt()
        val cardWidth   = screenW - 2 * sidePadding
        rv.setPadding(sidePadding, 0, sidePadding, 0)
        rv.clipToPadding = false

        // Custom LayoutManager: faster + CENTERED smooth scroll
        val lm = object : LinearLayoutManager(this, HORIZONTAL, false) {
            override fun smoothScrollToPosition(rv: RecyclerView, state: RecyclerView.State?, pos: Int) {
                val scroller = object : androidx.recyclerview.widget.LinearSmoothScroller(rv.context) {
                    override fun calculateSpeedPerPixel(dm: android.util.DisplayMetrics) =
                        6f / dm.densityDpi   // ~4× faster than default, for snappy detent
                    override fun getHorizontalSnapPreference() = SNAP_TO_ANY
                    override fun calculateTimeForDeceleration(dx: Int): Int =
                        (calculateTimeForScrolling(dx) * 0.5f).toInt()  // shorter decel = sharper stop

                    override fun calculateDxToMakeVisible(view: android.view.View, snapPreference: Int): Int {
                        val lm = layoutManager as? LinearLayoutManager ?: return 0
                        val childCenter = (lm.getDecoratedLeft(view) + lm.getDecoratedRight(view)) / 2
                        val containerCenter = lm.width / 2
                        return containerCenter - childCenter
                    }
                }
                scroller.targetPosition = pos
                startSmoothScroll(scroller)
            }
        }
        pagerLm = lm
        rv.layoutManager = lm

        // FastPagerSnapHelper: velocity-based multi-page flings + strong snap
        FastPagerSnapHelper().attachToRecyclerView(rv)

        // Scroll listener: darken + scale cards based on distance from center
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = applyPagerCardEffects()
        })

        pagerAdapter = PagerAdapter(
            scope = lifecycleScope,
            cardWidth = cardWidth,
            onStoreClick = { openStore() },
            onRemoteClick = { openRemote() },
            onImportClick = { openImport() },
            onRandomClick = { pickRandom() },
            onDefaultSelect = { selectDefault() },
            onDelete = { item -> confirmDelete(item) },
            onRename = { item -> showRenameDialog(item) },
            onPinToggle = { item -> togglePin(item) },
            onSelect = { item, iv -> selectWatchface(item, iv) },
        )
        rv.adapter = pagerAdapter

        pagerAdapter!!.onActiveCardBound = { previewView ->
            previewView.post {
                applyPagerCardEffects()
                animateOpenToCard(previewView)
            }
        }

        loadWatchfaces()
    }

    /** Darken and scale cards that are not at the center snap position. */
    private fun applyPagerCardEffects() {
        val rv = pagerRecycler ?: return
        val lm = pagerLm      ?: return
        val centerX = rv.paddingStart + (rv.width - rv.paddingStart - rv.paddingEnd) / 2
        val cardW   = (rv.width - rv.paddingStart - rv.paddingEnd).toFloat()
        for (i in 0 until lm.childCount) {
            val child = lm.getChildAt(i) ?: continue
            val childCenter = (child.left + child.right) / 2
            val ratio = (abs(childCenter - centerX) / cardW).coerceIn(0f, 1f)
            val scale = 1f - 0.30f * ratio   // 100% → 70%
            val alpha = 1f - 0.40f * ratio   // 100% → 60%
            child.scaleX = scale
            child.scaleY = scale
            child.alpha  = alpha
        }
    }

    // ── Grid mode ─────────────────────────────────────────────────────────────

    private fun setupGridMode() {
        recyclerView = findViewById(R.id.recyclerView)
        gridToolbar = findViewById(R.id.gridToolbar)
        gridActions = findViewById(R.id.gridActions)
        searchBar = findViewById(R.id.searchBar)
        btnSearch = findViewById(R.id.btnSearch)

        gridAdapter = GridAdapter(
            scope = lifecycleScope,
            onSelect = { item, iv -> selectWatchface(item, iv) },
            onLongPress = { item, anchor -> showWatchfaceContextMenu(item, anchor) },
            onFolderLongPress = { folderName, anchor -> showFolderContextMenu(folderName, anchor) },
            onAddFolderClick = { showAddFolderDialog() },
            onDefaultSelect = { selectDefault() },
        )

        val layoutManager = GridLayoutManager(this, 2)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = when (gridAdapter!!.getItemViewType(position)) {
                GridAdapter.TYPE_CELL, GridAdapter.TYPE_DEFAULT_WF -> 1
                else -> 2
            }
        }
        recyclerView!!.layoutManager = layoutManager
        recyclerView!!.adapter = gridAdapter

        gridAdapter!!.let { adapter ->
            // after adapter submits, scroll to active
        }

        // Toolbar buttons
        findViewById<View>(R.id.btnStore2).setOnClickListener { openStore() }
        findViewById<View>(R.id.btnRemote2).setOnClickListener { openRemote() }
        findViewById<View>(R.id.btnImport2).setOnClickListener { openImport() }
        findViewById<View>(R.id.btnRandom2).setOnClickListener { pickRandom() }
        findViewById<ImageButton>(R.id.btnBack2).setOnClickListener { finish() }

        btnSearch!!.setOnClickListener { toggleSearch() }

        searchBar!!.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                gridAdapter!!.setQuery(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        loadWatchfaces()
    }

    private var searchOpen = false
    private fun toggleSearch() {
        searchOpen = !searchOpen
        if (searchOpen) {
            gridActions!!.visibility = View.GONE
            searchBar!!.visibility = View.VISIBLE
            searchBar!!.requestFocus()
            btnSearch!!.setImageResource(R.drawable.ic_search)
        } else {
            searchBar!!.text.clear()
            searchBar!!.visibility = View.GONE
            gridActions!!.visibility = View.VISIBLE
            gridAdapter!!.setQuery("")
        }
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadWatchfaces() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { WatchfaceRepository.scan(this@WatchfaceSelectorActivity) }
            val folderNames = withContext(Dispatchers.IO) {
                WatchfaceRepository.getFolders(this@WatchfaceSelectorActivity).map { it.name }
            }
            val activeIsDefault = WatchfacePrefs.getActivePath(this@WatchfaceSelectorActivity).isNullOrEmpty()
            pagerAdapter?.submitList(items, activeIsDefault)
            gridAdapter?.submitData(items, folderNames, activeIsDefault)

            if (mode == WatchfacePrefs.SelectorMode.PAGER) {
                val activePos = pagerAdapter!!.getActivePosition()
                pagerRecycler!!.scrollToPosition(activePos)
                // onActiveCardBound triggers the animation when the active card is drawn
            } else {
                val activePos = gridAdapter!!.getActivePosition()
                if (activePos >= 0) recyclerView!!.scrollToPosition(activePos)
                recyclerView!!.post {
                    recyclerView!!.post {
                        val activeView = findActiveGridCellPreview()
                        animateOpenToCard(activeView)
                    }
                }
            }
        }
    }

    private fun findActiveGridCellPreview(): ImageView? {
        val rv = recyclerView ?: return null
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val activeFrame = child.findViewById<View>(R.id.activeFrame) ?: continue
            if (activeFrame.visibility == View.VISIBLE) {
                return child.findViewById(R.id.previewImage)
            }
        }
        return null
    }

    // ── Open/close animations ─────────────────────────────────────────────────

    /**
     * Set translation/scale so the centred overlay square sits exactly on the
     * face's render canvas (WatchfaceConfigPrefs.canvasRect — square today,
     * but scaled per-axis so a future stretched canvas keeps working).
     */
    private fun applyCanvasTransform(view: View, activePath: String?) {
        val (screenW, screenH) = windowSize()
        val canvas = WatchfaceConfigPrefs.canvasRect(this, activePath, screenW, screenH)
        view.translationX = canvas.exactCenterX() - screenW / 2f
        view.translationY = canvas.exactCenterY() - screenH / 2f
        view.scaleX = canvas.width() / overlaySquareSize.toFloat()
        view.scaleY = canvas.height() / overlaySquareSize.toFloat()
    }

    /** Close-animation reversed: overlay shrinks from fullscreen down to the active card position. */
    private fun animateOpenToCard(previewView: ImageView?) {
        val overlay = snapshotOverlay
        if (overlay.visibility != View.VISIBLE) return

        val cardBounds = Rect()
        val boundsValid = previewView?.getGlobalVisibleRect(cardBounds) == true && !cardBounds.isEmpty

        if (!boundsValid) {
            // Fallback: simple fade-out
            overlay.animate().alpha(0f).setDuration(350)
                .withEndAction { overlay.visibility = View.GONE }.start()
            return
        }

        val (winW, winH) = windowSize()
        val screenW = winW.toFloat()
        val screenH = winH.toFloat()
        // Uniform scale: the overlay is a square (overlaySquareSize × overlaySquareSize)
        // representing the watchface square. Scale by height ratio so the square maps
        // exactly to the card's square preview — no aspect-ratio distortion.
        val scale = cardBounds.height() / overlaySquareSize.toFloat()

        overlay.animate()
            .translationX(cardBounds.centerX() - screenW / 2f)
            .translationY(cardBounds.centerY() - screenH / 2f)
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(360)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                overlay.visibility = View.GONE
                overlay.translationX = 0f; overlay.translationY = 0f
                overlay.scaleX = 1f; overlay.scaleY = 1f
            }
            .start()
    }

    // ── Watchface selection ───────────────────────────────────────────────────

    private var loadingOverlay: WatchfaceLoadingOverlay? = null

    /**
     * Commit the selection and leave, covering the window handoff with the SAME
     * loading UI MainActivity shows. The swap back to home takes several hundred
     * ms on this hardware (home's window surface must be re-created and its
     * first GL frame gates the transition) — with identical loading content in
     * both windows the swap is invisible instead of a frozen last frame.
     * The pref is set only here, AFTER any zoom animation: the pref change
     * kicks off the Lua face load, whose CPU work would starve the animation.
     */
    private fun showLoadingHandoff(path: String, preview: android.graphics.Bitmap?) {
        val overlay = loadingOverlay ?: WatchfaceLoadingOverlay(this).also {
            it.isClickable = true   // selection is committed — swallow further taps
            findViewById<android.view.ViewGroup>(android.R.id.content).addView(
                it,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            loadingOverlay = it
        }
        overlay.show(preview, path)
        WatchfacePrefs.setActivePath(this, path)
        finishWithNoAnim()
    }

    private fun selectWatchface(item: WatchfaceItem, previewImageView: ImageView) {
        val preview = (previewImageView.drawable as? BitmapDrawable)?.bitmap
            ?: WatchfaceRepository.getPreviewCached(item.absolutePath)

        if (preview == null) {
            showLoadingHandoff(item.absolutePath, null)
            return
        }

        val (winW, winH) = windowSize()
        val screenW = winW.toFloat()
        val screenH = winH.toFloat()
        val cardBounds = Rect()
        previewImageView.getGlobalVisibleRect(cardBounds)

        // Uniform scale: overlay is overlaySquareSize × overlaySquareSize, start it
        // scaled down to match the card's square preview, then expand to fill screen.
        val scale = cardBounds.height() / overlaySquareSize.toFloat()

        val overlay = snapshotOverlay
        overlay.setImageBitmap(preview)
        overlay.translationX = cardBounds.centerX() - screenW / 2f
        overlay.translationY = cardBounds.centerY() - screenH / 2f
        overlay.scaleX = scale
        overlay.scaleY = scale
        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE

        // Zoom up to the SELECTED face's canvas rect (its own zoom/offset
        // alignment), so the expanded preview sits exactly where the face will
        // render and the loading placeholder takes over seamlessly.
        val canvas = WatchfaceConfigPrefs.canvasRect(this, item.absolutePath,
                                                     screenW.toInt(), screenH.toInt())
        // A plain withEndAction is not enough — it is skipped when the animation
        // is cancelled (activity destroyed mid-zoom), which would silently drop
        // the selection; onAnimationEnd fires on cancel too.
        overlay.animate()
            .translationX(canvas.exactCenterX() - screenW / 2f)
            .translationY(canvas.exactCenterY() - screenH / 2f)
            .scaleX(canvas.width() / overlaySquareSize.toFloat())
            .scaleY(canvas.height() / overlaySquareSize.toFloat())
            .setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    overlay.animate().setListener(null)
                    showLoadingHandoff(item.absolutePath, preview)
                }
            })
            .start()
    }

    private fun selectDefault() {
        showLoadingHandoff("", null)
    }

    private fun finishWithNoAnim() {
        finish()
        overridePendingTransition(R.anim.no_animation, R.anim.no_animation)
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun togglePin(item: WatchfaceItem) {
        WatchfacePrefs.setPinned(this, item.absolutePath, !item.isPinned)
        loadWatchfaces()
    }

    private fun confirmDelete(item: WatchfaceItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.wf_confirm_delete, item.name))
            .setPositiveButton(R.string.wf_action_delete) { _, _ ->
                if (item.isActive) WatchfacePrefs.setActivePath(this, "")
                WatchfacePrefs.setPinned(this, item.absolutePath, false)
                WatchfaceRepository.delete(item.file)
                loadWatchfaces()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog(item: WatchfaceItem) {
        val edit = EditText(this).apply {
            setText(item.name)
            selectAll()
            hint = getString(R.string.wf_rename_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wf_action_rename)
            .setView(edit)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = edit.text.toString().trim()
                if (newName.isBlank()) return@setPositiveButton
                val newFile = WatchfaceRepository.rename(item.file, newName)
                if (newFile == null) {
                    Toast.makeText(this, R.string.wf_rename_failed, Toast.LENGTH_SHORT).show()
                } else {
                    if (item.isActive) WatchfacePrefs.setActivePath(this, newFile.absolutePath)
                    WatchfaceRepository.invalidateCache(item.absolutePath)
                    loadWatchfaces()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showWatchfaceContextMenu(item: WatchfaceItem, anchor: View) {
        val options = mutableListOf(
            if (item.isPinned) getString(R.string.wf_action_unpin) else getString(R.string.wf_action_pin),
            getString(R.string.wf_action_rename),
            getString(R.string.wf_action_delete),
            getString(R.string.wf_action_move),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> togglePin(item)
                    1 -> showRenameDialog(item)
                    2 -> confirmDelete(item)
                    3 -> showMoveDialog(item)
                }
            }
            .show()
    }

    private fun showMoveDialog(item: WatchfaceItem) {
        val folders = WatchfaceRepository.getFolders(this)
        val options = mutableListOf(getString(R.string.wf_move_to_root))
        options.addAll(folders.map { it.name })
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wf_action_move)
            .setItems(options.toTypedArray()) { _, which ->
                val targetDir = if (which == 0) null else folders[which - 1]
                val newFile = WatchfaceRepository.moveToFolder(item.file, targetDir)
                if (newFile != null) {
                    if (item.isActive) WatchfacePrefs.setActivePath(this, newFile.absolutePath)
                    WatchfaceRepository.invalidateCache(item.absolutePath)
                    loadWatchfaces()
                }
            }
            .show()
    }

    private fun showFolderContextMenu(folderName: String, anchor: View) {
        val options = arrayOf(
            getString(R.string.wf_rename_folder),
            getString(R.string.wf_delete_folder),
            getString(R.string.wf_move_all),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(folderName)
            .setItems(options) { _, which ->
                val dir = WatchfaceRepository.getFolders(this)
                    .firstOrNull { it.name == folderName } ?: return@setItems
                when (which) {
                    0 -> showRenameFolderDialog(dir)
                    1 -> confirmDeleteFolder(dir)
                    2 -> showMoveAllDialog(dir)
                }
            }
            .show()
    }

    private fun showRenameFolderDialog(folder: java.io.File) {
        val edit = EditText(this).apply { setText(folder.name) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wf_rename_folder)
            .setView(edit)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = edit.text.toString().trim()
                if (newName.isBlank()) return@setPositiveButton
                WatchfaceRepository.renameFolder(folder, newName)
                loadWatchfaces()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteFolder(folder: java.io.File) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.wf_confirm_delete_folder, folder.name))
            .setPositiveButton(R.string.wf_delete_folder) { _, _ ->
                WatchfaceRepository.deleteFolder(folder)
                loadWatchfaces()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMoveAllDialog(folder: java.io.File) {
        val dest = mutableListOf(getString(R.string.wf_move_to_root))
        val others = WatchfaceRepository.getFolders(this).filter { it.name != folder.name }
        dest.addAll(others.map { it.name })
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wf_move_all)
            .setItems(dest.toTypedArray()) { _, which ->
                val targetDir = if (which == 0) null else others[which - 1]
                folder.listFiles { f -> f.isFile && f.name.endsWith(".watch") }?.forEach {
                    WatchfaceRepository.moveToFolder(it, targetDir)
                }
                loadWatchfaces()
            }
            .show()
    }

    private fun showAddFolderDialog() {
        val edit = EditText(this).apply { hint = getString(R.string.wf_folder_name_hint) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wf_add_folder)
            .setView(edit)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isNotBlank()) {
                    WatchfaceRepository.createFolder(this, name)
                    loadWatchfaces()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickRandom() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { WatchfaceRepository.scan(this@WatchfaceSelectorActivity) }
            if (items.isEmpty()) return@launch
            val pick = items.random()
            android.widget.Toast.makeText(this@WatchfaceSelectorActivity, getString(R.string.toast_wf_selected, pick.name), android.widget.Toast.LENGTH_SHORT).show()
            showLoadingHandoff(pick.absolutePath, WatchfaceRepository.getPreviewCached(pick.absolutePath))
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun openStore() {
        safeStartActivity(Intent(this,
            com.ailife.clox.watchface.store.WatchfaceStoreActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openRemote() {
        safeStartActivity(Intent(this,
            com.ailife.clox.watchface.remote.RemoteManagementActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openImport() {
        safeStartActivity(Intent(this,
            com.ailife.clox.watchface.WatchfaceBatchImportActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    /**
     * The window (GL surface) size in pixels — the reference the watchface
     * renders against (watchface.lua director:getWinSize). NOT displayMetrics,
     * which is reduced by the navigation bar (480x458 vs the 480x480 window on
     * this ROM); sizing the snapshot square off that drew it ~one nav-bar
     * smaller and offset from the live face. Matches WatchfaceLoadingOverlay.
     */
    private fun windowSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val p = android.graphics.Point()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p)
            p.x to p.y
        }
    }

    companion object {
        fun start(ctx: Context) {
            ctx.safeStartActivity(Intent(ctx, WatchfaceSelectorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Suppress system slide animation — we play our own snapshot transition
            if (ctx is android.app.Activity) {
                @Suppress("DEPRECATION")
                ctx.overridePendingTransition(R.anim.no_animation, R.anim.no_animation)
            }
        }
    }
}
