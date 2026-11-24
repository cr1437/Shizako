/* ============================================================================
   Shizako 官网脚本（2026-11 重构版）

   做什么：
   1. 图标注入 —— 把 assets/icons.js 里的 Lucide 路径塞进 <span data-icon="name">；
   2. 中英切换 —— 中文直接以 HTML 为准（脚本加载时抓取），英文查 EN 词典；
   3. 顶栏滚动态 / 手机抽屉 / 区块高亮；
   4. 数据卡片 —— HTML 兜底 → stats.json → api.github.com 三段来源；
   5. 文档页 —— 阅读进度、右侧目录高亮、代码块复制。

   约束：无第三方依赖；尊重 prefers-reduced-motion；所有网络请求失败都静默。
   ========================================================================== */
(function () {
  'use strict';

  var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* ── 图标注入 ──────────────────────────────────────────────────────────── */
  function paintIcons(root) {
    var icons = window.SHIZAKO_ICONS || {};
    [].slice.call((root || document).querySelectorAll('[data-icon]')).forEach(function (el) {
      if (el.getAttribute('data-icon-done')) return;
      var body = icons[el.getAttribute('data-icon')];
      if (!body) return;
      el.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
        'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + body + '</svg>';
      el.setAttribute('data-icon-done', '1');
    });
  }

  /* ── 中英切换 ──────────────────────────────────────────────────────────── */
  // 中文文案以 HTML 为准：加载时把原文抓下来，切回中文时原样写回。
  // data-i18n 用 textContent（安全）；data-i18n-html 用 innerHTML（标题里带渐变 span 这类结构）。
  var EN = {
    'nav.features': 'Features',
    'nav.activate': 'Activation',
    'nav.compat': 'Compatibility',
    'nav.faq': 'FAQ',
    'nav.docs': 'Docs',
    'nav.guide': 'Activation guide',
    'nav.download': 'Download',
    'nav.repo': 'GitHub repository',
    'hero.lede': 'Root, wireless debugging, computer ADB or Dhizuku — one of them wakes a small privileged process, and that privilege is then <strong>lent</strong> to apps you approve. Every app needs your explicit nod, and you can take it back at any time.',
    'hero.download': 'Download latest',
    'hero.hint': 'Android 7.0+ · wireless debugging needs Android 11+ · Apache-2.0',

    'stat.version': 'Latest version',
    'stat.downloads': 'Downloads',
    'stat.stars': 'Stars',

    'nav.shots': 'Screenshots',
    'nav.github': 'GitHub ↗',
    'hero.sub': 'System privileges without root.',
    'st.title': 'Four ways <span class="grad">to wake her up</span>.',
    'st.sub': 'No root, no flashing.',
    'st.p': 'Use whatever you have at hand: just the phone, a cable and a computer, or an already-rooted device. Only Dhizuku changes system state — the other three last until the next reboot.',
    'st.link': 'Step-by-step guide with real screenshots',
    'fl.power': 'Privilege',
    'fl.pass': 'Grants',
    'fl.compat': 'Compatibility',
    'fl.do': 'Device owner',
    'fl.backup': 'Data',
    'fl.files': 'Files',
    'fl.install': 'Installer',
    'fl.auto': 'Automation',
    'fl.batch': 'Batch',
    'fl.tpl': 'Templates',
    'fl.tool': 'Toolbox',
    'fl.ui': 'Interface',
    'cl.api': 'API',
    'cl.do': 'Device owner',
    'cl.coexist': 'Coexistence',
    'cl.why': 'Why',
    'features.eyebrow': 'Features',
    'features.title': 'What she can do',
    'features.lead': 'The whole idea in one sentence: lend system privileges to apps you trust instead of rooting the phone.',
    'new': 'New',
    'f1.t': 'Privileges without root',
    'f1.d': 'Near-root power through ADB / wireless debugging — no unlocking, no flashing, warranty intact.',
    'f2.t': 'Passport system',
    'f2.d': 'Every app needs your explicit approval on first connect; the allowlist lives on your device and is revocable.',
    'f3.t': 'Works with the official ecosystem',
    'f3.d': 'Apps built on the official Shizuku-API connect without a single line of changes.',
    'f4.t': 'Dhizuku mode',
    'f4.d': 'One tap makes her the Device Owner: always-on privileges without root or wireless debugging, surviving reboots.',
    'f5.t': 'App data backup / restore',
    'f5.d': 'External data (Android/data, obb) packed into the SAF folder you pick; replace-style restore never leaves half-written data.',
    'f6.t': 'Privileged storage proxy',
    'f6.d': 'A restricted file API served as shell/root, with path allowlist, four-tier access fallback and a per-call audit line.',
    'f7.t': 'Installer trust list',
    'f7.d': 'Trusted apps install silently, everyone else gets a confirmation dialog; APK / apk.1 / XAPK / APKS in one batch queue.',
    'f8.t': 'EXEC_SHELL automation',
    'f8.d': 'A token + signature guarded broadcast so Tasker / MacroDroid can run privileged commands and receive results.',
    'f9.t': 'Batch grants',
    'f9.d': 'Multi-select grant or revoke with impact confirmation, progress and a result report; every batch lands in the audit log.',
    'f10.t': 'Permission templates',
    'f10.d': 'Three built-in templates plus your own, exportable/importable through SAF — move your setup to a new phone.',
    'f11.t': 'Toolbox',
    'f11.d': 'Module manager, shell console, accessibility keeper and one-tap injection — no computer needed for everyday privileged chores.',
    'f12.t': 'Two UI styles',
    'f12.d': 'Glassmorphism or Material 3, switchable in one tap, with dark/light and dynamic colour support.',
    'a1.tag': 'Recommended',
    'a1.t': 'Wireless debugging',
    'a1.d': 'Android 11+ with wireless debugging on: scan or pair inside the app. Once paired, one tap restarts her after a reboot.',
    'a2.t': 'Computer ADB',
    'a2.d': 'With a computer at hand: copy the adb command shown in the app and run it once. No version limits.',
    'a3.t': 'Root',
    'a3.d': 'Already rooted (KernelSU / Magisk / APatch)? One tap, one grant, done — plus optional start-on-boot.',
    'a4.tag': 'Survives reboot',
    'a4.t': 'Dhizuku (Device Owner)',
    'a4.d': 'Run the dpm set-device-owner command once on a computer (no accounts on the device). Privileges then survive reboots with no root and no wireless debugging.',

    'shots.eyebrow': 'Screenshots',
    'shots.title': 'What she looks like',
    'shots.lead': 'Real device screenshots (vivo V2417A / Android 16, dark glass style).',
    'cap1': 'Home: service status, version and activation at a glance',
    'cap2': 'One-stop activation: four ways in, one page',
    'cap3': 'Authorised apps: every passport, revocable anytime',
    'cap4': 'Activated: the server is up and running',

    'compat.eyebrow': 'Compatibility',
    'compat.title': 'Ecosystem compatibility',
    'compat.lead': 'Same wire protocol as upstream Shizuku — different name, different identity.',
    'c1.t': 'Shizuku-API apps connect unchanged',
    'c1.d': 'Apps built on the official dev.rikka.shizuku:api (MT Manager, Ice Box, SystemUI Tuner and more) connect as-is: the server only checks that an app asked for permission, and the first connection still needs your approval.',
    'c2.t': 'Her own Device Owner',
    'c2.d': 'Once set as Device Owner, Dhizuku-API apps treat Shizako as Dhizuku. All privilege forwarding happens inside the Shizako process — no binder backdoor for third parties.',
    'c3.t': 'Coexists with official Shizuku',
    'c3.d': 'Shizako uses its own package, permission and provider authority, so both can be installed at once: whichever server is running serves the app.',
    'c4.t': 'Why the package name differs',
    'c4.d': 'Per the upstream license a fork must not use the upstream app id, name or moe.shizuku.manager.permission.* — so this project ships com.churan.shizako and its own permission names.',

    'safe.eyebrow': 'Trust',
    'safe.title': 'How to tell an official build',
    'safe.lead': 'Every official build is signed with the same certificate. If the fingerprint does not match, someone repackaged it — do not install, do not grant.',
    's1.d': 'All official builds carry the certificate below. Check it with <code>apksigner verify --print-certs</code> or in the app under Settings → About.',
    's2.d': 'The app reports nothing. The only outbound request is the public GitHub release API used to notice a new version.',
    's3.d': 'Each privilege belongs to one app you approved, the allowlist is stored locally and every privileged API call is written to the audit log.',
    's4.d': 'Build it yourself from source; upstream copyright belongs to RikkaApps and the modifications to Churan.',
    'rec.badge': 'Listed by the Shizuku community',
    'rec.link': 'View the list',
    'faq.eyebrow': 'FAQ',
    'faq.title': 'Common questions',
    'q1': 'Do I need root?',
    'q1a': 'No. Wireless debugging or a USB cable is enough; root simply adds more options.',
    'q2': 'How does this relate to Shizuku?',
    'q2a': 'It is compatible with the Shizuku-API ecosystem — apps built on the official SDK connect without changes. Per the upstream license it ships its own package and permission names, and it can be installed alongside the official app.',
    'q3': 'Is granting privileges to apps safe?',
    'q3a': 'Every app needs your explicit approval on first connect; the allowlist is local and revocable, Dhizuku forwarding stays inside the Shizako process, and the storage proxy needs a separate grant with per-call audit.',
    'q4': 'Which Android versions are supported?',
    'q4a': 'Android 7.0 and above; wireless-debugging activation needs Android 11+.',
    'q5': 'Does it collect my data?',
    'q5a': 'No. The app reports no usage data at all; the automatic update check only queries the public GitHub release API to see whether a newer version exists.',
    'q6': 'Is it open source? Can I build it myself?',
    'q6a': 'Yes, Apache-2.0. Clone the repository and run <code>./gradlew :manager:assembleRelease</code> — JDK 21 and Android SDK 36 required.',

    'final.title': 'Take her home',
    'final.lead': 'Download, wake her up, and start handing out passports to your apps.',
    'final.download': 'Download latest',
    'final.star': 'Star the project',

    'footer.desc': 'A catgirl-mascot privileged-API assistant. Open source, free, no ads.',
    'footer.links': 'Links',
    'footer.repo': 'GitHub repository',
    'footer.releases': 'Releases',
    'footer.issues': 'Issues',
    'footer.docs': 'Developer docs',
    'footer.community': 'Community',
    'footer.license': 'License',
    'footer.licenseText': 'Apache License 2.0. Upstream copyright RikkaApps; modifications copyright Churan.',
    'footer.bottom': 'Made with love · she only bites evil (ˊᗜˋ*)'
  };

  var zhStore = {};   // key -> 原始中文（textContent 或 innerHTML）

  function collectZh() {
    [].slice.call(document.querySelectorAll('[data-i18n]')).forEach(function (el) {
      var k = el.getAttribute('data-i18n');
      if (!(k in zhStore)) zhStore[k] = el.innerHTML;
    });
  }

  function setLang(lang) {
    var html = document.documentElement;
    html.setAttribute('lang', lang === 'en' ? 'en' : 'zh-CN');
    [].slice.call(document.querySelectorAll('[data-i18n]')).forEach(function (el) {
      var k = el.getAttribute('data-i18n');
      if (lang === 'en') {
        if (!(k in EN)) return;
        if (el.hasAttribute('data-i18n-html')) el.innerHTML = EN[k];
        else el.textContent = EN[k];
      } else if (k in zhStore) {
        el.innerHTML = zhStore[k];
      }
    });
    // 激活教程里中英文是两套截图：按语言换图片（data-img-zh / data-img-en）
    [].slice.call(document.querySelectorAll('[data-img-zh]')).forEach(function (img) {
      var want = lang === 'en' ? img.getAttribute('data-img-en') : img.getAttribute('data-img-zh');
      if (want && img.getAttribute('src') !== want) img.setAttribute('src', want);
    });
    // 下拉目录里的当前项标记 / 语言按钮文案
    var btn = document.getElementById('langBtn');
    if (btn) btn.textContent = lang === 'en' ? '中文' : 'EN';
    try { localStorage.setItem('shizako.lang', lang); } catch (e) { /* 隐私模式：忽略 */ }
    paintIcons(document);
  }

  function initLang() {
    // 只有首页同时有两套文案；文档页与激活教程是纯中文，
    // 那里不放语言按钮，也不做切换，避免出现「英文导航 + 中文正文」。
    var btn = document.getElementById('langBtn');
    if (!btn) return;
    collectZh();
    var saved = null;
    try { saved = localStorage.getItem('shizako.lang'); } catch (e) { /* ignore */ }
    // 默认中文；只有用户手动切过英文（或被浏览器语言判定为英文以外的明确选择）才跟随存储值。
    var lang = saved === 'en' ? 'en' : 'zh';
    setLang(lang);
    btn.addEventListener('click', function () {
      setLang(document.documentElement.getAttribute('lang') === 'en' ? 'zh' : 'en');
    });
  }

  /* ── 顶栏 / 抽屉 ───────────────────────────────────────────────────────── */
  function initChrome() {
    var bar = document.getElementById('topbar');
    function onScroll() {
      if (bar) bar.classList.toggle('scrolled', window.scrollY > 8);
    }
    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();

    var toggle = document.getElementById('menuBtn');
    var drawer = document.getElementById('drawer');
    if (toggle && drawer) {
      toggle.addEventListener('click', function () {
        var open = drawer.classList.toggle('open');
        toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
      });
      drawer.addEventListener('click', function (e) {
        if (e.target.closest('a')) {
          drawer.classList.remove('open');
          toggle.setAttribute('aria-expanded', 'false');
        }
      });
      document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' && drawer.classList.contains('open')) {
          drawer.classList.remove('open');
          toggle.setAttribute('aria-expanded', 'false');
        }
      });
    }
  }

  /* ── 滚动进场 ──────────────────────────────────────────────────────────── */
  function initReveal() {
    var targets = [].slice.call(document.querySelectorAll('[data-reveal]'));
    if (!targets.length) return;

    function show(el) { el.classList.add('in'); }

    if (reduceMotion || !('IntersectionObserver' in window)) {
      targets.forEach(function (el) { el.classList.add('reveal'); show(el); });
      return;
    }

    var pending = targets.slice();
    targets.forEach(function (el, i) {
      el.classList.add('reveal');
      el.style.transitionDelay = Math.min(i % 4, 3) * 70 + 'ms';
    });

    // 位置兜底：滚动时按 getBoundingClientRect 再判一次。
    // 只靠 IntersectionObserver 的话，快速甩动滚轮时「一帧就穿过视口」的元素会漏掉，
    // 表现就是卡片永久停在 opacity:0（之前踩过这个坑）。
    function revealInView() {
      var vh = window.innerHeight || 800;
      for (var i = pending.length - 1; i >= 0; i--) {
        var el = pending[i];
        var r = el.getBoundingClientRect();
        if (r.top < vh * 0.94 || r.bottom < 0) {
          show(el);
          pending.splice(i, 1);
        }
      }
    }

    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (!en.isIntersecting) return;
        show(en.target);
        io.unobserve(en.target);
      });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.01 });
    targets.forEach(function (el) { io.observe(el); });

    var ticking = false;
    window.addEventListener('scroll', function () {
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(function () { ticking = false; revealInView(); });
    }, { passive: true });
    window.addEventListener('resize', revealInView, { passive: true });

    // 最后一道保险：3 秒后无条件全部显示（宁可没动画，也不能看不见）
    setTimeout(function () { targets.forEach(show); }, 3000);
    revealInView();
  }

  /* ── 数据卡片：兜底值 → stats.json → GitHub API ────────────────────────── */
  function fmt(v) { return Number(v).toLocaleString('en-US'); }

  function countUp(el, target) {
    if (reduceMotion || !isFinite(target)) { el.textContent = fmt(target); return; }
    var start = performance.now(), dur = 900;
    function step(now) {
      var p = Math.min(1, (now - start) / dur);
      var eased = 1 - Math.pow(1 - p, 3);
      el.textContent = fmt(Math.round(target * eased));
      if (p < 1) requestAnimationFrame(step);
    }
    requestAnimationFrame(step);
  }

  var statDone = {};      // key -> 已经写过真实数据（stats.json 或实时接口）
  var statAnimated = {};  // key -> 数字滚动是否已经跑过

  function renderStat(key, value) {
    if (value === undefined || value === null || value === '') return;
    var s = String(value);
    var numeric = /^\d+$/.test(s);
    statDone[key] = true;
    [].slice.call(document.querySelectorAll('[data-stat="' + key + '"]')).forEach(function (el) {
      if (numeric && !statAnimated[key]) {
        statAnimated[key] = true;
        countUp(el, Number(s));
      } else {
        el.textContent = numeric ? fmt(Number(s)) : s;
      }
    });
  }

  function initStats() {
    var els = [].slice.call(document.querySelectorAll('[data-stat]'));
    if (!els.length) return;

    // 1) HTML 兜底值：纯数字的卡片先滚一遍（版本号是字符串，不能滚动）
    els.forEach(function (el) {
      var txt = (el.textContent || '').trim();
      var key = el.getAttribute('data-stat');
      if (statDone[key] || statAnimated[key]) return;
      if (!/^[\d,]+$/.test(txt)) return;
      var raw = txt.replace(/[^\d]/g, '');
      if (!raw) return;
      statAnimated[key] = true;
      countUp(el, Number(raw));
    });

    // 2) 同源 stats.json（badges.yml 每小时刷新；api.github.com 被墙时就是它兜底）
    fetch('stats.json', { cache: 'no-cache' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (s) {
        if (!s) return;
        ['version', 'downloads', 'stars'].forEach(function (k) { renderStat(k, s[k]); });
      })
      .catch(function () { /* 静默 */ });

    // 3) api.github.com 实时值；失败不影响前两级
    var API = 'https://api.github.com/repos/cr1437/Shizako';
    function gh(url, ms) {
      if (!window.fetch) return Promise.reject();
      var ctrl = window.AbortController ? new AbortController() : null;
      var timer = ctrl ? setTimeout(function () { ctrl.abort(); }, ms) : null;
      return fetch(url, ctrl ? { signal: ctrl.signal } : undefined).then(function (r) {
        if (timer) clearTimeout(timer);
        return r.ok ? r.json() : null;
      });
    }
    gh(API + '/releases?per_page=100', 6000).then(function (list) {
      if (!list || !list.length) return;
      var downloads = 0;
      list.forEach(function (rel) {
        (rel.assets || []).forEach(function (a) { downloads += a.download_count || 0; });
      });
      renderStat('downloads', downloads);
      var latest = (list.filter(function (r) { return !r.draft && !r.prerelease; })[0] || list[0]);
      if (latest && latest.tag_name) renderStat('version', latest.tag_name);
    }).catch(function () { /* 静默 */ });
    gh(API, 6000).then(function (repo) {
      if (repo && repo.stargazers_count !== undefined) renderStat('stars', repo.stargazers_count);
    }).catch(function () { /* 静默 */ });
  }

  /* ── 文档页：进度条 / 目录高亮 / 代码复制 ──────────────────────────────── */
  function initDocs() {
    var bar = document.getElementById('docProgress');
    var toc = document.getElementById('docToc');
    var links = toc ? [].slice.call(toc.querySelectorAll('a')) : [];
    var heads = links.map(function (a) {
      var href = a.getAttribute('href') || '';
      return document.getElementById(href.slice(1));
    });

    function spy() {
      if (!links.length) return;
      var idx = 0;
      for (var i = 0; i < heads.length; i++) {
        if (heads[i] && heads[i].getBoundingClientRect().top <= 130) idx = i;
      }
      links.forEach(function (a, i) { a.classList.toggle('active', i === idx); });
    }

    function onScroll() {
      if (bar) {
        var h = document.documentElement.scrollHeight - window.innerHeight;
        bar.style.width = (h > 0 ? Math.min(100, (window.scrollY / h) * 100) : 0) + '%';
      }
      spy();
    }
    if (bar || links.length) {
      window.addEventListener('scroll', onScroll, { passive: true });
      onScroll();
    }

    // 代码块复制（.code / .code-copy 由 md2html.py 生成）
    [].slice.call(document.querySelectorAll('.code')).forEach(function (box) {
      var btn = box.querySelector('.code-copy');
      var pre = box.querySelector('pre');
      if (!btn || !pre) return;
      var reset = null;
      btn.addEventListener('click', function () {
        var text = pre.innerText;
        function done() {
          btn.textContent = '已复制';
          clearTimeout(reset);
          reset = setTimeout(function () { btn.textContent = '复制'; }, 1400);
        }
        function fallback() {
          var ta = document.createElement('textarea');
          ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
          document.body.appendChild(ta); ta.select();
          try { document.execCommand('copy'); done(); } catch (e) { /* ignore */ }
          document.body.removeChild(ta);
        }
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(done, fallback);
        } else { fallback(); }
      });
    });
  }

  /* ── 启动 ──────────────────────────────────────────────────────────────── */
  paintIcons(document);
  initLang();
  initChrome();
  initReveal();
  initStats();
  initDocs();
})();
