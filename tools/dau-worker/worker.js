/**
 * Shizako 日活接收端（Cloudflare Worker）
 *
 * 接口
 *   POST /ping                  app 启动时匿名上报一次（每天每安装最多一次）
 *   GET  /stats                 返回每日活跃数据（公开只读，供官网「项目数据」页使用）
 *   GET  /admin?token=***       返回更细的明细（需要 ADMIN_TOKEN 环境变量）
 *   （定时任务）每日 UTC 00:10   把前一天的汇总写入 GitHub 仓库 data/dau.json
 *
 * 隐私设计
 *   客户端上报的 id 是「安装随机 ID + 当天日期」的 SHA-256 哈希。
 *   因此本服务**只能在同一天内去重**（这正是 DAU 的定义），
 *   无法把同一个人跨天关联起来，也不知道对方是谁。
 *   上报内容仅：日期 / 当日哈希 / 版本号 / 系统 API 级别 / 架构 —— 无账号、无设备号、无位置。
 *
 * 存储
 *   KV（计数器，快） + GitHub 仓库 data/dau.json（历史归档，可版本化、可公开审计）
 *
 * 需要的环境变量
 *   DAU_KV        KV 命名空间绑定（见 wrangler.toml）
 *   ADMIN_TOKEN   管理接口口令（wrangler secret put ADMIN_TOKEN）
 *   GH_TOKEN      有 repo 写权限的 token，仅用于归档提交（wrangler secret put GH_TOKEN）
 *   GH_REPO       例如 cr1437/Shizako
 */

const JSON_HEADERS = {
  'content-type': 'application/json; charset=utf-8',
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET,POST,OPTIONS',
  'access-control-allow-headers': 'content-type',
  'cache-control': 'no-store',
};

const DAY_RE = /^\d{4}-\d{2}-\d{2}$/;
const HASH_RE = /^[0-9a-f]{64}$/;

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}

function todayUtc() {
  return new Date().toISOString().slice(0, 10);
}

function yesterdayUtc() {
  return new Date(Date.now() - 86400000).toISOString().slice(0, 10);
}

/** 校验上报内容：任何一项不对就丢弃，避免被塞垃圾撑爆存储 */
function validPing(p) {
  if (!p || typeof p !== 'object') return null;
  const d = String(p.d || '');
  const h = String(p.h || '');
  if (!DAY_RE.test(d) || !HASH_RE.test(h)) return null;
  // 只接受「今天或昨天」，防止伪造历史日期刷数据
  if (d !== todayUtc() && d !== yesterdayUtc()) return null;
  const out = {
    d,
    h,
    v: String(p.v || '').slice(0, 32),
    os: Number.isFinite(+p.os) ? Math.trunc(+p.os) : 0,
    a: String(p.a || '').slice(0, 16),
  };
  return out;
}

async function handlePing(request, env) {
  const raw = await request.text();
  if (raw.length > 512) return json({ ok: false, e: 'too_large' }, 413);

  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return json({ ok: false, e: 'bad_json' }, 400);
  }

  const p = validPing(parsed);
  if (!p) return json({ ok: false, e: 'bad_payload' }, 400);

  // 当天去重键：同一天同一哈希只算一次 → 得到 DAU
  const uniqKey = `u:${p.d}:${p.h}`;
  const seen = await env.DAU_KV.get(uniqKey);
  const launches = Number((await env.DAU_KV.get(`l:${p.d}`)) || 0) + 1;
  await env.DAU_KV.put(`l:${p.d}`, String(launches));

  let dau = Number((await env.DAU_KV.get(`c:${p.d}`)) || 0);
  if (!seen) {
    await env.DAU_KV.put(uniqKey, '1', { expirationTtl: 60 * 60 * 24 * 120 });
    dau += 1;
    await env.DAU_KV.put(`c:${p.d}`, String(dau));
    // 版本分布（当日），便于看升级情况
    if (p.v) {
      const vk = `v:${p.d}:${p.v}`;
      await env.DAU_KV.put(vk, String(Number((await env.DAU_KV.get(vk)) || 0) + 1));
    }
  }

  return json({ ok: true, dau });
}

