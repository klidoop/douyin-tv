/*
 * DouyinTV content script.
 *
 * Runs inside douyin.com (GeckoView). Opens a native port to the Android app and
 * turns remote-control commands into page actions:
 *   prev / next   -> previous / next video in the feed
 *   toggle        -> play / pause the active video
 *   play / pause
 *   seekBack / seekFwd -> ±5s
 * Also: auto-advance when a video ends, and periodic memory hygiene
 * (unload <video> elements that are far off-screen so a 1.6GB box doesn't drown).
 */
(function () {
  'use strict';
  if (window.__douyinTvLoaded) return;
  window.__douyinTvLoaded = true;

  const NATIVE_APP = 'douyintv';
  let port = null;

  // --------------------------------------------------------------- native port

  function connect() {
    try {
      port = browser.runtime.connectNative(NATIVE_APP);
      port.onMessage.addListener(onCommand);
      port.onDisconnect.addListener(() => { port = null; setTimeout(connect, 1500); });
      notify('ready', { url: location.href });
    } catch (e) {
      port = null;
      setTimeout(connect, 1500);
    }
  }

  function notify(evt, data) {
    if (!port) return;
    try { port.postMessage(Object.assign({ evt }, data || {})); } catch (_) {}
  }

  function onCommand(msg) {
    const cmd = msg && msg.cmd;
    switch (cmd) {
      case 'next': return gotoVideo(+1);
      case 'prev': return gotoVideo(-1);
      case 'toggle': return togglePlay();
      case 'play': return withVideo(v => v.play().catch(() => {}));
      case 'pause': return withVideo(v => v.pause());
      case 'seekBack': return withVideo(v => { v.currentTime = Math.max(0, v.currentTime - 5); });
      case 'seekFwd': return withVideo(v => { v.currentTime = Math.min(v.duration || 1e9, v.currentTime + 5); });
    }
  }

  // --------------------------------------------------------------- video helpers

  /** The main video = the largest <video> currently intersecting the viewport. */
  function mainVideo() {
    const vw = window.innerWidth, vh = window.innerHeight;
    let best = null, bestArea = 0;
    for (const v of document.querySelectorAll('video')) {
      const r = v.getBoundingClientRect();
      const w = Math.max(0, Math.min(r.right, vw) - Math.max(r.left, 0));
      const h = Math.max(0, Math.min(r.bottom, vh) - Math.max(r.top, 0));
      const area = w * h;
      if (area > bestArea) { bestArea = area; best = v; }
    }
    return best;
  }

  function withVideo(fn) {
    const v = mainVideo();
    if (v) fn(v);
  }

  function togglePlay() {
    withVideo(v => { if (v.paused) v.play().catch(() => {}); else v.pause(); });
  }

  /** Nearest ancestor that actually scrolls vertically. */
  function scrollParent(el) {
    let n = el && el.parentElement;
    while (n && n !== document.body) {
      const cs = getComputedStyle(n);
      if (/(auto|scroll)/.test(cs.overflowY) && n.scrollHeight > n.clientHeight + 10) return n;
      n = n.parentElement;
    }
    return document.scrollingElement || document.documentElement;
  }

  function fireKey(target, key, keyCode) {
    const init = { key, code: key, keyCode, which: keyCode, bubbles: true, cancelable: true };
    for (const type of ['keydown', 'keyup']) {
      try { target.dispatchEvent(new KeyboardEvent(type, init)); } catch (_) {}
    }
  }

  /**
   * dir = +1 next, -1 previous.
   * 1) Ask the page politely via its own ArrowDown/ArrowUp shortcut.
   * 2) If the active video didn't change, scroll the feed container by one viewport.
   */
  function gotoVideo(dir) {
    const before = mainVideo();
    const beforeSrc = before ? (before.currentSrc || before.src) : null;
    const key = dir > 0 ? 'ArrowDown' : 'ArrowUp';
    const code = dir > 0 ? 40 : 38;

    fireKey(document.activeElement || document.body, key, code);
    fireKey(document, key, code);

    setTimeout(() => {
      const after = mainVideo();
      const afterSrc = after ? (after.currentSrc || after.src) : null;
      if (after && after !== before) return;            // page handled it
      if (afterSrc && afterSrc !== beforeSrc) return;   // same element, new source
      const sp = scrollParent(before || document.body);
      const step = (sp === document.documentElement || sp === document.body) ? window.innerHeight : sp.clientHeight;
      sp.scrollBy({ top: dir * step, left: 0, behavior: 'smooth' });
      setTimeout(() => withVideo(v => { if (v.paused) v.play().catch(() => {}); }), 600);
    }, 450);
  }

  // --------------------------------------------------------------- auto-advance

  const hooked = new WeakSet();
  function hookVideo(v) {
    if (hooked.has(v)) return;
    hooked.add(v);
    v.addEventListener('ended', () => {
      // Only advance if this is still the video on screen (feed may have moved already).
      if (mainVideo() === v) setTimeout(() => gotoVideo(+1), 300);
    });
  }

  // --------------------------------------------------------------- memory hygiene

  /** Detach media far from the viewport so the decoder/buffers are released. */
  function trimOffscreenVideos() {
    const vh = window.innerHeight;
    for (const v of document.querySelectorAll('video')) {
      const r = v.getBoundingClientRect();
      const far = r.bottom < -2 * vh || r.top > 3 * vh;
      if (far && !v.paused) { try { v.pause(); } catch (_) {} }
      if (far && v.readyState > 0 && !v.dataset.tvTrimmed) {
        try { v.dataset.tvTrimmed = '1'; v.removeAttribute('src'); v.load(); } catch (_) {}
      }
    }
  }

  // --------------------------------------------------------------- bootstrap

  function scan() {
    document.querySelectorAll('video').forEach(hookVideo);
  }

  function start() {
    scan();
    new MutationObserver(scan).observe(document.documentElement, { childList: true, subtree: true });
    setInterval(trimOffscreenVideos, 8000);
    // Keep focus somewhere sane so the page's own shortcuts keep working.
    if (document.activeElement === document.body) {
      const v = mainVideo();
      if (v) v.setAttribute('tabindex', '-1');
    }
  }

  connect();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, { once: true });
  } else {
    start();
  }
})();
