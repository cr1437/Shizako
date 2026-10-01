/* Shizako 官网脚本。
   功能：图标注入（Lucide，见 assets/icons.js）、中英切换、手机端汉堡菜单、
        滚动进场动画、数字滚动、以及读取同源 stats.json 填充数据卡片。
   无第三方依赖；所有动画都尊重系统的「减少动效」设置。 */
(function () {
  'use strict';

  var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  // ── 英文词典（中文文案直接来自 HTML，脚本加载时捕获，见下方 ZH）────────
  var EN = {
    'nav.features': 'Features',
    'nav.activate': 'Activation',
    'nav.compat': 'Compatibility',
    'nav.faq': 'FAQ',
    'nav.api': 'Docs',
    'nav.download': 'Download',
    'nav.releases': 'All releases',
    'nav.repo': 'GitHub repository',

    'hero.tagline': 'Borrow privileged Android APIs through your catgirl assistant — no root, no flashing',
    'hero.sub': 'Wake a small privileged process through root / wireless debugging / computer ADB / Dhizuku, then lend that privilege to apps you trust. Every app needs your explicit approval, and you can revoke it any time.',
    'hero.download': 'Download latest',
    'hero.github': 'GitHub repository',
    'hero.hint': 'Android 7.0+ · wireless debugging needs Android 11+ · Apache-2.0',
    'quick.docs': 'Documentation',
    'quick.releases': 'All releases',
    'quick.issues': 'Report an issue',
    'quick.star': 'Star her',

    'features.title': 'What she can do',
    'features.lead': 'The idea in one sentence: lend system privileges to apps you trust, instead of rooting the whole phone.',
    'f1.t': 'Privileges without root',
    'f1.d': 'Approximate root-level power through ADB / wireless debugging — no unlocking, no flashing, warranty intact.',
    'f2.t': 'Passport system',
    'f2.d': 'Every app needs your explicit approval on first connect; the allowlist is local and revocable at any time.',
    'f3.t': 'Works with the official ecosystem',
    'f3.d': 'Apps built on the official Shizuku-API connect without a single line of changes.',
    'f4.t': 'Dhizuku mode',
    'f4.d': 'Become Device Owner with one tap for always-on privileges without root or wireless debugging; Dhizuku-API apps can borrow directly.',
    'f5.t': 'Four activation paths',
    'f5.d': 'Root / wireless debugging / computer ADB / Dhizuku on one page, with one-tap start once paired.',
    'f6.t': 'Automatic update checks',
    'f6.d': 'Every launch checks for a new version and offers an in-app download dialog; falls back to a working mirror when GitHub is blocked.',
    'f7.t': 'Toolbox',
    'f7.d': 'Module management, a shell console, accessibility manager, one-tap injection and automation broadcasts.',
    'f8.t': 'Two UI styles',
    'f8.d': 'Glassmorphism or Material 3, switchable in one tap, with dark/light and dynamic colour support.',

    'activate.title': 'How to wake her up',
    'activate.lead': 'Install, activate with whichever method suits you, then start issuing passports to apps.',
    'a1.pill': 'Recommended',
    'a1.t': 'Wireless debugging',
    'a1.d': 'On Android 11+ enable wireless debugging and scan/pair inside the app. Once paired, a single tap restarts her after a reboot.',
    'a2.t': 'Computer ADB',
    'a2.d': 'With a computer at hand: copy the adb command shown in the app and run it once. No version limits.',
    'a3.t': 'Root',
    'a3.d': 'If you already have root (KernelSU / Magisk), just wake her up — the easiest path of all.',
    'a4.t': 'Dhizuku (Device Owner)',
    'a4.d': 'Run the dpm set-device-owner command once on a computer (no accounts may be on the device) for root-free, wireless-free privileges that survive reboots.',

    'shots.title': 'What it looks like',
    'shots.lead': 'Real device screenshot (vivo V2417A / Android 16, dark glass style).',
    'shots.cap1': 'Home: service status, version and activation methods at a glance',

    'compat.title': 'Ecosystem compatibility',
    'c1.t': 'Shizuku-API apps connect unchanged',
    'c1.d': 'Apps built on the official dev.rikka.shizuku:api (MT Manager, Ice Box, SystemUI Tuner and more) connect as-is: the server only checks that an app requested the permission, and the first connection still asks for your approval.',
    'c2.t': 'Her own Device Owner',
    'c2.d': 'Once set as Device Owner, Dhizuku-API apps treat Shizako as Dhizuku. All privilege forwarding happens inside the Shizako process — no binder backdoor for third parties.',
    'c3.t': 'Why the package name differs',
    'c3.d': "Per upstream Shizuku's license, this project does not use the upstream application id or permission names; it ships com.churan.shizako.permission.* instead.",
    'c4.t': 'Cannot coexist with official Shizuku',
    'c4.d': 'The built-in compatibility bridge occupies the official provider authority, so installing both at once conflicts. Pick one.',

    'faq.title': 'FAQ',
    'q1': 'Do I need root?',
    'q1a': 'No. Wireless debugging or USB is enough; root simply adds more options.',
    'q2': 'How does this relate to Shizuku?',
    'q2a': 'It is compatible with the Shizuku-API ecosystem — apps built on the official SDK connect without changes. Per the upstream license it ships its own package and permission names, so it cannot coexist with the official app.',
    'q3': 'Is granting privileges to apps safe?',
    'q3a': 'Every app requires your explicit approval on first connect; the allowlist is local and revocable, and Dhizuku privilege forwarding stays inside the Shizako process.',
    'q4': 'Which Android versions are supported?',
    'q4a': 'Android 7.0 and above; wireless debugging activation requires Android 11+.',
    'q5': 'Does it collect my data?',
    'q5a': 'No. The app reports no usage data at all; the automatic update check only queries the public GitHub release API to see whether a newer version exists.',
    'q6': 'Is it open source? Can I build it myself?',
    'q6a': 'Yes, Apache-2.0. Clone the repository and run ./gradlew :manager:assembleRelease — JDK 17+ and Android SDK 36 required.',

    'final.title': 'Take her home',
    'final.lead': 'Download, wake her up, and start issuing passports to your apps.',
    'final.star': 'Star the project',

    'footer.desc': 'A catgirl-mascot privileged-API assistant. Open source, free, no ads.',
    'footer.links': 'Links',
    'footer.repo': 'GitHub repository',
    'footer.releases': 'Releases',
    'footer.issues': 'Issues',
    'footer.api': 'Developer API docs',
    'footer.community': 'Community',
    'footer.license': 'License',
    'footer.licenseText': 'Apache License 2.0. Upstream copyright RikkaApps; modifications copyright 初然. Icons: Lucide (ISC).',
    'footer.bottom': 'Made with love · she does not bite (ˊᗜˋ*)',

    'stat.version': 'Latest version',
    'stat.downloads': 'Total downloads',
    'stat.stars': 'Stars',
    'stat.platform': 'Platform',
    'stat.license': 'License',
    'stat.version.desc': 'Newest release tag on GitHub',
    'stat.downloads.desc': 'Sum of downloads across all releases',
    'stat.stars.desc': 'One tap feeds the catgirl'
  };

  var TITLES = {
    zh: 'Shizako — 不 Root 也能用系统特权 API',
    en: 'Shizako — privileged Android APIs without root'
  };

  // ── 图标注入 ─────────────────────────────────────────────────────────
  function paintIcons() {
    var icons = window.SHIZAKO_ICONS;
    if (!icons) return;
    [].slice.call(document.querySelectorAll('[data-icon]')).forEach(function (el) {
      var body = icons[el.getAttribute('data-icon')];
      if (!body || el.getAttribute('data-painted') === '1') return;
      el.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
        + 'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">'
        + body + '</svg>';
      el.setAttribute('data-painted', '1');
    });
  }

  // ── 中英切换 ─────────────────────────────────────────────────────────
  var nodes = [].slice.call(document.querySelectorAll('[data-i18n]'));
  var ZH = {};
  nodes.forEach(function (el) { ZH[el.getAttribute('data-i18n')] = el.innerHTML; });

  var langBtn = document.getElementById('langBtn');
  var current = 'zh';

  function apply(lang) {
    var dict = lang === 'en' ? EN : ZH;
    nodes.forEach(function (el) {
      var key = el.getAttribute('data-i18n');
      if (dict[key] != null) el.innerHTML = dict[key];
    });
    document.documentElement.lang = lang === 'en' ? 'en' : 'zh-CN';
    document.title = TITLES[lang] || TITLES.zh;
    if (langBtn) langBtn.textContent = lang === 'en' ? '中文' : 'EN';
    current = lang;
    try { localStorage.setItem('shizako.lang', lang); } catch (e) { /* 忽略 */ }
  }

  var saved = null;
  try { saved = localStorage.getItem('shizako.lang'); } catch (e) { /* 忽略 */ }
  apply(saved || ((navigator.language || '').toLowerCase().indexOf('zh') === 0 ? 'zh' : 'en'));
  if (langBtn) {
    langBtn.addEventListener('click', function () { apply(current === 'zh' ? 'en' : 'zh'); });
  }

  // ── 顶栏滚动态 + 手机端汉堡菜单 ──────────────────────────────────────
  var nav = document.getElementById('nav');
  var toggle = document.getElementById('navToggle');
  var menu = document.getElementById('mobileMenu');

  function onScroll() { if (nav) nav.classList.toggle('scrolled', window.scrollY > 8); }

  function closeMenu() {
    if (!menu) return;
    menu.classList.remove('open');
    if (toggle) toggle.setAttribute('aria-expanded', 'false');
  }

  if (toggle && menu) {
    // 菜单顶部对齐真实顶栏高度（写死 56px 会在有安全区/大字号时错位）
    function syncNavHeight() {
      if (nav) document.documentElement.style.setProperty('--navh', nav.offsetHeight + 'px');
    }
    syncNavHeight();
    window.addEventListener('resize', function () { syncNavHeight(); closeMenu(); });
    window.addEventListener('orientationchange', function () { syncNavHeight(); closeMenu(); });

    toggle.addEventListener('click', function () {
      var open = menu.classList.toggle('open');
      toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
    menu.addEventListener('click', function (e) { if (e.target.tagName === 'A') closeMenu(); });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeMenu(); });
    document.addEventListener('click', function (e) {
      if (!menu.classList.contains('open')) return;
      if (menu.contains(e.target) || toggle.contains(e.target)) return;
      closeMenu();
    });
  }

  window.addEventListener('scroll', onScroll, { passive: true });

  // ── 数字滚动（进入视口时从 0 递增到目标值）───────────────────────────
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
  function fmt(v) { return Number(v).toLocaleString('en-US'); }
  var counted = {};

  function startCounts() {
    [].slice.call(document.querySelectorAll('[data-stat]')).forEach(function (el) {
      var key = el.getAttribute('data-stat');
      if (counted[key]) return;
      var txt = (el.textContent || '').trim();
      // 只有「纯数字」的卡片才做数字滚动。
      // 版本号是 zako3.12 这种字符串，早先这里用「提取所有数字」判断，
      // 于是它被当成 312 滚了一遍并覆盖掉原文本（真机上显示成 312）。
      if (!/^[\d,]+$/.test(txt)) return;
      var raw = txt.replace(/[^\d]/g, '');
      if (!raw) return;
      counted[key] = true;
      countUp(el, parseInt(raw, 10));
    });
  }

  // ── 滚动进场动画 ─────────────────────────────────────────────────────
  var revealTargets = [].slice.call(document.querySelectorAll(
    '.section > h2, .section > .lead, .section .card, .shots figure, .faq details, .cta-final > *'
  ));
  if (!reduceMotion && 'IntersectionObserver' in window && revealTargets.length) {
    revealTargets.forEach(function (el, i) {
      el.classList.add('reveal');
      el.style.transitionDelay = Math.min(i % 6, 5) * 55 + 'ms';
    });
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (!en.isIntersecting) return;
        en.target.classList.add('in');
        io.unobserve(en.target);
        if (en.target.closest && en.target.closest('.stats-grid')) startCounts();
      });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.08 });
    revealTargets.forEach(function (el) { io.observe(el); });
    // 首屏的数据卡片可能已经在视口内，直接开始滚动
    setTimeout(startCounts, 300);
  } else {
    startCounts();
  }

  // ── 数据卡片：读同源 stats.json（定时 Action 刷新）───────────────────
  // 刻意不直接调 GitHub API：api.github.com 在部分网络（含国内）会解析失败。
  fetch('stats.json', { cache: 'no-cache' })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (s) {
      if (!s) return;
      document.querySelectorAll('[data-stat]').forEach(function (el) {
        var key = el.getAttribute('data-stat');
        var val = s[key];
        if (val === undefined || val === null || val === '') return;
        if (!counted[key] && /^\d+$/.test(String(val))) { counted[key] = true; countUp(el, Number(val)); }
        else if (!/^\d+$/.test(String(val))) { el.textContent = String(val); }
      });
    })
    .catch(function () { /* 静默：兜底值已在 HTML 里 */ });

  paintIcons();
  onScroll();
})();
