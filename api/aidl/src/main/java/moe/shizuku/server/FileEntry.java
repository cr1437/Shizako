package moe.shizuku.server;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * 目录项（规格 F1 的 {@code FileEntry}）。
 *
 * 放在 :aidl 模块里（而不是 server 模块），因为它是 IStorageProxy 的 AIDL 参数类型：
 * - 服务端（server）要构造它；
 * - 管理器/第三方受信应用拿到的是 AIDL 生成的代理，反序列化时也要用到这个类；
 * - 所以它必须和接口一起发布，且**不能**依赖任何 Android 业务类（这里只有 Parcelable）。
 *
 * 注意：:aidl 模块**没有** androidx.annotation 依赖（它是给第三方 App 当 API 库用的，
 * 依赖越少越好），所以这里不要写 @NonNull/@Nullable —— 加了会直接编译失败。
 *
 * 字段刻意做成 public final：客户端只读，不需要额外 getter，AIDL 序列化也最直接。
 */
public class FileEntry implements Parcelable {

    /** 文件名（不含路径分隔符）。 */
    public final String name;
    /** 是否目录。 */
    public final boolean isDirectory;
    /** 字节数；目录为 0（拿不到可靠大小，不要用 0 判断空目录）。 */
    public final long size;
    /** 最后修改时间（毫秒）；降级层（shell）拿不到可靠值时是 0。 */
    public final long lastModified;

    public FileEntry(String name, boolean isDirectory, long size, long lastModified) {
        this.name = name;
        this.isDirectory = isDirectory;
        this.size = size;
        this.lastModified = lastModified;
    }

    protected FileEntry(Parcel in) {
        this.name = in.readString();
        this.isDirectory = in.readInt() != 0;
        this.size = in.readLong();
        this.lastModified = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeInt(isDirectory ? 1 : 0);
        dest.writeLong(size);
        dest.writeLong(lastModified);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "FileEntry{name='" + name + "', dir=" + isDirectory
                + ", size=" + size + ", mtime=" + lastModified + '}';
    }

    public static final Creator<FileEntry> CREATOR = new Creator<FileEntry>() {
        @Override
        public FileEntry createFromParcel(Parcel in) {
            return new FileEntry(in);
        }

        @Override
        public FileEntry[] newArray(int size) {
            return new FileEntry[size];
        }
    };
}
