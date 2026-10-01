#!/usr/bin/env python3
"""把 docs/assets/icons/*.svg 打包成 docs/assets/icons.js。

为什么这样做：
    图标包（Lucide，ISC 许可）逐个 <img> 引用既费请求又没法用 CSS 着色；
    内联到 HTML 里又会让页面臃肿、多处重复。这里统一打包成一份 JS 图标表，
    页面用 <span class="icon" data-icon="star"></span> 一行即可，颜色跟随文字。

用法：
    python3 tools/build_icons.py
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(ROOT, "docs", "assets", "icons")
OUT = os.path.join(ROOT, "docs", "assets", "icons.js")


def main():
    if not os.path.isdir(SRC_DIR):
        print("找不到图标目录：%s" % SRC_DIR)
        return 1

    icons = {}
    for name in sorted(os.listdir(SRC_DIR)):
        if not name.endswith(".svg"):
            continue
        key = name[:-4]
        with open(os.path.join(SRC_DIR, name), encoding="utf-8") as fh:
            raw = fh.read()
        # 只保留 <svg> 内部的内容，去掉许可注释与换行缩进
        body = re.sub(r"<!--.*?-->", "", raw, flags=re.S)
        body = re.sub(r"^.*?<svg[^>]*>", "", body, flags=re.S)
        body = re.sub(r"</svg>.*$", "", body, flags=re.S)
        body = re.sub(r"\s+", " ", body).strip()
        icons[key] = body

    lines = [
        "/* 由 tools/build_icons.py 生成，请勿直接编辑。",
        "   图标来源：Lucide (https://lucide.dev)，ISC 许可，见 assets/icons/LICENSE-lucide.txt */",
        "window.SHIZAKO_ICONS = {",
    ]
    for key, body in icons.items():
        lines.append('  "%s": \'%s\',' % (key, body.replace("'", "\\'")))
    lines.append("};")
    content = "\n".join(lines) + "\n"

    old = None
    if os.path.exists(OUT):
        with open(OUT, encoding="utf-8") as fh:
            old = fh.read()
    if old == content:
        print("图标表没有变化（%d 个图标）" % len(icons))
        return 0

    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(content)
    print("已生成 docs/assets/icons.js：%d 个图标，%.1f KB" % (len(icons), os.path.getsize(OUT) / 1024))
    return 0


if __name__ == "__main__":
    sys.exit(main())
