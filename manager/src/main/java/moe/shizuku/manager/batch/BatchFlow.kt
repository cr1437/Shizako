package moe.shizuku.manager.batch

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.dhizuku.DhizukuScopes
import moe.shizuku.manager.ui.glass.GlassWindow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 批量操作的 UI 流程（F2/F3/F4/F5/F7 的对话框与线程编排）。
 *
 * 分三层：
 * - 本文件：对话框 + 线程切换；不碰 binder 细节；
 * - [BatchExecutor]：真正循环调用单应用授权逻辑；
 * - [BatchStore] / [ApiAuditLog]：持久化与审计。
 *
 * 所有对话框都用 `MaterialAlertDialogBuilder` + 代码构建的滚动内容，
 * 这样不用为四种对话框各加一份 layout，也能在 API 24 上稳定工作。
 */
object BatchFlow {

    class Request(
        val type: BatchType,
        val target: BatchTarget,
        val apps: List<BatchApp>,
        val scopes: List<String> = emptyList(),
        val templateName: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())

    // ══════════ 入口：执行一个批量操作 ══════════

    /**
     * 弹出影响范围确认 → 执行 → 结果报告。
     *
     * 快照与预判（可能要查询几十个应用）全部在后台线程做，确认框不会卡主线程。
     */
    fun run(activity: Activity, request: Request, onFinished: (() -> Unit)? = null) {
        if (request.apps.isEmpty()) return
        val appContext = activity.applicationContext
        Thread {
            val snapshot = BatchExecutor.snapshot(activity, request.target, request.apps)
            val plan = BatchExecutor.plan(activity, request.target, request.type, request.apps, request.scopes, snapshot)
            main.post {
                if (activity.isFinishing) return@post
                showConfirmDialog(activity, request, plan) {
                    execute(activity, request, snapshot, onFinished)
                }
            }
        }.start()
    }

    private fun execute(
        activity: Activity,
        request: Request,
        snapshot: List<AppPermissionSnapshot>,
        onFinished: (() -> Unit)?,
    ) {
        val appContext = activity.applicationContext
        val batchId = UUID.randomUUID().toString()
        val cancelled = AtomicBoolean(false)
        val progress = ProgressDialog(activity, request.apps.size) { cancelled.set(true) }.apply { show() }

        Thread {
            val result = when (request.target) {
                BatchTarget.SHIZUKU -> BatchExecutor.runShizuku(
                    appContext,
                    request.apps,
                    request.type,
                    batchId,
                    isCancelled = { cancelled.get() },
                    onProgress = progress::postUpdate,
                )

                BatchTarget.DHIZUKU_DELEGATE -> BatchExecutor.runDhizuku(
                    appContext,
                    request.apps,
                    request.type,
                    request.scopes,
                    batchId,
                    isCancelled = { cancelled.get() },
                    onProgress = progress::postUpdate,
                )
            }

            val record = BatchOperationRecord(
                id = batchId,
                type = request.type,
                target = request.target,
                timestamp = System.currentTimeMillis(),
                items = result.items,
                snapshot = snapshot,
                scopes = request.scopes,
                templateName = request.templateName,
            )
            BatchStore.append(appContext, record)
            // 服务停止/取消造成的「半截」批次也记录下来：已处理项的审计已经写过，
            // 历史里留一条反而更容易解释当时发生了什么。
            main.post {
                progress.dismiss()
                if (!activity.isFinishing) {
                    onFinished?.invoke()
                    showResultDialog(activity, listOf(record), result.aborted, result.abortReason, result.cancelled)
                }
            }
        }.start()
    }

    // ══════════ F4：影响范围确认 ══════════

