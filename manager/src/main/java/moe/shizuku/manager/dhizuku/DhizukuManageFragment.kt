package moe.shizuku.manager.dhizuku

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.DhizukuAppItemBinding
import moe.shizuku.manager.databinding.FragmentDhizukuAppsBinding
import moe.shizuku.manager.databinding.FragmentSubPageBinding

/**
 * Dhizuku 本地白名单管理（已迁入 Navigation，原 DhizukuManageActivity）。逻辑不变。
 */
class DhizukuManageFragment : Fragment() {

    private val adapter = DhizukuAppsAdapter()
    private var shell: FragmentSubPageBinding? = null
    private lateinit var binding: FragmentDhizukuAppsBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val shell = FragmentSubPageBinding.inflate(inflater, container, false)
        this.shell = shell
        binding = FragmentDhizukuAppsBinding.inflate(inflater, shell.contentContainer, true)
        return shell.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val shell = shell ?: return
        shell.toolbar.title = getString(R.string.dhizuku_manage_title)
        shell.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.empty.text = getString(R.string.dhizuku_manage_empty)

        adapter.submit(DhizukuSettings.grantedUids().mapNotNull { uid ->
            val packageName =
                requireContext().packageManager.getPackagesForUid(uid)?.firstOrNull()
            if (packageName == null) {
                DhizukuSettings.revoke(uid)
                null
            } else {
                uid to packageName
            }
        })
        updateEmptyState()
    }

    private fun updateEmptyState() {
        binding.empty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    private inner class DhizukuAppsAdapter : RecyclerView.Adapter<DhizukuAppsAdapter.VH>() {

        private var entries: List<Pair<Int, String>> = emptyList()

        fun submit(list: List<Pair<Int, String>>) {
            entries = list
            notifyDataSetChanged()
        }

        fun remove(uid: Int) {
            entries = entries.filter { it.first != uid }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding =
                DhizukuAppItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(entries[position])
        }

        inner class VH(private val binding: DhizukuAppItemBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(entry: Pair<Int, String>) {
                val (uid, packageName) = entry
                val pm = requireContext().packageManager
                val ai = runCatching {
                    pm.getApplicationInfo(packageName, 0)
                }.getOrNull()

                binding.label.text = runCatching { ai?.loadLabel(pm) }.getOrNull()
                    ?: packageName
                binding.packageName.text = packageName
                binding.icon.setImageDrawable(
                    ai?.let { pm.getApplicationIcon(it) }
                        ?: pm.defaultActivityIcon
                )

                binding.root.setOnClickListener {
                    confirmRevoke(uid, binding.label.text.toString())
                }
            }
        }
    }

    private fun confirmRevoke(uid: Int, label: CharSequence) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dhizuku_revoke_title)
            .setMessage(getString(R.string.dhizuku_revoke_message, label))
            .setPositiveButton(R.string.dhizuku_revoke_confirm) { _, _ ->
                DhizukuSettings.revoke(uid)
                adapter.remove(uid)
                updateEmptyState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        shell = null
    }
}
