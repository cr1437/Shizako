package moe.shizuku.manager.management;

import android.content.Context;
import android.content.pm.PackageInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import rikka.recyclerview.BaseRecyclerViewAdapter;
import rikka.recyclerview.ClassCreatorPool;

public class AppsAdapter extends BaseRecyclerViewAdapter<ClassCreatorPool> {

    private final Context context;
    private List<PackageInfo> fullData = new ArrayList<>();
    private String query = "";

    /** 【性能】应用标签的小写缓存：搜索时不再对全表反复 loadLabel。 */
    private final Map<String, String> labelCache = new HashMap<>();

    public AppsAdapter(Context context) {
        super();
        this.context = context.getApplicationContext();

        getCreatorPool().putRule(PackageInfo.class, AppViewHolder.CREATOR);
        getCreatorPool().putRule(Object.class, EmptyViewHolder.CREATOR);
        setHasStableIds(true);
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
        // 列表数据换了：标签缓存一并失效
        labelCache.clear();
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
}