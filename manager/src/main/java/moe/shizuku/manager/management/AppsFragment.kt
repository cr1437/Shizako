package moe.shizuku.manager.management

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.FragmentAppsBinding
import rikka.lifecycle.Status
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.Shizuku
import java.util.Objects

/**
 * KernelSU 风格「应用管理」Tab：大标题折叠栏 + 搜索 + 下拉刷新 + 授权应用列表。
 * 逻辑迁移自原 ApplicationManagementActivity。
 */
class AppsFragment : Fragment() {

    private val viewModel by appsViewModel()
    private lateinit var adapter: AppsAdapter

    private var binding: FragmentAppsBinding? = null

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        val b = binding ?: return@OnBinderDeadListener
        adapter.updateData(emptyList())
        b.swipeRefresh.isRefreshing = false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentAppsBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding ?: return
        adapter = AppsAdapter(requireContext())

        binding.toolbar.title = getString(R.string.home_app_management_title)
        binding.toolbar.inflateMenu(R.menu.menu_apps)
        val searchItem = binding.toolbar.menu.findItem(R.id.menu_search)
        (searchItem?.actionView as? SearchView)?.setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    adapter.setQuery(newText)
                    return true
                }
            }
        )

        viewModel.packages.observe(viewLifecycleOwner) {
            when (it.status) {
                Status.SUCCESS -> {
                    binding.swipeRefresh.isRefreshing = false
                    adapter.updateData(it.data)
                }
                Status.ERROR -> {
                    binding.swipeRefresh.isRefreshing = false
                    val tr = it.error
                    Toast.makeText(context, Objects.toString(tr, "unknown"), Toast.LENGTH_SHORT).show()
                    tr?.printStackTrace()
                }
                Status.LOADING -> {
                }
            }
        }
        if (viewModel.packages.value == null) {
            binding.swipeRefresh.isRefreshing = true
            viewModel.load()
        }

        val recyclerView = binding.list
        recyclerView.adapter = adapter
        recyclerView.fixEdgeEffect()
        // 亚克力悬浮导航遮挡避让：末项可完整滚到胶囊上方
        recyclerView.updatePadding(bottom = resources.getDimensionPixelSize(R.dimen.ksu_content_bottom_padding))

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.load()
        }

        adapter.registerAdapterDataObserver(object : AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                viewModel.load(true)
            }
        })

        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Shizuku.removeBinderDeadListener(binderDeadListener)
        binding = null
    }
}
