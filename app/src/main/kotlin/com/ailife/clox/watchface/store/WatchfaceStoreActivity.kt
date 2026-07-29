package com.ailife.clox.watchface.store

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.http.HttpResponseCache
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.ailife.clox.graphics.GraphicsCompat
import com.ailife.clox.util.safeStartActivity
import com.ailife.clox.watchface.WatchfacePrefs
import com.ailife.clox.watchface.WatchfaceRepository
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class WatchfaceStoreActivity : AppCompatActivity() {

    companion object {
        // Domain only — preview_url and download_url in API responses already include /api/...
        private const val DOMAIN   = "https://jbwatchface.ai-life.xyz"
        private const val BASE_API = "$DOMAIN/api"
        private const val PAGE_SIZE = 20
        private const val TAG = "WatchfaceStore"

        fun launch(ctx: Context) =
            ctx.safeStartActivity(Intent(ctx, WatchfaceStoreActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private lateinit var recycler: RecyclerView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var initialProgress: CircularProgressIndicator
    private lateinit var chipGroup: ChipGroup
    private lateinit var adapter: StoreAdapter

    private var currentPage = 1
    private var isLoading = false
    private var hasMore = true
    private var currentQuery = ""
    private var currentCategory = ""
    private var currentSort = ""
    private var currentScreenType = ""
    private val items = mutableListOf<StoreItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        DynamicColors.applyToActivityIfAvailable(this)
        com.ailife.clox.util.MonetBackport.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watchface_store)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Disk-cache preview JPEGs (server sends 24h Cache-Control) so previews
        // survive process restarts and don't re-download on every visit.
        runCatching {
            if (HttpResponseCache.getInstalled() == null)
                HttpResponseCache.install(File(cacheDir, "store_http"), 20L * 1024 * 1024)
        }.onFailure { Log.w(TAG, "HTTP cache install failed: ${it.message}") }

        val toolbar = findViewById<Toolbar>(R.id.storeToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.pref_wf_store_title)

        recycler         = findViewById(R.id.storeRecycler)
        progressBar      = findViewById(R.id.storeProgressBar)
        initialProgress  = findViewById(R.id.initialProgress)
        chipGroup        = findViewById(R.id.storeCategoryChips)

        adapter = StoreAdapter(lifecycleScope, onItemClick = ::showDetail)
        recycler.layoutManager = GridLayoutManager(this, 2)
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as GridLayoutManager
                if (!isLoading && hasMore && lm.findLastVisibleItemPosition() >= adapter.itemCount - 4)
                    loadPage()
            }
        })

        // Sort toggle: most downloaded (sort=popular) vs default (latest)
        findViewById<Chip>(R.id.chipSortPopular).setOnCheckedChangeListener { _, checked ->
            reload(sort = if (checked) "popular" else "")
        }

        // Screen-type filter: round / square / rectangular (single-select, uncheckable)
        findViewById<ChipGroup>(R.id.storeScreenChips).setOnCheckedStateChangeListener { _, checkedIds ->
            reload(screenType = when (checkedIds.firstOrNull()) {
                R.id.chipScreenRound  -> "round"
                R.id.chipScreenSquare -> "square"
                R.id.chipScreenRect   -> "rectangular"
                else                  -> ""
            })
        }

        loadCategories()
        loadPage(reset = true)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.watchface_store_toolbar, menu)
        val searchItem = menu.findItem(R.id.action_store_search)
        val sv = searchItem?.actionView as? SearchView ?: return true
        sv.queryHint = getString(R.string.wf_search_hint)
        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true.also { reload(q.orEmpty()) }
            override fun onQueryTextChange(q: String?): Boolean {
                if (q.orEmpty() != currentQuery) reload(q.orEmpty())
                return true
            }
        })
        return true
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onStop() {
        super.onStop()
        HttpResponseCache.getInstalled()?.flush()
    }

    private fun reload(
        query: String = currentQuery,
        category: String = currentCategory,
        sort: String = currentSort,
        screenType: String = currentScreenType,
    ) {
        currentQuery = query; currentCategory = category
        currentSort = sort; currentScreenType = screenType
        items.clear(); adapter.submitList(emptyList())
        currentPage = 1; hasMore = true
        loadPage(reset = true)
    }

    private fun loadPage(reset: Boolean = false) {
        if (isLoading || !hasMore) return
        isLoading = true
        progressBar.visibility = View.VISIBLE
        if (items.isEmpty()) initialProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val url = buildUrl(currentPage)
                val json = withContext(Dispatchers.IO) { fetchJson(url) }
                val data = json.getJSONArray("data")
                val pagination = json.getJSONObject("pagination")
                hasMore = currentPage < pagination.getInt("total_pages")
                currentPage++

                val existing = WatchfaceRepository.getWatchfaceDir(this@WatchfaceStoreActivity)
                val newItems = (0 until data.length()).map { i ->
                    val obj = data.getJSONObject(i)
                    val name = obj.getString("name")
                    // preview_url is like "/api/watchfaces/.../preview" — prepend domain only
                    val previewUrl = "$DOMAIN${obj.optString("preview_url")}"
                    StoreItem(
                        id = obj.getString("id"),
                        name = name,
                        previewUrl = previewUrl,
                        downloadCount = obj.optInt("download_count"),
                        isDownloaded = File(existing, "$name.watch").exists(),
                        uploadDate = obj.optString("created_at", "").take(10).replace("-", "/")
                    )
                }
                items.addAll(newItems)
                adapter.submitList(items.toList())
            } catch (e: Exception) {
                Log.e(TAG, "Load page failed", e)
                Toast.makeText(this@WatchfaceStoreActivity,
                    getString(R.string.wf_store_load_failed, e.message), Toast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
                progressBar.visibility = View.GONE
                initialProgress.visibility = View.GONE
            }
        }
    }

    private fun loadCategories() {
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) { fetchJson("$BASE_API/categories") }
                val data = json.getJSONArray("data")
                Chip(this@WatchfaceStoreActivity).apply {
                    text = getString(R.string.wf_store_filter_all); isCheckable = true; isChecked = true
                    setOnClickListener { reload(category = "") }
                }.also { chipGroup.addView(it) }
                (0 until data.length()).forEach { i ->
                    val obj = data.getJSONObject(i)
                    val slug = obj.getString("slug")
                    Chip(this@WatchfaceStoreActivity).apply {
                        text = obj.getString("name"); isCheckable = true
                        setOnClickListener { chipGroup.clearCheck(); isChecked = true; reload(category = slug) }
                    }.also { chipGroup.addView(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Categories failed: ${e.message}")
            }
        }
    }

    private fun showDetail(item: StoreItem) {
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) { fetchJson("$BASE_API/watchfaces/${item.id}") }
                val desc = json.optString("description", "")
                // The author lives in the detail response only (uploader.username), not the list.
                val author = json.optJSONObject("uploader")?.optString("username", "") ?: ""
                // download_url is like "/api/watchfaces/.../download" — prepend domain
                val downloadUrl = "$DOMAIN${json.optString("download_url")}"
                StoreDetailBottomSheet.show(supportFragmentManager, item, desc, downloadUrl,
                    item.uploadDate, author) { url, name ->
                    downloadWatchface(url, name)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detail failed", e)
                Toast.makeText(this@WatchfaceStoreActivity,
                    getString(R.string.wf_store_load_detail_failed, e.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    suspend fun downloadWatchface(downloadUrl: String, fileName: String) {
        try {
            val destFile = withContext(Dispatchers.IO) {
                // Unique temp file per download: concurrent downloads of the same
                // face must not share a path, or one's delete() races the other's copyTo()
                val tmp = File.createTempFile("wf_dl_", ".watch", cacheDir)
                try {
                    URL(downloadUrl).openStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    val dest = File(WatchfaceRepository.getWatchfaceDir(this@WatchfaceStoreActivity), "$fileName.watch")
                    tmp.copyTo(dest, overwrite = true)
                } finally {
                    tmp.delete()
                }
            }
            WatchfacePrefs.setActivePath(this@WatchfaceStoreActivity, destFile.absolutePath)
            Toast.makeText(this@WatchfaceStoreActivity,
                getString(R.string.wf_store_download_done), Toast.LENGTH_SHORT).show()
            reload()
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            throw e  // suspend fun: propagates to the bottom sheet's try/catch
        }
    }

    private fun buildUrl(page: Int): String {
        val sb = StringBuilder("$BASE_API/watchfaces?page=$page&limit=$PAGE_SIZE")
        if (currentQuery.isNotBlank())      sb.append("&search=${java.net.URLEncoder.encode(currentQuery, "UTF-8")}")
        if (currentCategory.isNotBlank())   sb.append("&category=$currentCategory")
        if (currentSort.isNotBlank())       sb.append("&sort=$currentSort")
        if (currentScreenType.isNotBlank()) sb.append("&screen_type=$currentScreenType")
        return sb.toString()
    }

    private fun fetchJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 15_000
        return conn.inputStream.use { JSONObject(it.reader().readText()) }
    }
}

// ── Data model ────────────────────────────────────────────────────────────────

data class StoreItem(
    val id: String,
    val name: String,
    val previewUrl: String,
    val downloadCount: Int,
    val isDownloaded: Boolean,
    val uploadDate: String = "",
)

// ── Preview bitmap cache ──────────────────────────────────────────────────────
//
// In-memory LRU keyed on preview URL. Without this, RecyclerView re-fetched and
// re-decoded every thumbnail over the network on every rebind, so previews kept
// reloading while scrolling. A cache hit is instant and does no network work;
// the HttpResponseCache installed by the activity backs cache misses from disk.

object StorePreviewCache {
    // ~64px grid thumbnails; keep 2x for crisp centerCrop on high-density screens.
    const val TARGET_PX = 320

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()  // 1/8 of heap, in KB
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun get(url: String): Bitmap? = cache.get(url)

    /** Fetch (via HTTP cache when possible), downsample to ~TARGET_PX, and memoise. */
    fun load(url: String, reqWidth: Int = TARGET_PX): Bitmap? {
        cache.get(url)?.let { return it }
        val bytes = runCatching {
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.connectTimeout = 5_000; conn.readTimeout = 10_000
            conn.inputStream.use { it.readBytes() }
        }.onFailure { Log.w("StorePreviewCache", "Preview load failed for $url: ${it.message}") }
         .getOrNull() ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > reqWidth * 2) sample *= 2
        val bmp = GraphicsCompat.serializedDecode {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        cache.put(url, bmp)
        return bmp
    }
}

// ── Store grid adapter ────────────────────────────────────────────────────────

class StoreAdapter(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onItemClick: (StoreItem) -> Unit,
) : RecyclerView.Adapter<StoreAdapter.VH>() {

    private var items: List<StoreItem> = emptyList()
    fun submitList(list: List<StoreItem>) { items = list; notifyDataSetChanged() }

    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_store_card, parent, false))

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val item = items[pos]
        holder.tvName.text = item.name
        holder.tvDownloads.text = holder.itemView.context.getString(R.string.store_download_count, item.downloadCount)
        holder.ivDownloaded.visibility = if (item.isDownloaded) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.boundId = item.id
        holder.loadJob?.cancel()

        // Cache hit: set instantly, no flash and no network.
        val cached = StorePreviewCache.get(item.previewUrl)
        if (cached != null) {
            holder.ivPreview.setImageBitmap(cached)
            return
        }
        holder.ivPreview.setImageBitmap(null)
        holder.loadJob = scope.launch(Dispatchers.IO) {
            val bmp = StorePreviewCache.load(item.previewUrl)
            withContext(Dispatchers.Main) {
                if (holder.boundId == item.id) holder.ivPreview.setImageBitmap(bmp)
            }
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvStoreName)
        val tvDownloads: TextView = v.findViewById(R.id.tvStoreDownloads)
        val ivPreview: ImageView = v.findViewById(R.id.ivStorePreview)
        val ivDownloaded: ImageView = v.findViewById(R.id.ivStoreDownloaded)
        var boundId = ""; var loadJob: Job? = null
    }
}

