package com.ailife.clox.applist

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.ailife.clox.applist.backends.ListBackend
import kotlinx.coroutines.launch

class AppListFragment : Fragment() {

    private val viewModel: AppListViewModel by viewModels()
    private lateinit var adapter: AppListAdapter
    private val backend: AppListBackend = ListBackend()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_app_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = AppListAdapter(backend)

        val recycler = view.findViewById<RecyclerView>(R.id.appRecyclerView)
        recycler.layoutManager = backend.createLayoutManager(requireContext())
        recycler.adapter = adapter

        val search = view.findViewById<EditText>(R.id.searchField)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                adapter.submitList(
                    viewModel.apps.value.filter { it.label.lowercase().contains(query) }
                )
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.apps.collect { list ->
                    val query = search.text?.toString()?.trim()?.lowercase() ?: ""
                    adapter.submitList(
                        if (query.isEmpty()) list
                        else list.filter { it.label.lowercase().contains(query) }
                    )
                }
            }
        }
    }
}
