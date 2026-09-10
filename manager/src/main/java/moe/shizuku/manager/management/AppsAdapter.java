package moe.shizuku.manager.management;

import android.content.Context;
import android.content.pm.PackageInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import rikka.recyclerview.BaseRecyclerViewAdapter;
import rikka.recyclerview.ClassCreatorPool;

public class AppsAdapter extends BaseRecyclerViewAdapter<ClassCreatorPool> {

    private final Context context;
    private List<PackageInfo> fullData = new ArrayList<>();
    private String query = "";

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
            CharSequence label = pi.applicationInfo.loadLabel(context.getPackageManager());
            return label != null && label.toString().toLowerCase(Locale.ROOT).contains(q);
        }
        return false;
    }
}
