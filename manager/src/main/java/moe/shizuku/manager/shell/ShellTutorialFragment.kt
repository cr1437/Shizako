package moe.shizuku.manager.shell

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.FragmentSubPageBinding
import moe.shizuku.manager.databinding.FragmentTerminalTutorialBinding
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.utils.CustomTabsHelper
import rikka.html.text.HtmlCompat
import rikka.insets.*
import kotlin.math.roundToInt

/**
 * 终端（rish）教程（已迁入 Navigation，原 ShellTutorialActivity）。逻辑不变。
 */
class ShellTutorialFragment : Fragment() {

    companion object {

        private val SH_NAME = "rish"
        private val DEX_NAME = "rish_shizuku.dex"
    }

    private var shell: FragmentSubPageBinding? = null

    private val openDocumentsTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
            if (tree == null) return@registerForActivityResult

            val cr = requireContext().contentResolver
            val doc = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree)
            )
            val child = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree)
            )

            cr.query(
                child,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val name = it.getString(1)
                    if (name == SH_NAME || name == DEX_NAME) {
                        DocumentsContract.deleteDocument(
                            cr,
                            DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        )
                    }
                }
            }

            fun writeToDocument(name: String) {
                DocumentsContract.createDocument(
                    requireContext().contentResolver,
                    doc,
                    "application/octet-stream",
                    name
                )?.runCatching {
                    cr.openOutputStream(this)
                        ?.let { requireContext().assets.open(name).copyTo(it) }
                }
            }

            writeToDocument(SH_NAME)
            writeToDocument(DEX_NAME)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val shell = FragmentSubPageBinding.inflate(inflater, container, false)
        this.shell = shell
        val binding = FragmentTerminalTutorialBinding.inflate(inflater, shell.contentContainer, true)

        binding.content.apply {
            setInitialPadding(
                initialPaddingLeft,
                initialPaddingTop + (resources.displayMetrics.density * 8).roundToInt(),
                initialPaddingRight,
                initialPaddingBottom
            )
        }

        binding.apply {
            val shName = "<font face=\"monospace\">$SH_NAME</font>"
            val dexName = "<font face=\"monospace\">$DEX_NAME</font>"

            summary.text =
                getString(R.string.rish_description, shName)
                    .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)

            text1.text = getString(R.string.terminal_tutorial_1, shName, dexName).toHtml()

            text2.text = getString(R.string.terminal_tutorial_2, shName).toHtml()
            summary2.text = getString(
                R.string.terminal_tutorial_2_description,
                "Termux",
                "<font face=\"monospace\">PKG</font>",
                "<font face=\"monospace\">com.termux</font>",
                "<font face=\"monospace\">com.termux</font>",
            ).toHtml()

            text3.text = getString(
                R.string.terminal_tutorial_3,
                "<font face=\"monospace\">sh $SH_NAME</font>",
            ).toHtml()
            summary3.text = getString(
                R.string.terminal_tutorial_3_description,
                shName, "<font face=\"monospace\">PATH</font>"
            ).toHtml()

            button1.setOnClickListener { openDocumentsTree.launch(null) }
            button2.setOnClickListener { v: View ->
                CustomTabsHelper.launchUrlOrCopy(v.context, Helps.RISH.get())
            }
        }

        return shell.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val shell = shell ?: return
        shell.toolbar.title = getString(R.string.home_terminal_title)
        shell.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        shell = null
    }
}
