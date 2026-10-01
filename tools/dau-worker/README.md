# 日活接收端（Cloudflare Worker）

app 每天匿名上报一次启动，这里负责接收、当日去重（得到 **DAU**）、并把每日汇总归档到你 GitHub 仓库的 `data/dau.json`。

## 为什么需要它

GitHub 自己**收不了**上报：Pages 是纯静态托管、Actions 收不到外部 HTTP、而把 token 放进 APK 会被提取滥用。
所以链路是：**app → 这个 Worker（接收）→ GitHub 仓库（存储与历史）→ 官网数据页（展示）**。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/ping` | app 上报。请求体：`{"d":"2026-10-01","h":"<sha256>","v":"zako3.12","os":36,"a":"arm64"}` |
| GET | `/stats` | 公开只读：每日 `dau` / `launches`，以及近 7 天均值、峰值 |
| GET | `/admin?token=***` | 明细（含每日版本分布），需要 `ADMIN_TOKEN` |
| GET | `/` | 自检，返回可用接口列表 |

## 隐私设计（重要）

- `h` = `sha256(安装随机ID + ":" + 当天日期)`
- 因此服务端**只能在同一天内去重**（这正是 DAU 的定义），**无法跨天关联同一个人**
- 去重键设 120 天过期；不含账号、设备号、位置；服务端不接受非当日/昨日的日期，避免刷历史数据

## 部署步骤（约 5 分钟）

1. 打开 <https://dash.cloudflare.com> → **Workers & Pages** → **Create** → **Worker**，起名 `shizako-dau`，把 `worker.js` 的内容粘进去，Deploy。
2. **建 KV**：左侧 **Storage & Databases → KV** → Create namespace，名字 `shizako-dau`。
3. 回到 Worker → **Settings → Bindings** → Add → **KV namespace**：Variable name 填 `DAU_KV`，选刚建的命名空间。
4. **加口令**：Settings → **Variables and Secrets** → 加两个 **Secret**：
   - `ADMIN_TOKEN`：你自己随便定一个长随机串（看明细时用）
   - `GH_TOKEN`：GitHub 的 fine-grained token，只给 **Contents: Read and write**（用于把归档提交到仓库）
5. 再加一个普通变量 `GH_REPO` = `cr1437/Shizako`。
6. **定时归档**：Worker → Settings → **Triggers → Cron Triggers** → 添加 `10 0 * * *`（每天 UTC 00:10 把前一天写入 `data/dau.json`）。
7. 把 Worker 的域名（形如 `https://shizako-dau.<你的子域>.workers.dev`）填到 app 的 `DAU_ENDPOINT` 与官网数据页。

## 本地语法检查

```bash
node --check tools/dau-worker/worker.js
```

（真正跑起来需要 `wrangler dev`，可选。）
