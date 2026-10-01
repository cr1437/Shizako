#!/usr/bin/env python3
"""重新生成仓库里的自托管徽章（docs/badge-*.svg）。

为什么不用 shields.io：
    外部图床在部分网络下取不到（GitHub 还会再套一层 camo 代理），README 顶部会变成
    一排裂图。所以徽章改成 SVG 文件放进本仓库、用相对路径引用 —— 这类图片由
    github.com 自己提供，稳定性和仓库里的横幅图一致。

静态文件显示不了会变的数字，所以这个脚本由 .github/workflows/badges.yml 定时
（以及每次发布 release 后）运行，把最新的数据写回 SVG。

用法：
    GH_TOKEN=xxx python3 tools/update_badges.py
（不设 GH_TOKEN 也能跑，只是会受未认证的速率限制。）
"""

import json
import os
import sys
import urllib.request
from datetime import datetime, timezone

REPO = "cr1437/Shizako"
API = "https://api.github.com"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "docs")


def api(path):
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "shizako-badge-updater",
    }
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(API + path, headers=headers)
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.load(resp)


def esc(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def badge(label, value, color, value_fg="#fff"):
    """生成一个 flat-square 风格的徽章（宽度按字符数估算，留足余量）。"""
    wl = 10 + 7 * len(label)
    wr = 10 + 7 * len(value)
    total = wl + wr
    font = "Verdana,Geneva,DejaVu Sans,sans-serif"
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" width="{total}" height="20" '
        'role="img" aria-label="{aria}">\n'
        "  <title>{aria}</title>\n"
        '  <g shape-rendering="crispEdges">\n'
        '    <rect width="{wl}" height="20" fill="#555"/>\n'
        '    <rect x="{wl}" width="{wr}" height="20" fill="{color}"/>\n'
        "  </g>\n"
        '  <g fill="#fff" text-anchor="middle" font-family="{font}" font-size="11">\n'
        '    <text x="{lx}" y="14">{label}</text>\n'
        "  </g>\n"
        '  <g fill="{value_fg}" text-anchor="middle" font-family="{font}" font-size="11">\n'
        '    <text x="{vx}" y="14">{value}</text>\n'
        "  </g>\n"
        "</svg>\n"
    ).format(
        total=total, wl=wl, wr=wr, color=color, font=font, value_fg=value_fg,
        aria=esc(label + ": " + value), label=esc(label), value=esc(value),
        lx=wl // 2, vx=wl + wr // 2,
    )


def write_if_changed(name, content):
    path = os.path.join(OUT_DIR, name)
    old = None
    if os.path.exists(path):
        with open(path, encoding="utf-8") as fh:
            old = fh.read()
    if old == content:
        return False
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(content)
    return True


def main():
    releases = api("/repos/%s/releases?per_page=100" % REPO)
    repo = api("/repos/%s" % REPO)

    downloads = sum(
        asset.get("download_count", 0)
        for release in releases
        for asset in release.get("assets", [])
    )
    stars = repo.get("stargazers_count", 0)
    latest = releases[0]["tag_name"] if releases else "unknown"

    changed = []
    if write_if_changed("badge-downloads.svg", badge("downloads", "{:,}".format(downloads), "#2ea44f")):
        changed.append("badge-downloads.svg")
    if write_if_changed("badge-star.svg", badge("star", "{:,}".format(stars), "#FFD700", value_fg="#24292f")):
        changed.append("badge-star.svg")
    if write_if_changed("badge-version.svg", badge("version", latest, "#2ea44f")):
        changed.append("badge-version.svg")

    # 同时输出一份机器可读的数据，供官网首屏的「数据卡片」直接读取。
    # 为什么不让学生浏览器直接调 GitHub API：api.github.com 在部分网络（含国内）会被
    # DNS 拦掉，页面会拿不到数字；这份 json 与页面同源，永远读得到。
    stats = {
        "version": latest,
        "downloads": downloads,
        "stars": stars,
        "updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    if write_if_changed("stats.json", json.dumps(stats, ensure_ascii=False, indent=2) + "\n"):
        changed.append("stats.json")

    print("downloads=%d stars=%d latest=%s" % (downloads, stars, latest))
    print("updated: %s" % (", ".join(changed) if changed else "(no change)"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
