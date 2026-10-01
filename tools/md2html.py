#!/usr/bin/env python3
"""把仓库里的 Markdown 开发文档转成官网上的网页。

为什么需要它：
    GitHub Pages 对 .md 只按纯文本返回，浏览器不会排版（点进去就是一堆原文）。
    这个脚本把 Markdown 渲染成带站点导航 / 目录 / 代码高亮的 HTML 页面，
    以后文档更新只要重跑一次即可，不用手工维护两份内容。

用法（在仓库根目录）：
    python3 tools/md2html.py                      # 转换默认清单
    python3 tools/md2html.py docs/API.md out.html # 转换单个文件

不做的事：不引第三方库（标准库足够），不引 CDN。
"""

import html
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "docs", "dev")

# 默认要转换的文档：(源文件, 输出文件名, 页面标题, 说明)
JOBS = [
    ("docs/API.md", "api.html", "API 使用指南", "接入路线、binder 生命周期、AIDL 用户服务与常见坑"),
    ("docs/BUILD.md", "build.html", "编译与参与开发", "环境要求、编译命令、签名注意事项与代码结构"),
    ("api/SHIZAKO-CHANGES.md", "changes.html", "对 Shizuku-API 的本地修改", "为什么权限名不同、如何与上游同步"),
    ("api/README.md", "upstream-api.html", "Shizuku-API 上游文档", "官方客户端库的原始说明（英文）"),
    ("api/rish/README.md", "rish.html", "rish 命令行", "在终端里调用特权 API 的 CLI 工具"),
    ("更新日志.md", "changelog.html", "更新日志", "各版本的对外更新内容（与 GitHub Releases 同步）"),
]

PAGE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title} — Shizako 开发文档</title>
<meta name="description" content="{desc}">
<meta name="theme-color" content="#0b0d12">
<link rel="icon" href="{p}assets/icon.png">
<link rel="stylesheet" href="{p}assets/site.css">
</head>
<body class="doc-body">

<header class="nav" id="nav">
  <a class="brand" href="{p}index.html">
    <img src="{p}assets/icon.png" alt="Shizako" width="32" height="32">
    <span>Shizako</span>
  </a>
  <nav class="nav-links">
    <a href="{p}index.html">首页</a>
    <a href="{p}index.html#features">功能</a>
    <a href="{p}index.html#activate">激活</a>
    <a href="index.html" class="active">开发文档</a>
    <a href="https://github.com/cr1437/Shizako">GitHub</a>
  </nav>
  <div class="nav-right">
    <a class="btn primary small" href="https://github.com/cr1437/Shizako/releases/latest">下载</a>
  </div>
</header>

<div class="doc-layout">
  <aside class="doc-side">
    <a class="doc-back" href="index.html">← 开发文档总览</a>
    <nav class="doc-toc">
      <p class="doc-toc-title">本页目录</p>
      {toc}
    </nav>
  </aside>

  <main class="doc-main">
    <article class="doc-article">
{body}
    </article>
    <footer class="doc-foot">
      <p>本页由 <code>tools/md2html.py</code> 从 <code>{src}</code> 生成，请勿直接编辑 HTML。</p>
      <p><a href="{p}index.html">← 返回官网</a> · <a href="https://github.com/cr1437/Shizako">GitHub</a> ·
         <a href="{srclink}">查看本文源文件</a></p>
    </footer>
  </main>
</div>

