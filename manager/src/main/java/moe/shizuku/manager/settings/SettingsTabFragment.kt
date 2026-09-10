package moe.shizuku.manager.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.FragmentSettingsBinding

/**
 * KernelSU 风格「设置」Tab：大标题折叠栏容器，
 * 内嵌现有 SettingsFragment（设置内容零改动）。
 */
class SettingsTabFragment : Fragment() {

    private var binding: FragmentSettingsBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentSettingsBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding ?: return
        binding.toolbar.title = getString(R.string.settings_title)

        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
