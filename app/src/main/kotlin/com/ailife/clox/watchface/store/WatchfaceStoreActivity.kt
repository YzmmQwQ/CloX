package com.ailife.clox.watchface.store

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
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
            ctx.startActivity(Intent(ctx, WatchfaceStoreActivity::class.java)
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
    private val items = mutableListOf<StoreItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watchface_store)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

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

    private fun reload(query: String = currentQuery, category: String = currentCategory) {
        currentQuery = query; currentCategory = category
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
                    "Failed to load: ${e.message}", Toast.LENGTH_SHORT).show()
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
                    text = "All"; isCheckable = true; isChecked = true
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
                // download_url is like "/api/watchfaces/.../download" — prepend domain
                val downloadUrl = "$DOMAIN${json.optString("download_url")}"
                StoreDetailBottomSheet.show(supportFragmentManager, item, desc, downloadUrl,
                    item.uploadDate) { url, name ->
                    downloadWatchface(url, name)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detail failed", e)
                Toast.makeText(this@WatchfaceStoreActivity,
                    "Failed to load detail: ${e.message}", Toast.LENGTH_SHORT).show()
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
        if (currentQuery.isNotBlank())    sb.append("&search=${java.net.URLEncoder.encode(currentQuery, "UTF-8")}")
        if (currentCategory.isNotBlank()) sb.append("&category=$currentCategory")
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
        holder.ivPreview.setImageBitmap(null)
        holder.ivDownloaded.visibility = if (item.isDownloaded) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onItemClick(item) }

        holder.loadJob?.cancel()
        holder.loadJob = scope.launch(Dispatchers.IO) {
            val bmp = runCatching {
                val conn = URL(item.previewUrl).openConnection() as HttpsURLConnection
                conn.connectTimeout = 5_000; conn.readTimeout = 10_000
                BitmapFactory.decodeStream(conn.inputStream)
            }.onFailure { Log.w("StoreAdapter", "Preview load failed for ${item.previewUrl}: ${it.message}") }
             .getOrNull()
            withContext(Dispatchers.Main) {
                if (holder.boundId == item.id) holder.ivPreview.setImageBitmap(bmp)
            }
        }
        holder.boundId = item.id
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
            onDownload: suspend (String, String) -> Unit,
        ) = StoreDetailBottomSheet().apply {
            this.onDownload = onDownload
            arguments = Bundle().apply {
                putString("id", item.id); putString("name", item.name)
                putString("previewUrl", item.previewUrl)
                putInt("downloadCount", item.downloadCount)
                putString("desc", description); putString("durl", downloadUrl)
                putString("uploadDate", uploadDate)
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
        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = runCatching { BitmapFactory.decodeStream(URL(previewUrl).openStream()) }.getOrNull()
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
                    progressLayout.visibility = View.GONE
                    btnDownload.visibility = View.VISIBLE
                    Toast.makeText(requireContext(),
                        getString(R.string.store_download_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