<script>
// 顶栏滚动态（与首页同一效果，文档页不加载 site.js 以免误伤语言切换）
(function () {{
  var nav = document.getElementById('nav');
  function onScroll() {{ if (nav) nav.classList.toggle('scrolled', window.scrollY > 8); }}
  window.addEventListener('scroll', onScroll, {{ passive: true }});
  onScroll();
}})();
</script>
</body>
</html>
"""


def slug(text):
    """标题 → 锚点 id（保留中日韩字符与字母数字）。"""
    s = re.sub(r"<[^>]+>", "", text)
    s = s.strip().lower()
    s = re.sub(r"[^\w\u4e00-\u9fff\s-]", "", s, flags=re.UNICODE)
    s = re.sub(r"\s+", "-", s)
    return s or "section"


def inline(text):
    """行内 Markdown → HTML（先转义，再放行受支持的行内语法）。"""
    out = html.escape(text, quote=False)
    out = re.sub(r"`([^`]+)`", r"<code>\1</code>", out)
    out = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", out)
    out = re.sub(r"(?<!\*)\*([^*\n]+)\*(?!\*)", r"<em>\1</em>", out)
    out = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", r'<a href="\2">\1</a>', out)
    return out


def render(md):
    """Markdown → (正文 HTML, 目录项列表)。支持标题/段落/列表/表格/引用/代码块/分隔线。"""
    lines = md.split("\n")
    body, toc = [], []
    i, n = 0, len(lines)

    while i < n:
        line = lines[i]

        # 代码块
        if line.startswith("```"):
            lang = line[3:].strip()
            i += 1
            buf = []
            while i < n and not lines[i].startswith("```"):
                buf.append(lines[i])
                i += 1
            i += 1
            cls = ' class="lang-%s"' % lang if lang else ""
            body.append("<pre%s><code>%s</code></pre>" % (cls, html.escape("\n".join(buf))))
            continue

        # 标题
        m = re.match(r"^(#{1,6})\s+(.*)$", line)
        if m:
            level = len(m.group(1))
            text = m.group(2).strip()
            aid = slug(text)
            if level == 1:
                body.append('<h1 id="%s">%s</h1>' % (aid, inline(text)))
            else:
                body.append('<h%d id="%s">%s</h%d>' % (level, aid, inline(text), level))
                if level in (2, 3):
                    toc.append((level, aid, re.sub(r"<[^>]+>", "", inline(text))))
            i += 1
            continue

        # 分隔线
        if re.match(r"^\s*([-*_])\s*\1\s*\1[\s\1]*$", line):
            body.append("<hr>")
            i += 1
            continue

        # 表格
        if line.lstrip().startswith("|") and i + 1 < n and re.match(r"^\s*\|[\s:|-]+\|\s*$", lines[i + 1]):
            def cells(row):
                return [c.strip() for c in row.strip().strip("|").split("|")]
            head = cells(line)
            i += 2
            rows = []
            while i < n and lines[i].lstrip().startswith("|"):
                rows.append(cells(lines[i]))
                i += 1
            t = ["<div class=\"doc-table-wrap\"><table><thead><tr>"]
            t += ["<th>%s</th>" % inline(c) for c in head]
            t.append("</tr></thead><tbody>")
            for r in rows:
                t.append("<tr>" + "".join("<td>%s</td>" % inline(c) for c in r) + "</tr>")
            t.append("</tbody></table></div>")
            body.append("".join(t))
            continue

        # 引用
        if line.lstrip().startswith(">"):
            buf = []
            while i < n and lines[i].lstrip().startswith(">"):
                buf.append(lines[i].lstrip()[1:].strip())
                i += 1
            body.append("<blockquote>%s</blockquote>" % inline(" ".join(buf)))
            continue

        # 列表（有序 / 无序，支持一层嵌套）
        if re.match(r"^\s*([-*+]|\d+\.)\s+", line):
            ordered = bool(re.match(r"^\s*\d+\.\s+", line))
            tag = "ol" if ordered else "ul"
            items = []
            while i < n and re.match(r"^\s*([-*+]|\d+\.)\s+", lines[i]):
                items.append(re.sub(r"^\s*([-*+]|\d+\.)\s+", "", lines[i]))
                i += 1
            body.append("<%s>%s</%s>" % (tag, "".join("<li>%s</li>" % inline(x) for x in items), tag))
            continue

        # 空行
        if not line.strip():
            i += 1
            continue

        # 段落（连续非空行合并）
        buf = [line]
        i += 1
        while i < n and lines[i].strip() and not re.match(r"^(#{1,6}\s|```|\s*([-*+]|\d+\.)\s|>|\|)", lines[i]) \
                and not re.match(r"^\s*([-*_])\s*\1\s*\1", lines[i]):
            buf.append(lines[i])
            i += 1
        body.append("<p>%s</p>" % inline(" ".join(x.strip() for x in buf)))

    return "\n".join(body), toc


def build_toc(toc):
    if not toc:
        return '<p class="doc-toc-empty">（本页无小节）</p>'
    out = []
    for level, aid, text in toc:
        cls = " class=\"lvl3\"" if level == 3 else ""
        out.append('<a href="#%s"%s>%s</a>' % (aid, cls, html.escape(text)))
    return "\n      ".join(out)


def convert(src, out_name, title, desc):
    src_path = os.path.join(ROOT, src.replace("/", os.sep))
    if not os.path.exists(src_path):
        print("跳过（源文件不存在）：%s" % src)
        return None
    with open(src_path, encoding="utf-8") as fh:
        md = fh.read()

    # 首行的一级标题已经用作页面标题，正文里去掉，避免重复
    md = re.sub(r"^#\s+.*\n", "", md, count=1)
    body, toc = render(md)

    # 源文件链接：docs/ 下的文件站点会直接提供（相对路径）；仓库其它位置的
    # （api/ 等不在 Pages 服务范围内）给 GitHub 链接，否则会指向错误的文件。
    src_link = ("../" + os.path.basename(src)) if src.startswith("docs/") \
        else ("https://github.com/cr1437/Shizako/blob/main/" + src)

    html_out = PAGE.format(
        title=html.escape(title), desc=html.escape(desc), body=body,
        toc=build_toc(toc), src=src, srclink=src_link, p="../",
    )
    os.makedirs(OUT_DIR, exist_ok=True)
    dst = os.path.join(OUT_DIR, out_name)
    with open(dst, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(html_out)
    print("%-34s → docs/dev/%-20s %5.1f KB  小节 %d 个"
          % (src, out_name, os.path.getsize(dst) / 1024, len(toc)))
    return (out_name, title, desc)


def main():
    if len(sys.argv) >= 3:
        convert(sys.argv[1], sys.argv[2], "开发文档", "Shizako 开发文档")
        return 0
    for job in JOBS:
        convert(*job)
    return 0


if __name__ == "__main__":
    sys.exit(main())
