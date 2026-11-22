package moe.shizuku.manager.batch

import android.content.Context
import android.content.pm.PackageManager
import moe.shizuku.manager.R
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.dhizuku.DhizukuScopes
import moe.shizuku.manager.dhizuku.DhizukuSettings
import rikka.shizuku.Shizuku

/**
 * 批量执行的**内核**（F2 / F3 / F5 撤销 / F7 套用）。
 *
 * 设计约束（来自交接文档与本次规格）：
 * - **不重写单应用授权逻辑**：Shizuku 侧仍然调用 [AuthorizationManager.grant] / [revoke]，
 *   Dhizuku 侧仍然走 [DhizukuSettings.grant] / [revoke] + [DhizukuScopes.write]，这里只做循环、
 *   快照、失败归因和审计。
 * - 单项失败不中断：每一项都独立 try/catch，失败原因落进 [BatchItemResult.reason]。
 * - 服务中途停掉：立即暂停队列（未处理的项不进入结果），已处理项保留。
 * - 撤销批次只碰「本批次新增的」：以 [AppPermissionSnapshot] 为准。
 *
 * 所有方法都在**后台线程**调用（内部有 binder IPC 与 PackageManager 调用），
 * 回调由调用方自己切主线程。
 */
object BatchExecutor {

    /** 执行结果。items 只包含「真正处理过」的项（被取消/服务停止后剩下的不入列）。 */
    class RunResult(
        val items: List<BatchItemResult>,
        val aborted: Boolean = false,
        val abortReason: String? = null,
        val cancelled: Boolean = false,
    )

    /** 确认框 / 差异预览用的预判结果。 */
    data class PlanItem(
        val app: BatchApp,
        val skip: Boolean,
        val skipReason: String?,
    )

    // ---------------- 快照 ----------------

    fun snapshotShizuku(apps: List<BatchApp>): List<AppPermissionSnapshot> = apps.map { app ->
        AppPermissionSnapshot(
            packageName = app.packageName,
            uid = app.uid,
            hadPermission = AuthorizationManager.granted(app.packageName, app.uid),
            existingScopes = null,
        )
    }

    fun snapshotDhizuku(context: Context, apps: List<BatchApp>): List<AppPermissionSnapshot> =
        apps.map { app ->
            AppPermissionSnapshot(
                packageName = app.packageName,
                uid = app.uid,
                hadPermission = DhizukuSettings.isGranted(app.uid),
                existingScopes = DhizukuScopes.read(context, app.packageName),
            )
        }

    fun snapshot(context: Context, target: BatchTarget, apps: List<BatchApp>): List<AppPermissionSnapshot> =
        when (target) {
            BatchTarget.SHIZUKU -> snapshotShizuku(apps)
            BatchTarget.DHIZUKU_DELEGATE -> snapshotDhizuku(context, apps)
        }

    // ---------------- 预判（F4 的「已存在，将跳过」与 F7 的差异预览） ----------------

    fun plan(
        context: Context,
        target: BatchTarget,
        type: BatchType,
        apps: List<BatchApp>,
        scopes: List<String>,
        snapshot: List<AppPermissionSnapshot> = snapshot(context, target, apps),
    ): List<PlanItem> = apps.map { app ->
        val snap = snapshot.firstOrNull { it.packageName == app.packageName }
        when (target) {
            BatchTarget.SHIZUKU -> {
                val granted = snap?.hadPermission ?: false
                when (type) {
                    BatchType.GRANT -> if (granted) {
                        PlanItem(app, true, context.getString(R.string.batch_reason_already))
                    } else PlanItem(app, false, null)

                    BatchType.REVOKE -> if (!granted) {
                        PlanItem(app, true, context.getString(R.string.batch_reason_not_granted))
                    } else PlanItem(app, false, null)
                }
            }

            BatchTarget.DHIZUKU_DELEGATE -> {
                val granted = snap?.hadPermission ?: false
                val existing = snap?.existingScopes ?: emptyList()
                when (type) {
                    BatchType.GRANT -> {
                        val missing = scopes.filter { it !in existing }
                        if (granted && missing.isEmpty()) {
                            PlanItem(app, true, context.getString(R.string.batch_reason_already))
                        } else PlanItem(app, false, null)
                    }

                    BatchType.REVOKE -> if (!granted && existing.isEmpty()) {
                        PlanItem(app, true, context.getString(R.string.batch_reason_not_granted))
                    } else PlanItem(app, false, null)
                }
            }
        }
    }

    // ---------------- 执行：Shizuku 权限 ----------------