    private fun showConfirmDialog(
        activity: Activity,
        request: Request,
        plan: List<BatchExecutor.PlanItem>,
        onConfirm: () -> Unit,
    ) {
        val context = activity
        val typeText = context.getString(
            if (request.type == BatchType.GRANT) R.string.batch_confirm_type_grant
                else R.string.batch_confirm_type_revoke,
        )
        val targetText = context.getString(
            if (request.target == BatchTarget.SHIZUKU) R.string.batch_confirm_target_shizuku
                else R.string.batch_confirm_target_dhizuku,
        )
        val isRevoke = request.type == BatchType.REVOKE

        val content = column(context)
        content.addView(text(context, context.getString(R.string.batch_confirm_operation, typeText, targetText)))
        content.addView(text(context, context.getString(R.string.batch_confirm_count, plan.size), 13f))
        if (request.templateName != null) {
            content.addView(text(context, "模板：${request.templateName}", 13f))
        }
        content.addView(divider(context))

        if (request.target == BatchTarget.DHIZUKU_DELEGATE && request.scopes.isNotEmpty()) {
            content.addView(text(context, context.getString(R.string.batch_confirm_scopes), 13f))
            request.scopes.forEach { scope ->
                content.addView(bullet(context, scopeLabel(context, scope)))
            }
            content.addView(divider(context))
        }

        plan.forEach { item ->
            val line = if (item.skip && item.skipReason != null) {
                "${item.app.label} (${item.app.packageName}) · ${item.skipReason}"
            } else {
                "${item.app.label} (${item.app.packageName})"
            }
            val tv = text(context, line, 13f)
            if (item.skip) tv.alpha = 0.6f
            content.addView(tv)
        }

        if (isRevoke) {
            content.addView(divider(context))
            val warn = text(context, context.getString(R.string.batch_confirm_revoke_warning), 13f)
            warn.setTextColor(errorColor(context) ?: warn.currentTextColor)
            content.addView(warn)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.batch_confirm_title)
            .setView(scroll(content))
            .setNegativeButton(R.string.batch_action_cancel, null)
            .setPositiveButton(R.string.batch_confirm_execute) { _, _ -> onConfirm() }
            .create()
        dialog.show()
        GlassWindow.applyIfGlass(dialog)
        if (isRevoke) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { button ->
                errorColor(context)?.let { button.setTextColor(it) }
            }
        }
    }

    // ══════════ F5：结果报告 + 撤销本批次 ══════════

    fun showResultDialog(
        activity: Activity,
        records: List<BatchOperationRecord>,
        aborted: Boolean = false,
        abortReason: String? = null,
        cancelled: Boolean = false,
    ) {
        if (activity.isFinishing) return
        val context = activity
        val success = records.sumOf { it.successCount }
        val failed = records.sumOf { it.failedCount }
        val skipped = records.sumOf { it.skippedCount }

        val content = column(context)
        content.addView(
            text(
                context,
                context.getString(R.string.batch_result_summary, success, failed, skipped),
                15f,
            ).apply { setTypeface(typeface, Typeface.BOLD) },
        )
        if (aborted) {
            val reason = abortReason ?: context.getString(R.string.batch_result_aborted)
            content.addView(text(context, reason, 13f).apply {
                setTextColor(errorColor(context) ?: currentTextColor)
            })
        } else if (cancelled) {
            content.addView(text(context, context.getString(R.string.batch_result_cancelled), 13f))
        }

        val failedItems = records.flatMap { r -> r.items.filter { it.status == ItemStatus.FAILED } }
        if (failedItems.isNotEmpty()) {
            content.addView(divider(context))
            content.addView(text(context, context.getString(R.string.batch_result_failed_section), 13f))
            failedItems.forEach {
                val tv = text(
                    context,
                    "${appLabel(context, it.packageName)} (${it.packageName}) · ${it.reason ?: ""}",
                    12f,
                )
                errorColor(context)?.let { c -> tv.setTextColor(c) }
                content.addView(tv)
            }
        }

        val skippedItems = records.flatMap { r -> r.items.filter { it.status == ItemStatus.SKIPPED } }
        if (skippedItems.isNotEmpty()) {
            content.addView(divider(context))
            content.addView(text(context, context.getString(R.string.batch_result_skipped_section), 13f))
            skippedItems.forEach {
                content.addView(
                    text(
                        context,
                        "${appLabel(context, it.packageName)} (${it.packageName}) · ${it.reason ?: ""}",
                        12f,
                    ).apply { alpha = 0.7f },
                )
            }
        }

        val undoable = records.filter { it.canUndo }
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.batch_result_title)
            .setView(scroll(content))
            .setPositiveButton(R.string.batch_result_done, null)
        if (undoable.isNotEmpty()) {
            builder.setNeutralButton(R.string.batch_result_undo) { _, _ ->
                confirmUndo(activity, undoable)
            }
        }
        val dialog = builder.create()
        dialog.show()
        GlassWindow.applyIfGlass(dialog)
    }

    private fun confirmUndo(activity: Activity, records: List<BatchOperationRecord>) {
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.batch_undo_title)
            .setMessage(R.string.batch_undo_message)
            .setNegativeButton(R.string.batch_action_cancel, null)
            .setPositiveButton(R.string.batch_result_undo) { _, _ -> undo(activity, records) }
            .create()
        dialog.show()
        GlassWindow.applyIfGlass(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { button ->
            errorColor(activity)?.let { button.setTextColor(it) }
        }
    }

    /** 撤销一批「授予」记录（F5）。 */
    fun undo(
        activity: Activity,
        records: List<BatchOperationRecord>,
        onFinished: (() -> Unit)? = null,
    ) {
        val appContext = activity.applicationContext
        val cancelled = AtomicBoolean(false)
        val undoable = records.filter { it.canUndo }
        if (undoable.isEmpty()) return

        val total = undoable.sumOf { r -> r.items.count { it.status == ItemStatus.SUCCESS } }
        val progress = ProgressDialog(activity, total) { cancelled.set(true) }.apply { show() }

        Thread {
            val undoRecords = ArrayList<BatchOperationRecord>(undoable.size)
            var done = 0
            for (original in undoable) {
                val undoId = UUID.randomUUID().toString()
                val result = BatchExecutor.undoRecord(
                    appContext,
                    original,
                    undoId,
                    isCancelled = { cancelled.get() },
                    onProgress = { _, _, app ->
                        progress.postUpdate(done, total, app)
                    },
                )
                val record = BatchOperationRecord(
                    id = undoId,
                    type = BatchType.REVOKE,
                    target = original.target,
                    timestamp = System.currentTimeMillis(),
                    items = result.items,
                    snapshot = emptyList(),
                    scopes = original.scopes,
                    undoOf = original.id,
                )
                BatchStore.append(appContext, record)
                undoRecords.add(record)
                done += result.items.size
            }

            main.post {
                progress.dismiss()
                if (activity.isFinishing) return@post
                val success = undoRecords.sumOf { it.successCount }
                val skipped = undoRecords.sumOf { it.skippedCount }
                val failed = undoRecords.sumOf { it.failedCount }
                Toast.makeText(
                    activity,
                    activity.getString(R.string.batch_undo_result, success, skipped, failed),
                    Toast.LENGTH_LONG,
                ).show()
                onFinished?.invoke()
                showResultDialog(activity, undoRecords)
            }
        }.start()
    }

    // ══════════ F3：选择 Dhizuku scopes ══════════

    fun pickScopes(activity: Activity, preselected: Collection<String>, onPicked: (List<String>) -> Unit) {
        val scopes = DhizukuScopes.ALL
        val labels = scopes.map { scopeLabel(activity, it.id) }.toTypedArray()
        val checked = BooleanArray(scopes.size) { scopes[it].id in preselected }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.template_section_scopes)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton(R.string.batch_action_cancel, null)
            .setPositiveButton(R.string.batch_confirm_execute) { _, _ ->
                onPicked(scopes.filterIndexed { index, _ -> checked[index] }.map { it.id })
            }
            .create()
        dialog.show()
        GlassWindow.applyIfGlass(dialog)
    }

    // ══════════ F7：模板套用与差异预览 ══════════

    /**
     * 多选 → 套用模板：先选模板，再按应用逐个算差异（新增/保留/不涉及），
     * 确认后执行「只新增」的授予。
     */
    fun pickTemplateAndApply(activity: Activity, apps: List<BatchApp>, onFinished: (() -> Unit)? = null) {
        if (apps.isEmpty()) return
        val templates = TemplateStore.all(activity)
        if (templates.isEmpty()) {
            Toast.makeText(activity, R.string.template_none, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = templates.map { t ->
            val badge = if (t.builtIn) " [${activity.getString(R.string.template_builtin)}]" else ""
            "${t.name}$badge · ${t.itemCount}"
        }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.template_choose_title)
            .setItems(labels) { _, which ->
                val template = templates[which]
                showApplyPreview(activity, apps, template, onFinished)
            }
            .setNegativeButton(R.string.batch_action_cancel, null)
            .create()
        dialog.show()
        GlassWindow.applyIfGlass(dialog)
    }

    /** 单个应用的差异（F7 预览用）。 */
    data class AppDiff(
        val app: BatchApp,
        val addPerms: List<String>,
        val keepPerms: List<String>,
        val addScopes: List<String>,
        val keepScopes: List<String>,
        val untouchedScopes: List<String>,
    )

    private fun computeDiff(
        context: Context,
        apps: List<BatchApp>,
        template: PermissionTemplate,
    ): List<AppDiff> {
        val shizukuSnapshot = BatchExecutor.snapshotShizuku(apps)
        val dhizukuSnapshot = if (template.dhizukuScopes.isEmpty()) emptyList()
        else BatchExecutor.snapshotDhizuku(context, apps)
        return apps.map { app ->
            val permGranted = shizukuSnapshot.firstOrNull { it.packageName == app.packageName }?.hadPermission == true
            val addPerms = if (permGranted) emptyList() else template.shizukuPermissions
            val keepPerms = if (permGranted) template.shizukuPermissions else emptyList()
            val existingScopes = dhizukuSnapshot.firstOrNull { it.packageName == app.packageName }
                ?.existingScopes ?: emptyList()
            val addScopes = template.dhizukuScopes.filter { it !in existingScopes }
            val keepScopes = template.dhizukuScopes.filter { it in existingScopes }
            val untouched = existingScopes.filter { it !in template.dhizukuScopes }
            AppDiff(app, addPerms, keepPerms, addScopes, keepScopes, untouched)
        }
    }

    private fun showApplyPreview(
        activity: Activity,
        apps: List<BatchApp>,
        template: PermissionTemplate,
        onFinished: (() -> Unit)?,
    ) {
        Thread {
            val diffs = computeDiff(activity, apps, template)
            main.post {
                if (activity.isFinishing) return@post
                showApplyPreviewDialog(activity, template, diffs, onFinished)
            }
        }.start()
    }

    private fun showApplyPreviewDialog(
        activity: Activity,
        template: PermissionTemplate,
        diffs: List<AppDiff>,
        onFinished: (() -> Unit)?,
    ) {
        val context = activity
        val content = column(context)

        diffs.forEach { diff ->
            val header = text(
                context,
                "${diff.app.label}\n" + context.getString(
                    R.string.template_apply_per_app,
                    diff.addPerms.size + diff.addScopes.size,
                    diff.keepPerms.size + diff.keepScopes.size,
                    diff.untouchedScopes.size,
                ),
                13f,
            )
            content.addView(header)
            val details = column(context)
            details.visibility = View.GONE
            if (diff.addPerms.isNotEmpty() || diff.addScopes.isNotEmpty()) {
                details.addView(text(context, context.getString(R.string.template_apply_add), 12f))
                diff.addPerms.forEach { details.addView(bullet(context, permLabel(context, it))) }
                diff.addScopes.forEach { details.addView(bullet(context, scopeLabel(context, it))) }
            }
            if (diff.keepPerms.isNotEmpty() || diff.keepScopes.isNotEmpty()) {
                details.addView(text(context, context.getString(R.string.template_apply_keep), 12f))
                diff.keepPerms.forEach { details.addView(bullet(context, permLabel(context, it))) }
                diff.keepScopes.forEach { details.addView(bullet(context, scopeLabel(context, it))) }
            }
            if (diff.untouchedScopes.isNotEmpty()) {
                details.addView(text(context, context.getString(R.string.template_apply_untouched), 12f))
                diff.untouchedScopes.forEach { details.addView(bullet(context, scopeLabel(context, it))) }
            }
            content.addView(details)
            header.setOnClickListener {
                details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
            content.addView(divider(context))
        }

        if (template.dhizukuScopes.isNotEmpty()) {
            content.addView(text(context, context.getString(R.string.template_scope_hint), 12f).apply { alpha = 0.7f })
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.template_apply_preview_title, template.name))
            .setView(scroll(content))
            .setNegativeButton(R.string.batch_action_cancel, null)
            .setPositiveButton(R.string.template_apply_confirm) { _, _ ->
                applyTemplate(activity, template, diffs, onFinished)
            }
            .create()
        dialog.show()
        GlassWindow.applyIfGlass(dialog)
    }

    /** 执行套用：Shizuku 权限一段 + Dhizuku scopes 一段，共用一个进度框与结果报告。 */
    private fun applyTemplate(
        activity: Activity,
        template: PermissionTemplate,
        diffs: List<AppDiff>,
        onFinished: (() -> Unit)?,
    ) {
        val appContext = activity.applicationContext
        val runShizuku = diffs.any { it.addPerms.isNotEmpty() }
        val runDhizuku = template.dhizukuScopes.isNotEmpty() && diffs.any { it.addScopes.isNotEmpty() }
        if (!runShizuku && !runDhizuku) {
            Toast.makeText(activity, R.string.batch_reason_already, Toast.LENGTH_SHORT).show()
            return
        }

        val cancelled = AtomicBoolean(false)
        val total = diffs.size * ((if (runShizuku) 1 else 0) + (if (runDhizuku) 1 else 0))
        val progress = ProgressDialog(activity, total) { cancelled.set(true) }.apply { show() }

        Thread {
            val records = ArrayList<BatchOperationRecord>()
            var aborted = false
            var abortReason: String? = null
            var done = 0

            if (runShizuku) {
                val batchId = UUID.randomUUID().toString()
                val apps = diffs.map { it.app }
                val snapshot = BatchExecutor.snapshotShizuku(apps)
                val result = BatchExecutor.runShizuku(
                    appContext,
                    apps,
                    BatchType.GRANT,
                    batchId,
                    uninstalledIsSkip = true,
                    isCancelled = { cancelled.get() },
                    onProgress = { _, _, app -> progress.postUpdate(done, total, app) },
                )
                done += result.items.size
                aborted = aborted || result.aborted
                abortReason = abortReason ?: result.abortReason
                records.add(
                    BatchOperationRecord(
                        id = batchId,
                        type = BatchType.GRANT,
                        target = BatchTarget.SHIZUKU,
                        timestamp = System.currentTimeMillis(),
                        items = result.items,
                        snapshot = snapshot,
                        templateName = template.name,
                    ),
                )
                BatchStore.append(appContext, records.last())
            }

            if (runDhizuku && !aborted && !cancelled.get()) {
                val batchId = UUID.randomUUID().toString()
                val apps = diffs.map { it.app }
                val snapshot = BatchExecutor.snapshotDhizuku(appContext, apps)
                val result = BatchExecutor.runDhizuku(
                    appContext,
                    apps,
                    BatchType.GRANT,
                    template.dhizukuScopes,
                    batchId,
                    uninstalledIsSkip = true,
                    isCancelled = { cancelled.get() },
                    onProgress = { _, _, app -> progress.postUpdate(done, total, app) },
                )
                aborted = aborted || result.aborted
                abortReason = abortReason ?: result.abortReason
                records.add(
                    BatchOperationRecord(
                        id = batchId,
                        type = BatchType.GRANT,
                        target = BatchTarget.DHIZUKU_DELEGATE,
                        timestamp = System.currentTimeMillis(),
                        items = result.items,
                        snapshot = snapshot,
                        scopes = template.dhizukuScopes,
                        templateName = template.name,
                    ),
                )
                BatchStore.append(appContext, records.last())
            }

            main.post {
                progress.dismiss()
                if (activity.isFinishing) return@post
                onFinished?.invoke()
                showResultDialog(activity, records, aborted, abortReason, cancelled.get())
            }
        }.start()
    }

    // ══════════ 进度对话框 ══════════

    private class ProgressDialog(
        private val activity: Activity,
        private val total: Int,
        private val onCancel: () -> Unit,
    ) {
        private var dialog: AlertDialog? = null
        private var bar: ProgressBar? = null
        private var countText: TextView? = null
        private var currentText: TextView? = null

        fun show() {
            val content = column(activity)
            val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
            progress.max = total.coerceAtLeast(1)
            progress.progress = 0
            content.addView(progress)
            bar = progress

            val count = text(activity, activity.getString(R.string.batch_progress_count, 0, total), 13f)
            content.addView(count)
            countText = count

            val current = text(activity, "", 12f).apply { alpha = 0.7f }
            content.addView(current)
            currentText = current

            dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.batch_progress_title)
                .setView(content)
                .setNegativeButton(R.string.batch_progress_cancel, null)
                .setCancelable(false)
                .create()
            dialog?.show()
            GlassWindow.applyIfGlass(dialog)
            // 「取消」只置取消标志，不关对话框：队列要一个完整的结果（已处理项保留），
            // 关掉弹窗会让用户以为已经中断。
            dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.let { button ->
                button.setOnClickListener {
                    onCancel()
                    button.isEnabled = false
                    button.text = activity.getString(R.string.batch_progress_cancel)
                }
            }
        }

        fun postUpdate(done: Int, total: Int, app: BatchApp) {
            main.post {
                if (activity.isFinishing) return@post
                bar?.let {
                    it.max = total.coerceAtLeast(1)
                    it.progress = done
                }
                countText?.text = activity.getString(R.string.batch_progress_count, done, total)
                currentText?.text = activity.getString(R.string.batch_progress_current, app.label)
            }
        }

        fun dismiss() {
            runCatching { dialog?.dismiss() }
            dialog = null
        }
    }

    // ══════════ 小工具 ══════════

    /** 包名 → 应用展示名；应用已卸载时回退成包名（结果报告不能因为卸载就显示空白）。 */
    fun appLabel(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    fun scopeLabel(context: Context, scopeId: String): String {
        val res = DhizukuScopes.labelRes(scopeId) ?: return scopeId
        return context.getString(res)
    }

    private fun permLabel(context: Context, permId: String): String {
        val res = ShizukuPermissions.labelRes(permId) ?: return permId
        return context.getString(res)
    }

    private fun column(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(context, 20)
        setPadding(pad, dp(context, 8), pad, dp(context, 4))
    }

    /**
     * 限制最大高度的滚动容器：应用很多时确认框不至于撑出屏幕，
     * 内容少时又不会留一大片空白（AT_MOST 让短内容按实际高度收）。
     */
    private class MaxHeightScrollView(context: Context) : ScrollView(context) {
        private val maxHeight: Int =
            (context.resources.displayMetrics.heightPixels * 0.55f).toInt()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val capped = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, capped)
        }
    }

    private fun scroll(content: View): View = MaxHeightScrollView(content.context).apply {
        addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun text(context: Context, value: String, sizeSp: Float = 14f): TextView =
        TextView(context).apply {
            text = value
            textSize = sizeSp
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        }

    private fun bullet(context: Context, value: String): TextView =
        text(context, "· $value", 12f).apply { alpha = 0.85f }

    private fun divider(context: Context): View = View(context).apply {
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1))
        params.topMargin = dp(context, 8)
        params.bottomMargin = dp(context, 4)
        layoutParams = params
        setBackgroundColor(
            ContextCompat.getColor(context, android.R.color.darker_gray),
        )
        alpha = 0.25f
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun errorColor(context: Context): Int? = runCatching {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(com.google.android.material.R.attr.colorError, tv, true)) {
            return null
        }
        if (tv.resourceId != 0) ContextCompat.getColor(context, tv.resourceId) else tv.data
    }.getOrNull()
}
