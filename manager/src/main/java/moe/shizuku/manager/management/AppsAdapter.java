package moe.shizuku.manager.management;

import android.content.Context;
import android.content.pm.PackageInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import rikka.recyclerview.BaseRecyclerViewAdapter;
import rikka.recyclerview.ClassCreatorPool;

public class AppsAdapter extends BaseRecyclerViewAdapter<ClassCreatorPool> {

    /**
     * 多选状态回调（F1）：Fragment 据此更新标题与底部操作栏。
     * 选择态维护在适配器里（它知道每一行的绑定状态），Fragment 只读不写。
     */
    public interface Listener {
        void onSelectionChanged();

        void onEnterSelectionMode();
    }

    private final Context context;
    private List<PackageInfo> fullData = new ArrayList<>();
    private String query = "";

    /** 【性能】应用标签的小写缓存：搜索时不再对全表反复 loadLabel。 */
    private final Map<String, String> labelCache = new HashMap<>();

    private boolean selectionMode = false;
    /** 按包名记选择（不是 position）：列表刷新/搜索过滤后选择不会串位 */
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private Listener listener;

    public AppsAdapter(Context context) {
        super();
        this.context = context.getApplicationContext();

        getCreatorPool().putRule(PackageInfo.class, AppViewHolder.CREATOR);
        getCreatorPool().putRule(Object.class, EmptyViewHolder.CREATOR);
        setHasStableIds(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public long getItemId(int position) {
        return getItemAt(position).hashCode();
    }

    @Override
    public ClassCreatorPool onCreateCreatorPool() {
        return new ClassCreatorPool();
    }

    public void updateData(List<PackageInfo> data) {
        fullData = data != null ? data : new ArrayList<>();
        // 列表数据换了：标签缓存一并失效；被卸载/消失的应用从选择集里剔除
        labelCache.clear();
        if (!selected.isEmpty()) {
            LinkedHashSet<String> alive = new LinkedHashSet<>();
            for (PackageInfo pi : fullData) {
                if (pi.packageName != null && selected.contains(pi.packageName)) {
                    alive.add(pi.packageName);
                }
            }
            if (alive.size() != selected.size()) {
                selected.clear();
                selected.addAll(alive);
                notifyListener();
            }
        }
        applyFilter();
    }

    public void setQuery(CharSequence q) {
        query = q == null ? "" : q.toString();
        applyFilter();
    }

    private void applyFilter() {
        getItems().clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<PackageInfo> result = new ArrayList<>();
        for (PackageInfo pi : fullData) {
            if (q.isEmpty() || matches(pi, q)) {
                result.add(pi);
            }
        }
        if (result.isEmpty()) {
            getItems().add(new Object());
        } else {
            getItems().addAll(result);
        }
        notifyDataSetChanged();
    }

    private boolean matches(PackageInfo pi, String q) {
        if (pi.packageName != null && pi.packageName.toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (pi.applicationInfo != null) {
            return cachedLabel(pi).contains(q);
        }
        return false;
    }

    private String cachedLabel(PackageInfo pi) {
        String key = pi.packageName + '/' + pi.applicationInfo.uid;
        String label = labelCache.get(key);
        if (label == null) {
            CharSequence cs = pi.applicationInfo.loadLabel(context.getPackageManager());
            label = cs != null ? cs.toString().toLowerCase(Locale.ROOT) : "";
            labelCache.put(key, label);
        }
        return label;
    }

    // ---------------- 多选（F1） ----------------

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public boolean isSelected(String packageName) {
        return packageName != null && selected.contains(packageName);
    }

    public int selectedCount() {
        return selected.size();
    }

    public void setSelectionMode(boolean enabled) {
        if (selectionMode == enabled) {
            return;
        }
        selectionMode = enabled;
        if (!enabled) {
            selected.clear();
        }
        notifyDataSetChanged();
        notifyListener();
    }

    /** 长按：未进入多选则先进入，然后切换该行选中态。 */
    public void onItemLongClick(PackageInfo info) {
        if (info == null || info.packageName == null) {
            return;
        }
        if (!selectionMode) {
            selectionMode = true;
            if (listener != null) {
                listener.onEnterSelectionMode();
            }
        }
        toggleSelection(info);
    }

    public void toggleSelection(PackageInfo info) {
        if (info == null || info.packageName == null) {
            return;
        }
        if (!selected.remove(info.packageName)) {
            selected.add(info.packageName);
        }
        notifyDataSetChanged();
        notifyListener();
    }

    public void selectAll() {
        for (PackageInfo pi : visiblePackages()) {
            if (pi.packageName != null) {
                selected.add(pi.packageName);
            }
        }
        notifyDataSetChanged();
        notifyListener();
    }

    public void invertSelection() {
        for (PackageInfo pi : visiblePackages()) {
            if (pi.packageName == null) {
                continue;
            }
            if (!selected.remove(pi.packageName)) {
                selected.add(pi.packageName);
            }
        }
        notifyDataSetChanged();
        notifyListener();
    }

    public void clearSelection() {
        selected.clear();
        notifyDataSetChanged();
        notifyListener();
    }

    /** 当前可见（经过搜索过滤）的 PackageInfo。 */
    private List<PackageInfo> visiblePackages() {
        List<PackageInfo> out = new ArrayList<>();
        for (Object item : getItems()) {
            if (item instanceof PackageInfo) {
                out.add((PackageInfo) item);
            }
        }
        return out;
    }

    /** 选中项（按列表顺序，批量执行时按界面顺序走）。 */
    public List<PackageInfo> selectedPackages() {
        List<PackageInfo> out = new ArrayList<>();
        for (Object item : getItems()) {
            if (item instanceof PackageInfo) {
                PackageInfo pi = (PackageInfo) item;
                if (pi.packageName != null && selected.contains(pi.packageName)) {
                    out.add(pi);
                }
            }
        }
        return out;
    }

    private void notifyListener() {
        if (listener != null) {
            listener.onSelectionChanged();
        }
    }
}
