#ifndef MISC_H
#define MISC_H

int copyfile(const char *src_path, const char *dst_path);
uintptr_t memsearch(const uintptr_t start, const uintptr_t end, const void *value, size_t size);
int switch_mnt_ns(int pid);
int get_proc_name(int pid, char *name, size_t _size);

/**
 * 读整个 /proc/<pid>/cmdline，并把参数之间的 NUL 换成空格，便于整体子串匹配。
 * 读不到（进程已退出、权限不足）返回 1。
 */
int get_proc_cmdline(int pid, char *cmdline, size_t size);

using foreach_proc_function = void(pid_t);
void foreach_proc(foreach_proc_function *func);

char *trim(char *str);

#endif // MISC_H