    fun runShizuku(
        context: Context,
        apps: List<BatchApp>,
        type: BatchType,
        batchId: String,
        /** F7：模板套用时「应用已卸载」算 SKIPPED（规格 F7），普通批量算 FAILED（规格 §5） */
        uninstalledIsSkip: Boolean = false,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int, app: BatchApp) -> Unit = { _, _, _ -> },
    ): RunResult {
        val results = ArrayList<BatchItemResult>(apps.size)
        var done = 0
        for (app in apps) {
            if (isCancelled()) return RunResult(results, cancelled = true)
            if (!serviceAvailable()) {
                return RunResult(results, aborted = true, abortReason = str(context, R.string.batch_reason_service_stopped))
            }
            onProgress(done, apps.size, app)

            val result = processShizukuItem(context, app, type, uninstalledIsSkip)
            results.add(result)
            audit(context, type, BatchTarget.SHIZUKU, batchId, app, result)
            done++
            onProgress(done, apps.size, app)
        }
        return RunResult(results)
    }

    private fun processShizukuItem(
        context: Context,
        app: BatchApp,
        type: BatchType,
        uninstalledIsSkip: Boolean,
    ): BatchItemResult {
        if (!isInstalled(context, app.packageName)) {
            return if (uninstalledIsSkip) {
                skipped(app, str(context, R.string.batch_reason_uninstalled))
            } else {
                failed(app, str(context, R.string.batch_reason_uninstalled))
            }
        }
        val before = AuthorizationManager.granted(app.packageName, app.uid)
        return try {
            when (type) {
                BatchType.GRANT -> {
                    if (before) {
                        skipped(app, str(context, R.string.batch_reason_already))
                    } else {
                        AuthorizationManager.grant(app.packageName, app.uid)
                        verifyShizuku(context, app, expected = true, type = type)
                    }
                }

                BatchType.REVOKE -> {
                    if (!before) {
                        skipped(app, str(context, R.string.batch_reason_not_granted))
                    } else {
                        AuthorizationManager.revoke(app.packageName, app.uid)
                        verifyShizuku(context, app, expected = false, type = type)
                    }
                }
            }
        } catch (e: SecurityException) {
            failed(app, str(context, R.string.batch_reason_denied) + ": " + (e.message ?: ""))
        } catch (e: Throwable) {
            failed(app, (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun verifyShizuku(
        context: Context,
        app: BatchApp,
        expected: Boolean,
        @Suppress("UNUSED_PARAMETER") type: BatchType,
    ): BatchItemResult {
        if (!serviceAvailable()) {
            return failed(app, str(context, R.string.batch_reason_service_stopped))
        }
        val now = AuthorizationManager.granted(app.packageName, app.uid)
        return if (now == expected) {
            BatchItemResult(app.packageName, app.uid, ItemStatus.SUCCESS)
        } else {
            failed(app, str(context, R.string.batch_reason_verify_failed))
        }
    }

    // ---------------- 执行：Dhizuku 委派 ----------------

    fun runDhizuku(
        context: Context,
        apps: List<BatchApp>,
        type: BatchType,
        scopes: List<String>,
        batchId: String,
        uninstalledIsSkip: Boolean = false,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int, app: BatchApp) -> Unit = { _, _, _ -> },
    ): RunResult {
        // 非 Device Owner：直接拒绝整批（规格 §5）
        if (!DhizukuSettings.isDeviceOwner(context)) {
            return RunResult(
                emptyList(),
                aborted = true,
                abortReason = str(context, R.string.batch_need_owner),
            )
        }
        val results = ArrayList<BatchItemResult>(apps.size)
        var done = 0
        for (app in apps) {
            if (isCancelled()) return RunResult(results, cancelled = true)
            onProgress(done, apps.size, app)

            val result = processDhizukuItem(context, app, type, scopes, uninstalledIsSkip)
            results.add(result)
            audit(context, type, BatchTarget.DHIZUKU_DELEGATE, batchId, app, result)
            done++
            onProgress(done, apps.size, app)
        }
        return RunResult(results)
    }

    private fun processDhizukuItem(
        context: Context,
        app: BatchApp,
        type: BatchType,
        scopes: List<String>,
        uninstalledIsSkip: Boolean,
    ): BatchItemResult {
        if (!isInstalled(context, app.packageName)) {
            return if (uninstalledIsSkip) {
                skipped(app, str(context, R.string.batch_reason_uninstalled))
            } else {
                failed(app, str(context, R.string.batch_reason_uninstalled))
            }
        }
        if (scopes.isNotEmpty() && !DhizukuScopes.isSupported()) {
            return failed(app, str(context, R.string.batch_reason_not_supported))
        }
        val wasGranted = DhizukuSettings.isGranted(app.uid)
        val existing = DhizukuScopes.read(context, app.packageName)
        return try {
            when (type) {
                BatchType.GRANT -> {
                    val missing = scopes.filter { it !in existing }
                    if (wasGranted && missing.isEmpty()) {
                        return skipped(app, str(context, R.string.batch_reason_already))
                    }
                    // 白名单 + scopes 合并（只增不删：模板套用/批量授予都不该顺手撤销既有委派）
                    DhizukuSettings.grant(app.uid)
                    if (missing.isNotEmpty()) {
                        val error = DhizukuScopes.write(context, app.packageName, existing + missing)
                        if (error != null) return failed(app, error)
                    }
                    val nowGranted = DhizukuSettings.isGranted(app.uid)
                    val nowScopes = DhizukuScopes.read(context, app.packageName)
                    if (nowGranted && (scopes.isEmpty() || scopes.all { it in nowScopes })) {
                        BatchItemResult(app.packageName, app.uid, ItemStatus.SUCCESS)
                    } else {
                        failed(app, str(context, R.string.batch_reason_verify_failed))
                    }
                }

                BatchType.REVOKE -> {
                    if (!wasGranted && existing.isEmpty()) {
                        return skipped(app, str(context, R.string.batch_reason_not_granted))
                    }
                    DhizukuSettings.revoke(app.uid)
                    if (existing.isNotEmpty()) {
                        val error = DhizukuScopes.write(context, app.packageName, emptyList())
                        if (error != null) return failed(app, error)
                    }
                    if (!DhizukuSettings.isGranted(app.uid) &&
                        DhizukuScopes.read(context, app.packageName).isEmpty()
                    ) {
                        BatchItemResult(app.packageName, app.uid, ItemStatus.SUCCESS)
                    } else {
                        failed(app, str(context, R.string.batch_reason_verify_failed))
                    }
                }
            }
        } catch (e: SecurityException) {
            failed(app, str(context, R.string.batch_reason_denied) + ": " + (e.message ?: ""))
        } catch (e: Throwable) {
            failed(app, (e.message ?: e.javaClass.simpleName))
        }
    }

    // ---------------- F5：撤销本批次 ----------------

    /**
     * 撤销一个「授予」批次：只还原该批次**新增**的授权。
     *
     * - 快照里 `hadPermission = true` 的项：什么都不做（原有授权）；
     *   Dhizuku 委派的 scopes 会被写回快照里的旧值，从而只移除本批次新增的部分。
     * - 快照里 `hadPermission = false` 的项：撤销（Dhizuku 还会把 scopes 清回旧值）。
     * - 应用已卸载：记 SKIPPED「已不存在」，不算失败。
     */
    fun undoRecord(
        context: Context,
        record: BatchOperationRecord,
        undoBatchId: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int, app: BatchApp) -> Unit = { _, _, _ -> },
    ): RunResult {
        val successItems = record.items.filter { it.status == ItemStatus.SUCCESS }
        if (successItems.isEmpty()) {
            return RunResult(emptyList(), aborted = true, abortReason = str(context, R.string.batch_reason_gone))
        }
        if (record.target == BatchTarget.SHIZUKU && !serviceAvailable()) {
            return RunResult(
                emptyList(),
                aborted = true,
                abortReason = str(context, R.string.batch_reason_service_stopped),
            )
        }
        if (record.target == BatchTarget.DHIZUKU_DELEGATE && !DhizukuSettings.isDeviceOwner(context)) {
            return RunResult(emptyList(), aborted = true, abortReason = str(context, R.string.batch_need_owner))
        }

        val results = ArrayList<BatchItemResult>(successItems.size)
        var done = 0
        for (item in successItems) {
            if (isCancelled()) return RunResult(results, cancelled = true)
            val app = BatchApp(item.packageName, item.uid, item.packageName)
            onProgress(done, successItems.size, app)

            val result = when (record.target) {
                BatchTarget.SHIZUKU -> undoShizukuItem(context, record, item)
                BatchTarget.DHIZUKU_DELEGATE -> undoDhizukuItem(context, record, item)
            }
            results.add(result)
            if (result.status != ItemStatus.SKIPPED) {
                ApiAuditLog.record(
                    context,
                    func = "batch.undo",
                    allowed = result.status == ItemStatus.SUCCESS,
                    detail = detailOf(undoBatchId, "UNDO", record.target, result),
                )
            }
            done++
            onProgress(done, successItems.size, app)
        }
        return RunResult(results)
    }

    private fun undoShizukuItem(
        context: Context,
        record: BatchOperationRecord,
        item: BatchItemResult,
    ): BatchItemResult {
        val snap = record.snapshotOf(item.packageName)
        // 快照里本来就有权限 → 不是本批次新增的，不动
        if (snap?.hadPermission == true) {
            return skipped(item, str(context, R.string.batch_reason_already))
        }
        if (!isInstalled(context, item.packageName)) {
            return skipped(item, str(context, R.string.batch_reason_gone))
        }
        if (!serviceAvailable()) {
            return failed(item, str(context, R.string.batch_reason_service_stopped))
        }
        return try {
            AuthorizationManager.revoke(item.packageName, item.uid)
            if (!AuthorizationManager.granted(item.packageName, item.uid)) {
                BatchItemResult(item.packageName, item.uid, ItemStatus.SUCCESS)
            } else {
                failed(item, str(context, R.string.batch_reason_verify_failed))
            }
        } catch (e: Throwable) {
            failed(item, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun undoDhizukuItem(
        context: Context,
        record: BatchOperationRecord,
        item: BatchItemResult,
    ): BatchItemResult {
        val snap = record.snapshotOf(item.packageName)
        if (!isInstalled(context, item.packageName)) {
            // 应用已卸载：白名单条目顺手清掉，但按「已不存在」记 SKIPPED（不计失败）
            runCatching { DhizukuSettings.revoke(item.uid) }
            return skipped(item, str(context, R.string.batch_reason_gone))
        }
        val oldScopes = snap?.existingScopes ?: emptyList()
        return try {
            if (snap?.hadPermission != true) {
                // 本批次新加的白名单：撤掉
                DhizukuSettings.revoke(item.uid)
            }
            // 无论原来有没有白名单，都只把 scopes 写回快照旧值 —— 本批次新增的 scopes 被移除，
            // 用户原有的 scopes 原样保留。
            if (DhizukuScopes.isSupported()) {
                val error = DhizukuScopes.write(context, item.packageName, oldScopes)
                if (error != null) return failed(item, error)
            }
            val grantedOk = snap?.hadPermission == true || !DhizukuSettings.isGranted(item.uid)
            val scopesOk = !DhizukuScopes.isSupported() ||
                DhizukuScopes.read(context, item.packageName).sorted() == oldScopes.sorted()
            if (grantedOk && scopesOk) {
                BatchItemResult(item.packageName, item.uid, ItemStatus.SUCCESS)
            } else {
                failed(item, str(context, R.string.batch_reason_verify_failed))
            }
        } catch (e: Throwable) {
            failed(item, e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------------- 小工具 ----------------

    private fun audit(
        context: Context,
        type: BatchType,
        target: BatchTarget,
        batchId: String,
        app: BatchApp,
        result: BatchItemResult,
    ) {
        ApiAuditLog.record(
            context,
            func = "batch." + (if (type == BatchType.GRANT) "grant" else "revoke") + "." +
                (if (target == BatchTarget.SHIZUKU) "shizuku" else "dhizuku"),
            allowed = result.status == ItemStatus.SUCCESS,
            detail = detailOf(batchId, type.name, target, result),
        )
    }

    private fun detailOf(
        batchId: String,
        type: String,
        target: BatchTarget,
        result: BatchItemResult,
    ): String = buildString {
        append("src=ui;batch=").append(batchId)
        append(";type=").append(type)
        append(";target=").append(target.name)
        append(";pkg=").append(result.packageName)
        append(";uid=").append(result.uid)
        append(";result=").append(result.status.name)
        append(";reason=").append(result.reason ?: "")
    }

    private fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: Throwable) {
        // PackageManager 偶发 RemoteException 之类：当作还在，真正操作时再报错
        true
    }

    private fun serviceAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    private fun str(context: Context, res: Int): String = context.getString(res)

    private fun failed(app: BatchApp, reason: String): BatchItemResult =
        BatchItemResult(app.packageName, app.uid, ItemStatus.FAILED, reason)

    private fun failed(item: BatchItemResult, reason: String): BatchItemResult =
        BatchItemResult(item.packageName, item.uid, ItemStatus.FAILED, reason)

    private fun skipped(app: BatchApp, reason: String): BatchItemResult =
        BatchItemResult(app.packageName, app.uid, ItemStatus.SKIPPED, reason)

    private fun skipped(item: BatchItemResult, reason: String): BatchItemResult =
        BatchItemResult(item.packageName, item.uid, ItemStatus.SKIPPED, reason)
}
