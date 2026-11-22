package moe.shizuku.manager.batch

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.databinding.FragmentSubPageBinding
import moe.shizuku.manager.dhizuku.DhizukuScopes
import moe.shizuku.manager.ui.glass.GlassWindow
import moe.shizuku.manager.ui.hint.HintCard
import moe.shizuku.manager.ui.hint.HintPage
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.HintPrimaryButton
import moe.shizuku.manager.ui.hint.HintSecondaryButton
import moe.shizuku.manager.ui.hint.HintSectionTitle
import moe.shizuku.manager.ui.hint.itemEntrance
import moe.shizuku.manager.ui.hint.resolveHintPalette
import moe.shizuku.manager.ui.style.UiStyle
import moe.shizuku.manager.ui.theme.setShizakoContent
import java.util.UUID

/**
 * F6 模板新建 / 编辑页。
 *
 * 外观与其它二级页一致：`fragment_sub_page.xml` 的大标题折叠栏 +
 * `HintPage` / `HintCard` / `HintPrimaryButton` / `HintSecondaryButton`，
 * 玻璃与 MD3 双风格随全局设置。
 */
class TemplateEditActivity : AppActivity() {

    companion object {
        const val EXTRA_TEMPLATE_ID = "template_id"
    }

    private var templateId: String? = null
    private var original: PermissionTemplate? = null

    private val listState = LazyListState()

    private val nameState = mutableStateOf("")
    private val permsState = mutableStateOf<Set<String>>(emptySet())
    private val scopesState = mutableStateOf<Set<String>>(emptySet())
    private var readOnly = false

    private lateinit var shell: FragmentSubPageBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        templateId = intent.getStringExtra(EXTRA_TEMPLATE_ID)
        original = templateId?.let { TemplateStore.find(this, it) }
        original?.let {
            nameState.value = it.name
            permsState.value = it.shizukuPermissions.toSet()
            scopesState.value = it.dhizukuScopes.toSet()
            readOnly = it.builtIn
        }

        shell = FragmentSubPageBinding.inflate(layoutInflater)
        setContentView(shell.root)
        shell.toolbar.title = getString(
            if (original == null) R.string.template_new else R.string.template_edit,
        )
        shell.toolbar.setNavigationOnClickListener { finish() }

        val composeView = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        shell.contentContainer.addView(composeView)
        composeView.setShizakoContent {
            val style = UiStyle.current
            val palette = remember(style) { resolveHintPalette(this@TemplateEditActivity) }
            TemplateEditScreen(
                palette = palette,
                listState = listState,
                onCollapsedChange = { expanded -> shell.appBar.setExpanded(expanded, true) },
                name = nameState.value,
                selectedPerms = permsState.value,
                selectedScopes = scopesState.value,
                readOnly = readOnly,
                canDelete = original != null && !readOnly,
                onNameChange = { nameState.value = it },
                onTogglePerm = { id ->
                    permsState.value = if (id in permsState.value) permsState.value - id
                    else permsState.value + id
                },
                onToggleScope = { id ->
                    scopesState.value = if (id in scopesState.value) scopesState.value - id
                    else scopesState.value + id
                },
                onSave = ::save,
                onDelete = ::confirmDelete,
            )
        }
    }

    private fun save() {
        if (readOnly) {
            Toast.makeText(this, R.string.template_builtin_readonly, Toast.LENGTH_SHORT).show()
            return
        }
        val name = nameState.value.trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.template_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val existing = original
        val template = PermissionTemplate(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            shizukuPermissions = permsState.value.toList(),
            dhizukuScopes = scopesState.value.toList(),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            builtIn = false,
        )
        val error = TemplateStore.save(this, template)
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            return
        }
        finish()
    }

    private fun confirmDelete() {
        val template = original ?: return
        if (template.builtIn) {
            Toast.makeText(this, R.string.template_builtin_readonly, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.template_delete_title)
            .setMessage(getString(R.string.template_delete_message, template.name))
            .setNegativeButton(R.string.batch_action_cancel, null)
            .setPositiveButton(R.string.template_delete) { _, _ ->
                TemplateStore.delete(this, template.id)
                finish()
            }
            .create()
        dialog.show()
        GlassWindow.applyIfGlass(dialog)
    }
}

@Composable
private fun TemplateEditScreen(
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    name: String,
    selectedPerms: Set<String>,
    selectedScopes: Set<String>,
    readOnly: Boolean,
    canDelete: Boolean,
    onNameChange: (String) -> Unit,
    onTogglePerm: (String) -> Unit,
    onToggleScope: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    HintPage(
        listState = listState,
        onCollapsedChange = onCollapsedChange,
        // 独立 Activity 没有悬浮底栏，但要给系统手势条 / 三键导航留白
        modifier = Modifier.navigationBarsPadding(),
        reserveNavSpace = false,
    ) {
        item {
            Box(modifier = Modifier.itemEntrance(0)) {
                HintCard(palette = palette) {
                    HintSectionTitle(palette = palette, text = stringResource(R.string.template_name_label))
                    OutlinedTextField(
                        value = name,
                        onValueChange = onNameChange,
                        enabled = !readOnly,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.template_name_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(palette.cardCorner),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = palette.onCard,
                            unfocusedTextColor = palette.onCard,
                            disabledTextColor = palette.variant,
                            cursorColor = palette.accent,
                            focusedBorderColor = palette.accent,
                            unfocusedBorderColor = palette.outline,
                            disabledBorderColor = palette.outline,
                            focusedPlaceholderColor = palette.variant,
                            unfocusedPlaceholderColor = palette.variant,
                        ),
                    )
                    if (readOnly) {
                        Text(
                            text = stringResource(R.string.template_builtin_readonly),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.variant,
                        )
                    }
                }
            }
        }

        item {
            Box(modifier = Modifier.itemEntrance(1)) {
                HintCard(palette = palette) {
                    HintSectionTitle(palette = palette, text = stringResource(R.string.template_section_shizuku))
                    ShizukuPermissions.ALL.forEach { item ->
                        HintCheckRow(
                            palette = palette,
                            label = stringResource(item.labelRes),
                            checked = item.id in selectedPerms,
                            enabled = !readOnly,
                            onToggle = { onTogglePerm(item.id) },
                        )
                    }
                }
            }
        }

        item {
            Box(modifier = Modifier.itemEntrance(2)) {
                HintCard(palette = palette) {
                    HintSectionTitle(palette = palette, text = stringResource(R.string.template_section_scopes))
                    DhizukuScopes.ALL.forEach { scope ->
                        HintCheckRow(
                            palette = palette,
                            label = stringResource(scope.labelRes),
                            checked = scope.id in selectedScopes,
                            enabled = !readOnly,
                            onToggle = { onToggleScope(scope.id) },
                        )
                    }
                    Text(
                        text = stringResource(R.string.template_scope_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.variant,
                    )
                }
            }
        }

        item {
            Box(modifier = Modifier.itemEntrance(3)) {
                HintCard(palette = palette) {
                    HintPrimaryButton(
                        palette = palette,
                        text = stringResource(R.string.template_save),
                        enabled = !readOnly,
                        onClick = onSave,
                    )
                    if (canDelete) {
                        HintSecondaryButton(
                            palette = palette,
                            text = stringResource(R.string.template_delete),
                            onClick = onDelete,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HintCheckRow(
    palette: HintPalette,
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle() }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = palette.accent,
                uncheckedColor = palette.outline,
                checkmarkColor = palette.onAccent,
                disabledCheckedColor = palette.variant,
                disabledUncheckedColor = palette.outline,
            ),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) palette.onCard else palette.variant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
