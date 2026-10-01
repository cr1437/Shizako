package rikka.shizuku.server;

import android.content.pm.PackageInfo;
import android.content.pm.UserInfo;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reflection based compatibility layer for framework ABI changes that break the
 * prebuilt hidden-api compat library (4.4.0) bundled with this fork.
 *
 * <p>Known breakages on newer Android versions:
 * <ul>
 *   <li>Android 17 (API 37) changed the return type of
 *   {@code IPackageManager#getInstalledPackages(long, int)} from
 *   {@code ParceledListSlice} to {@code PackageInfoList}. Calling it through the
 *   old compiled stub throws {@link NoSuchMethodError} and the applications list
 *   becomes empty. The call is made reflectively here and the result is unwrapped
 *   via the common {@code getList()} method.</li>
 *   <li>{@code IUserManager#getUsers} descriptors differ between OS versions, so
 *   the method is probed among the known signatures.</li>
 * </ul>
 *
 * <p>The "NoThrow" contract matches the hidden-api compat classes: on any
 * failure an empty list is returned (user ids fall back to {@code {0}} like the
 * old library did, so at least the primary user keeps working).
 */
public final class ApiCompat {

    private static final String TAG = "ShizukuApiCompat";

    private ApiCompat() {
    }

    private static volatile Object sPackageManager;
    private static volatile Object sUserManager;

    // ------------------------------------------------------------- services

    private static Object getSystemService(String name, String stubClass) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, name);
        if (binder == null) {
            throw new IllegalStateException("service not found: " + name);
        }
        return Class.forName(stubClass)
                .getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
    }

    private static Object getPackageManager() throws Exception {
        Object pm = sPackageManager;
        if (pm == null) {
            synchronized (ApiCompat.class) {
                pm = sPackageManager;
                if (pm == null) {
                    pm = getSystemService("package", "android.content.pm.IPackageManager$Stub");
                    sPackageManager = pm;
                }
            }
        }
        return pm;
    }

    private static Object getUserManager() throws Exception {
        Object um = sUserManager;
        if (um == null) {
            synchronized (ApiCompat.class) {
                um = sUserManager;
                if (um == null) {
                    um = getSystemService("user", "android.os.IUserManager$Stub");
                    sUserManager = um;
                }
            }
        }
        return um;
    }

    // ---------------------------------------------------------- reflection

    private static Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        try {
            Method method = clazz.getMethod(name, parameterTypes);
            if (method != null) {
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                }
                return method;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Method findMethodOnInterfaceOrClass(String interfaceName, Object target,
                                                       String name, Class<?>... parameterTypes) {
        try {
            Method method = findMethod(Class.forName(interfaceName), name, parameterTypes);
            if (method != null) {
                return method;
            }
        } catch (Throwable ignored) {
        }
        return findMethod(target.getClass(), name, parameterTypes);
    }

    // ------------------------------------------------------ IPackageManager

    /**
     * Same contract as the old {@code PackageManagerApis.getInstalledPackagesNoThrow}:
     * returns an empty list instead of throwing.
     */
    public static List<PackageInfo> getInstalledPackagesNoThrow(long flags, int userId) {
        try {
            Object pm = getPackageManager();
            Method method = findMethodOnInterfaceOrClass(
                    "android.content.pm.IPackageManager", pm,
                    "getInstalledPackages", long.class, int.class);
            if (method == null) {
                Log.w(TAG, "getInstalledPackages: method not found");
                return Collections.emptyList();
            }
            List<PackageInfo> list = unwrapPackageInfoList(method.invoke(pm, flags, userId));
            Log.i(TAG, "getInstalledPackages(flags=" + flags + ", user=" + userId + ") -> " + list.size());
            return list;
        } catch (Throwable tr) {
            Log.w(TAG, "getInstalledPackages failed (flags=" + flags + ", userId=" + userId + ")", tr);
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<PackageInfo> unwrapPackageInfoList(Object result) {
        if (result == null) {
            return Collections.emptyList();
        }
        if (result instanceof List) {
            // some OEM frameworks return a plain List
            return (List<PackageInfo>) result;
        }
        // ParceledListSlice (<= API 36) and PackageInfoList (API 37+) both expose getList()
        Method getList = findMethod(result.getClass(), "getList");
        if (getList != null) {
            try {
                Object list = getList.invoke(result);
                if (list instanceof List) {
                    return (List<PackageInfo>) list;
                }
            } catch (Throwable tr) {
                Log.w(TAG, "unwrapPackageInfoList", tr);
            }
        }
        return Collections.emptyList();
    }

    // --------------------------------------------------------- IUserManager

    private static final Class<?>[] USERS_3 = {boolean.class, boolean.class, boolean.class};
    private static final Class<?>[] USERS_1 = {boolean.class};

    /**
     * Same contract as the fixed {@code UserManagerApis.getUserIdsNoThrow()}:
     * <ul>
     *   <li>SDK 30..35: 3-boolean descriptor {@code getUsers(true, true, true)};</li>
     *   <li>SDK &lt; 30 and SDK &gt;= 36: single-boolean descriptor {@code getUsers(true)},
     *   falling back to the 3-boolean one when it is missing;</li>
     *   <li>on any failure: fall back to {@code {0}} like the old library.</li>
     * </ul>
     */
    public static List<Integer> getUserIdsNoThrow() {
        try {
            Object um = getUserManager();

            List<?> users;
            int sdk = android.os.Build.VERSION.SDK_INT;
            if (sdk >= 30 /* R */ && sdk < 36 /* BAKLAVA */) {
                users = invokeGetUsers3(um, true, true, true);
            } else {
                // prefer the single-boolean descriptor (legacy and Android 16+)
                List<?> result = null;
                Method single = findMethodOnInterfaceOrClass(
                        "android.os.IUserManager", um, "getUsers", USERS_1);
                if (single != null) {
                    try {
                        result = asList(single.invoke(um, true /* excludeDying */));
                    } catch (NoSuchMethodError ignored) {
                    }
                }
                if (result == null) {
                    result = invokeGetUsers3(um, true, true, true);
                }
                users = result;
            }

            Set<Integer> ids = new LinkedHashSet<>();
            for (Object user : users) {
                if (user == null) {
                    continue;
                }
                if (user instanceof UserInfo) {
                    ids.add(((UserInfo) user).id);
                } else {
                    try {
                        Object id = user.getClass().getField("id").get(user);
                        if (id instanceof Integer) {
                            ids.add((Integer) id);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            List<Integer> idList = new ArrayList<>(ids);
            Log.i(TAG, "getUsers -> " + idList);
            return idList;
        } catch (Throwable tr) {
            Log.w(TAG, "getUsers failed", tr);
            return Collections.singletonList(0);
        }
    }

    private static List<?> invokeGetUsers3(Object um, boolean excludePartial, boolean excludeDying, boolean excludePreCreated) throws Throwable {
        Method method = findMethodOnInterfaceOrClass(
                "android.os.IUserManager", um, "getUsers", USERS_3);
        if (method == null) {
            throw new NoSuchMethodError("IUserManager#getUsers(ZZZ)");
        }
        return asList(method.invoke(um, excludePartial, excludeDying, excludePreCreated));
    }

    private static List<?> asList(Object result) {
        if (result instanceof List) {
            return (List<?>) result;
        }
        throw new IllegalStateException("unexpected result: " + (result == null ? "null" : result.getClass()));
    }
}