// ── Detail bottom sheet ───────────────────────────────────────────────────────

class StoreDetailBottomSheet : BottomSheetDialogFragment() {
    companion object {
        fun show(
            fm: FragmentManager, item: StoreItem,
            description: String, downloadUrl: String, uploadDate: String,
            author: String = "",
            onDownload: suspend (String, String) -> Unit,
        ) = StoreDetailBottomSheet().apply {
            this.onDownload = onDownload
            arguments = Bundle().apply {
                putString("id", item.id); putString("name", item.name)
                putString("previewUrl", item.previewUrl)
                putInt("downloadCount", item.downloadCount)
                putString("desc", description); putString("durl", downloadUrl)
                putString("uploadDate", uploadDate)
                putString("author", author)
            }
            show(fm, "store_detail")
        }
    }

    var onDownload: (suspend (String, String) -> Unit)? = null

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        inf.inflate(R.layout.bottom_sheet_store_detail, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val args = arguments ?: return
        val name = args.getString("name") ?: ""
        val durl = args.getString("durl") ?: ""

        view.findViewById<TextView>(R.id.tvDetailName).text = name
        val author = args.getString("author", "")
        val tvAuthor = view.findViewById<TextView>(R.id.tvDetailAuthor)
        if (author.isNotEmpty()) {
            tvAuthor.text = getString(R.string.store_author_label, author)
            tvAuthor.visibility = View.VISIBLE
        } else {
            tvAuthor.visibility = View.GONE
        }
        view.findViewById<TextView>(R.id.tvDetailDesc).text = args.getString("desc")
        view.findViewById<TextView>(R.id.tvDetailDownloads).text =
            getString(R.string.store_downloads_label, args.getInt("downloadCount"))
        val uploadDate = args.getString("uploadDate", "")
        val tvDate = view.findViewById<TextView>(R.id.tvDetailDate)
        if (uploadDate.isNotEmpty()) {
            tvDate.text = getString(R.string.store_uploaded_label, uploadDate)
            tvDate.visibility = View.VISIBLE
        } else {
            tvDate.visibility = View.GONE
        }

        val ivPreview = view.findViewById<ImageView>(R.id.ivDetailPreview)
        val previewUrl = args.getString("previewUrl") ?: ""
        StorePreviewCache.get(previewUrl)?.let { ivPreview.setImageBitmap(it) }
            ?: lifecycleScope.launch(Dispatchers.IO) {
                val bmp = StorePreviewCache.load(previewUrl)
                withContext(Dispatchers.Main) { ivPreview.setImageBitmap(bmp) }
            }

        val btnDownload  = view.findViewById<MaterialButton>(R.id.btnDetailDownload)
        val progressLayout = view.findViewById<View>(R.id.downloadProgressLayout)
        val tvStatus     = view.findViewById<TextView>(R.id.tvDownloadStatus)

        btnDownload.setOnClickListener {
            btnDownload.visibility = View.GONE
            progressLayout.visibility = View.VISIBLE
            tvStatus.setText(R.string.wf_store_downloading)

            lifecycleScope.launch {
                try {
                    onDownload?.invoke(durl, name)
                    // Small delay so user sees the "Done" state before dismiss
                    tvStatus.setText(R.string.wf_store_download_done)
                    view.postDelayed({ dismissAllowingStateLoss() }, 800)
                } catch (e: Exception) {
                    // The bottom sheet can be dismissed (swipe-down) while the download is
                    // still in flight; if the failure lands after that, the fragment is
                    // already detached and requireContext()/view access would crash.
                    val ctx = context ?: return@launch
                    progressLayout.visibility = View.GONE
                    btnDownload.visibility = View.VISIBLE
                    Toast.makeText(ctx,
                        getString(R.string.store_download_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
