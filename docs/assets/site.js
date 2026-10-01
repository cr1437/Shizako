/* Shizako 官网脚本：中英切换 + 顶栏滚动阴影。
   无依赖。中文文案直接来自 HTML（作为默认语言并在此处捕获），
   这里只额外提供英文词典，因此切换是双向的、不需要重复维护中文。 */
(function () {
  'use strict';

  var EN = {
    'nav.features': 'Features',
    'nav.activate': 'Activation',
    'nav.compat': 'Compatibility',
    'nav.faq': 'FAQ',
    'nav.api': 'API docs',
    'nav.download': 'Download',

    'hero.tagline': 'Borrow privileged Android APIs through your catgirl assistant — no root, no flashing',
    'hero.sub': 'Wake a small privileged process through root / wireless debugging / computer ADB / Dhizuku, then lend that privilege to apps you trust. Every app needs your explicit approval, and you can revoke it any time.',
    'hero.download': 'Download latest',
    'hero.github': 'GitHub repository',
    'hero.hint': 'Android 7.0+ · wireless debugging needs Android 11+ · Apache-2.0',

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
    'final.star': '⭐ Star the project',

    'footer.desc': 'A catgirl-mascot privileged-API assistant. Open source, free, no ads.',
    'footer.links': 'Links',
    'footer.repo': 'GitHub repository',
    'footer.releases': 'Releases',
    'footer.issues': 'Issues',
    'footer.api': 'Developer API docs',
    'footer.community': 'Community',
    'footer.license': 'License',
    'footer.licenseText': 'Apache License 2.0. Upstream copyright RikkaApps; modifications copyright 初然.',
    'footer.bottom': 'Made with love · she does not bite (ˊᗜˋ*)'
  };

  var TITLES = {
    zh: 'Shizako — 不 Root 也能用系统特权 API',
    en: 'Shizako — privileged Android APIs without root'
  };

  // 捕获 HTML 里的中文原文，作为 zh 词典（这样只需维护一份英文翻译）
  var nodes = document.querySelectorAll('[data-i18n]');
  var ZH = {};
  nodes.forEach(function (el) {
    ZH[el.getAttribute('data-i18n')] = el.innerHTML;
  });

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
    try { localStorage.setItem('shizako.lang', lang); } catch (e) { /* 隐私模式下忽略 */ }
  }

  // 初始语言：优先用户上次选择，其次跟随浏览器
  var saved = null;
  try { saved = localStorage.getItem('shizako.lang'); } catch (e) { /* ignore */ }
  var initial = saved || ((navigator.language || '').toLowerCase().indexOf('zh') === 0 ? 'zh' : 'en');
  apply(initial);

  if (langBtn) {
    langBtn.addEventListener('click', function () {
      apply(current === 'zh' ? 'en' : 'zh');
    });
  }

  // 顶栏滚动态
  var nav = document.getElementById('nav');
  function onScroll() {
    if (!nav) return;
    nav.classList.toggle('scrolled', window.scrollY > 8);
  }
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();
})();