/** 读取最近 N 天的每日数据 */
async function readDays(env, limit = 60) {
  const list = await env.DAU_KV.list({ prefix: 'c:', limit: 1000 });
  const days = [];
  for (const k of list.keys) {
    const d = k.name.slice(2);
    const dau = Number((await env.DAU_KV.get(k.name)) || 0);
    const launches = Number((await env.DAU_KV.get(`l:${d}`)) || 0);
    days.push({ d, dau, launches });
  }
  days.sort((a, b) => (a.d < b.d ? 1 : -1)); // 新的在前
  return days.slice(0, limit);
}

async function handleStats(env) {
  const days = await readDays(env);
  const total = days.reduce((s, x) => s + x.dau, 0);
  const peak = days.reduce((m, x) => Math.max(m, x.dau), 0);
  // 近 7 天均值（不足 7 天按实际天数算）
  const recent = days.slice(0, 7);
  const avg7 = recent.length ? Math.round(recent.reduce((s, x) => s + x.dau, 0) / recent.length) : 0;
  return json({
    updated: new Date().toISOString(),
    avg7,
    peak,
    activeDays: days.length,
    totalDauSum: total,
    days,
  });
}

async function handleAdmin(url, env) {
  const token = url.searchParams.get('token') || '';
  if (!env.ADMIN_TOKEN || token !== env.ADMIN_TOKEN) return json({ ok: false, e: 'forbidden' }, 403);
  const days = await readDays(env, 180);
  const versions = {};
  const list = await env.DAU_KV.list({ prefix: 'v:', limit: 1000 });
  for (const k of list.keys) {
    const [, d, v] = k.name.split(':');
    versions[d] = versions[d] || {};
    versions[d][v] = Number((await env.DAU_KV.get(k.name)) || 0);
  }
  return json({ ok: true, days, versions });
}

/** 定时任务：把前一天汇总写进 GitHub 仓库（历史归档，避免每次上报都提交） */
async function archiveToGithub(env) {
  if (!env.GH_TOKEN || !env.GH_REPO) return;
  const days = await readDays(env, 365);
  const body = {
    updated: new Date().toISOString(),
    note: '由 Cloudflare Worker 每日归档；dau=当日去重活跃安装数，launches=当日上报次数',
    days,
  };
  const api = `https://api.github.com/repos/${env.GH_REPO}/contents/data/dau.json`;
  const headers = {
    authorization: `Bearer ${env.GH_TOKEN}`,
    accept: 'application/vnd.github+json',
    'user-agent': 'shizako-dau-worker',
  };
  let sha;
  const cur = await fetch(api, { headers });
  if (cur.ok) {
    const j = await cur.json();
    sha = j.sha;
  }
  const content = btoa(unescape(encodeURIComponent(JSON.stringify(body, null, 2) + '\n')));
  const res = await fetch(api, {
    method: 'PUT',
    headers: { ...headers, 'content-type': 'application/json' },
    body: JSON.stringify({
      message: `data: 日活归档 ${body.updated.slice(0, 10)}`,
      content,
      ...(sha ? { sha } : {}),
    }),
  });
  if (!res.ok) console.log('archive failed', res.status, await res.text());
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === 'OPTIONS') return new Response(null, { headers: JSON_HEADERS });

    if (request.method === 'POST' && url.pathname === '/ping') return handlePing(request, env);
    if (request.method === 'GET' && url.pathname === '/stats') return handleStats(env);
    if (request.method === 'GET' && url.pathname === '/admin') return handleAdmin(url, env);
    if (request.method === 'GET' && url.pathname === '/') {
      return json({ ok: true, service: 'shizako-dau', endpoints: ['POST /ping', 'GET /stats', 'GET /admin?token='] });
    }
    return json({ ok: false, e: 'not_found' }, 404);
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(archiveToGithub(env));
  },
};
