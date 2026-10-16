// ==UserScript==
// @name Banana Plus — защита переходов
// @namespace local.bananaplus.guard
// @version 2.1.3
// @description Общая защита от рекламных окон и подмены вкладки с сохранением обычных обработчиков страниц.
// @match https://*/*
// @match http://*/*
// @include /^https?:\/\/.*$/
// @run-at document-start
// @grant none
// @grant frames
// ==/UserScript==

(() => {
  'use strict';
  if (!/^https?:$/.test(location.protocol) || window.__bananaClickGuard) return;
  // The browser-owned manager is not website content.
  if (location.hostname === '127.0.0.1' && /^\/[a-f0-9-]{72}\//.test(location.pathname)) return;
  const add = EventTarget.prototype.addEventListener;
  const nativeOpen = window.open;
  const later = window.setTimeout.bind(window);
  const now = performance.now.bind(performance);
  const counts = { version: '2.1.3', scope: 'all-http-sites', blockedOpens: 0,
    blockedNavigations: 0, blockedGestures: 0, allowedOpens: 0, recoveredLinks: 0, recoveredMedia: 0, blockedLinkChanges: 0, guardedFrames: 0, navigationAPI: !!window.navigation };
  const adHost = host => /(^|\.)(pemsrv\.com|magsrv\.com|exosrv\.com|chubbyexemplaryhardiness\.com|doubleclick\.net|googlesyndication\.com|googleadservices\.com)$/.test(host);
  const playerSelector = 'video,audio,.video-js,.fluid_video_wrapper,.player_cont,.play-parent,[role="slider"]';
  let gesture = null, approved = null, noticeHost = null, noticeRoot = null, noticeTimer = null, pending = null;
  let windowIncident = false;
  const parse = value => { try { return new URL(String(value), location.href); } catch (_) { return null; } };
  const http = url => url && /^https?:$/.test(url.protocol);
  const normalizedHost = host => host.replace(/^(www|m)\./, '');
  const related = url => url.origin === location.origin ||
    (normalizedHost(url.hostname) === normalizedHost(location.hostname) &&
     !(location.protocol === 'https:' && url.protocol !== 'https:'));
  const freshGesture = () => gesture && now() - gesture.time < 1500 ? gesture : null;
  const exact = (a, b) => !!a && !!b && a.href === b.href;
  Object.defineProperty(window, '__bananaClickGuard', { configurable: false,
    get: () => Object.freeze({ ...counts, pendingHost: pending?.url.hostname || null }) });

  function clearNotice() {
    noticeHost?.remove(); noticeHost = noticeRoot = pending = null; clearTimeout(noticeTimer);
  }
  function showBlocked(url, kind, target = '_blank', features = '') {
    if (!http(url) && url?.href !== 'about:blank') return;
    pending = { url, kind, target, features };
    // No persistent URL, title or credential logging.
    if (!document.documentElement) later(() => { if (pending) renderNotice(); }, 100);
    else renderNotice();
  }
  function renderNotice() {
    if (!pending || !document.documentElement) return;
    if (!noticeHost?.isConnected) {
      noticeHost = document.createElement('div');
      noticeHost.setAttribute('data-banana-navigation-guard', '');
      noticeHost.style.cssText = 'all:initial!important;position:fixed!important;left:8px!important;right:8px!important;bottom:12px!important;z-index:2147483647!important;display:block!important;pointer-events:auto!important';
      noticeRoot = noticeHost.attachShadow({ mode: 'closed' });
      const style = document.createElement('style');
      style.textContent = ':host{color-scheme:dark}div{display:flex;align-items:center;gap:8px;padding:12px;border-radius:14px;background:#24262b;color:white;box-shadow:0 3px 16px #0008;font:14px/1.3 system-ui,sans-serif}span{flex:1;overflow-wrap:anywhere}button{border:0;border-radius:8px;padding:12px;background:#ffe063;color:#171717;font:600 14px system-ui;white-space:nowrap}button:last-child{background:transparent;color:white}';
      const box = document.createElement('div'); box.setAttribute('role', 'status');
      const label = document.createElement('span');
      const open = document.createElement('button'); open.type = 'button'; open.textContent = 'Открыть';
      const close = document.createElement('button'); close.type = 'button'; close.textContent = '×'; close.setAttribute('aria-label', 'Закрыть');
      add.call(open, 'click', event => {
        if (!event.isTrusted || !pending) return;
        event.stopImmediatePropagation();
        const request = pending; clearNotice();
        if (request.kind === 'navigation') {
          approved = { href: request.url.href, expires: now() + 1000 }; location.assign(request.url.href);
        } else Reflect.apply(nativeOpen, window, [request.url.href, request.target, request.features]);
      });
      add.call(close, 'click', event => { if (event.isTrusted) clearNotice(); });
      for (const type of ['click', 'pointerdown', 'pointerup', 'touchstart', 'touchend', 'mousedown', 'mouseup']) {
        add.call(noticeRoot, type, event => event.stopPropagation());
      }
      box.append(label, open, close); noticeRoot.append(style, box); document.documentElement.append(noticeHost);
    }
    noticeRoot.querySelector('span').textContent = 'Заблокирован переход: ' + (pending.url.hostname || 'новое окно');
    clearTimeout(noticeTimer); noticeTimer = later(clearNotice, 12000);
  }

  function isPlayer(element) {
    if (element.closest(playerSelector)) return true;
    for (let p = element.parentElement, depth = 0; p && depth < 4; p = p.parentElement, depth++) {
      if (p.matches('body,html,form,main,header,nav')) break;
      if (p.querySelector('video,audio,iframe[allowfullscreen],iframe[allow*="autoplay"]')) return true;
    }
    return false;
  }

  function selectedMedia(event, element, covered = false) {
    const control = element.closest('button,input,select,textarea,[role="button"],[role="slider"]');
    const label = control && ((control.getAttribute('aria-label') || '') + ' ' + (control.getAttribute('title') || '') + ' ' + control.textContent.slice(0,80) + ' ' + control.className);
    if (control && !/play|resume|воспроиз|смотр|пуск/i.test(label) &&
        !control.matches('.vjs-big-play-button,.vjs-play-control,.fluid_button_play')) return null;
    const link = element.closest('a[href]');
    if (!covered && link && http(parse(link.href)) && !/^[#]/.test(link.getAttribute('href'))) return null;
    for (const video of document.querySelectorAll('video')) {
      const r = video.getBoundingClientRect();
      const inside = event.clientX >= r.left && event.clientX <= r.right &&
        event.clientY >= r.top && event.clientY < r.bottom - Math.min(40,r.height/4);
      const nearPlay = control && isPlayer(control) && control.parentElement?.contains(video);
      if (r.width > 0 && r.height > 0 && (inside || nearPlay)) return {
        video, paused: video.paused, source: video.currentSrc || video.src, stream: video.srcObject
      };
    }
    return null;
  }
  function recoverMedia(g) {
    const m = g?.media;
    if (!m || !m.paused || !g.blocked || !g.clickEvent?.isTrusted || g.mediaPending ||
        gesture !== g || now() - g.time > 1000 || document.visibilityState !== 'visible') return;
    g.mediaPending = true;
    later(() => {
      const v = m.video;
      if (gesture !== g || document.visibilityState !== 'visible' || !v.isConnected || !v.paused ||
          m.source !== (v.currentSrc || v.src) || m.stream !== v.srcObject) return;
      // Resume only the bound video selected by this trusted Play gesture.
      // A click that began while playing (Pause), other controls and source
      // replacements must never cause an automatic resume.
      Promise.resolve(v.play()).then(() => { if (!v.paused) counts.recoveredMedia++; }).catch(() => {});
    }, 0);
  }

  function recoverLink(g) {
    if (!g?.anchor || !http(g.url) || g.download || g.navigated || g.recovering ||
        !g.blocked || !g.clickEvent || gesture !== g || location.href !== g.startURL) return;
    if (g.target === '_blank' && !g.clickEvent.defaultPrevented) return;
    g.recovering = true;
    later(() => {
      if (gesture !== g || g.navigated || location.href !== g.startURL) return;
      g.navigated = true; counts.recoveredLinks++;
      approved = { href: g.url.href, expires: now() + 1000 };
      // A detached anchor avoids the same hostile document click delegation.
      const link = document.createElement('a');
      link.href = g.url.href; link.target = g.target || '_self';
      link.rel = g.anchor.rel; link.click();
    }, 0);
  }
  function blockedAction() {
    windowIncident = true;
    if (gesture) { gesture.blocked = true; recoverLink(gesture); recoverMedia(gesture); }
  }


  function rejectLinkChange(anchor, value) {
    const g = freshGesture();
    if (!g || g.navigated || g.anchor !== anchor || !http(g.url) || exact(parse(value), g.url)) return false;
    counts.blockedLinkChanges++; g.blocked = true;
    return true;
  }
  // Snapshot the requested address before page handlers can swap href. This
  // also covers target=_blank, which does not emit navigate in its opener.
  for (const type of [HTMLAnchorElement, HTMLAreaElement]) {
    const descriptor = Object.getOwnPropertyDescriptor(type.prototype, 'href');
    if (descriptor?.set && descriptor.configurable) Object.defineProperty(type.prototype, 'href', {
      ...descriptor, set(value) { if (!rejectLinkChange(this, value)) descriptor.set.call(this, value); }
    });
  }
  const nativeSetAttribute = Element.prototype.setAttribute;
  Element.prototype.setAttribute = function(name, value) {
    if (String(name).toLowerCase() === 'href' &&
        (this instanceof HTMLAnchorElement || this instanceof HTMLAreaElement) && rejectLinkChange(this, value)) return;
    return Reflect.apply(nativeSetAttribute, this, [name, value]);
  };

  function overlayAnchor(anchor) {
    if (anchor.textContent.trim() || anchor.querySelector('img,svg,video,canvas')) return false;
    const r = anchor.getBoundingClientRect(), s = getComputedStyle(anchor);
    return /^(fixed|absolute)$/.test(s.position) && r.width * r.height > innerWidth * innerHeight * .5;
  }
  function captureGesture(event) {
    if (event.type === 'keydown' && event.key !== 'Enter') return;
    const path = event.composedPath(); if (path.includes(noticeHost)) return;
    const element = path.find(e => e instanceof Element); if (!element) return;
    const anchor = path.find(e => (e instanceof HTMLAnchorElement || e instanceof HTMLAreaElement) && e.hasAttribute('href'));
    const url = anchor && parse(anchor.href);
    const covered = anchor && overlayAnchor(anchor);
    if (anchor && (adHost(url?.hostname || '') || anchor.relList.contains('sponsored') || covered)) {
      event.stopImmediatePropagation();
      if (event.cancelable && /^(click|auxclick)$/.test(event.type)) {
        event.preventDefault(); counts.blockedGestures++;
        if (covered && event.isTrusted) {
          const media = selectedMedia(event, element, true);
          anchor.style.pointerEvents = 'none';
          if (media) { gesture = { media, blocked: true, clickEvent: event, time: now() }; recoverMedia(gesture); }
        }
        showBlocked(url, anchor.target === '_blank' ? 'popup' : 'navigation', anchor.target || '_self');
      }
      return;
    }
    if (!event.isTrusted) {
      if (anchor && http(url) && (!related(url) || anchor.target === '_blank') &&
          !exact(freshGesture()?.url, url) && /^(click|auxclick)$/.test(event.type)) {
        event.preventDefault(); event.stopImmediatePropagation(); counts.blockedGestures++;
        showBlocked(url, anchor.target === '_blank' ? 'popup' : 'navigation', anchor.target || '_self');
      }
      return;
    }
    const control = element.closest('button,input,select,textarea,summary,[role="button"],[contenteditable="true"]');
    const player = isPlayer(element);
    // One popup budget across all events generated by a single touch.
    if (!gesture || gesture.element !== element || now() - gesture.time > 1000 ||
        (gesture.clickEvent && /^(pointerdown|touchstart|mousedown|keydown)$/.test(event.type))) {
      windowIncident = false;
      gesture = { element, url, anchor, target: anchor?.target, download: anchor?.hasAttribute('download'),
        startURL: location.href, media: player ? selectedMedia(event, element) : null,
        blocked: false, navigated: false, recovering: false, control: !!control && !player, controlElement: control, player: !!player,
        popupUsed: false, popupBlocked: false, live: true, time: now() };
    } else { gesture.time = now(); gesture.live = true; }
    const current = gesture;
    if (event.type === 'click' || event.type === 'auxclick') {
      current.clickEvent = event;
      if (!current.media && player) current.media = selectedMedia(event, element);
      later(() => { recoverLink(current); recoverMedia(current); }, 0);
    }
    later(() => { if (gesture === current) current.live = false; }, 0);
    // Keep delegated menus, SPA routers and media controls intact.
  }
  for (const type of ['pointerdown', 'pointerup', 'touchstart', 'touchend', 'mousedown', 'mouseup', 'click', 'auxclick', 'keydown']) {
    add.call(window, type, captureGesture, { capture: true, passive: type.startsWith('touch') });
  }
  add.call(window, 'submit', event => {
    const previous = freshGesture();
    if (!event.isTrusted || !(event.target instanceof HTMLFormElement) ||
        !previous || !event.target.contains(previous.element)) return;
    const url = parse(event.submitter?.hasAttribute('formaction') ? event.submitter.formAction : event.target.action);
    gesture = { element: event.target, url, form: true, control: true, controlElement: event.target, player: false,
      popupUsed: false, popupBlocked: false, live: true, time: now() };
    const current = gesture; later(() => { if (gesture === current) current.live = false; }, 0);
  }, true);
  function guardedOpen(value = '', target = '_blank', features = '') {
    const url = value === '' ? parse('about:blank') : parse(value), g = freshGesture();
    const directControl = window === window.top && g?.control && !g.anchor && window.event?.currentTarget === g.controlElement;
    const explicit = g && (exact(g.url, url) || directControl);
    if (url && !adHost(url.hostname) && g?.live && navigator.userActivation?.isActive && explicit &&
        !g.popupUsed && !g.popupBlocked && (http(url) || url.href === 'about:blank')) {
      g.popupUsed = true; windowIncident = true; counts.allowedOpens++;
      return Reflect.apply(nativeOpen, window, [value, target, features]);
    }
    if (g) g.popupBlocked = true;
    counts.blockedOpens++; blockedAction(); showBlocked(url, 'popup', target, features); return null;
  }
  Object.defineProperty(window, 'open', { configurable: false, enumerable: true,
    get: () => guardedOpen, set: () => {} });
  if (window.navigation) add.call(window.navigation, 'navigate', event => {
    if (!event.isTrusted || !event.cancelable || event.navigationType === 'traverse' || event.navigationType === 'reload') return;
    const url = parse(event.destination.url); if (!http(url)) return;
    if (approved && approved.expires > now() && approved.href === url.href) { approved = null; return; }
    const g = freshGesture();
    // Chromium 146 exposes userInitiated but not sourceElement. This browser
    // flag distinguishes native link/form actions from location assignments.
    const nativeIntent = event.userInitiated === true;
    const requestedURL = exact(g?.url, url) || (g?.form && g.url &&
      g.url.origin === url.origin && g.url.pathname === url.pathname);
    const popupChain = windowIncident && !nativeIntent && !requestedURL && !event.hashChange;
    const linkHijack = g?.anchor && !requestedURL && !event.destination.sameDocument && !event.hashChange;
    const automaticDocument = window === window.top && !event.destination.sameDocument &&
      !nativeIntent && !requestedURL && !(g?.control && g.live);
    if (adHost(url.hostname) || popupChain || linkHijack || automaticDocument || (!related(url) && !nativeIntent && !requestedURL)) {
      event.preventDefault(); counts.blockedNavigations++; blockedAction(); showBlocked(url, 'navigation', '_self');
    } else if (gesture) gesture.navigated = true;
  }, true);

  // A blank same-origin iframe otherwise exposes an unguarded copy of open().
  const frameWindows = new WeakMap();
  function protectFrameWindow(child) {
    if (!child || child === window) return child;
    try {
      if (child.__bananaClickGuard || frameWindows.get(child) === child.open) return child;
      const blockedFrameOpen = function(value = '', target = '_blank', features = '') {
        let url; try { url = new URL(value || 'about:blank', child.location.href); } catch (_) { return null; }
        counts.blockedOpens++; blockedAction(); showBlocked(url, 'popup', target, features); return null;
      };
      Object.defineProperty(child, 'open', { configurable: true, get: () => blockedFrameOpen, set: () => {} });
      frameWindows.set(child, blockedFrameOpen);
    } catch (_) { /* Cross-origin media frames are constrained by sandbox below. */ }
    return child;
  }
  const contentWindow = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, 'contentWindow');
  if (contentWindow?.get && contentWindow.configurable) {
    Object.defineProperty(HTMLIFrameElement.prototype, 'contentWindow', { ...contentWindow,
      get() { return protectFrameWindow(contentWindow.get.call(this)); } });
  }
  const securedFrames = new WeakSet();
  const defaultSandbox = 'allow-scripts allow-same-origin allow-forms allow-modals allow-pointer-lock allow-downloads allow-presentation allow-storage-access-by-user-activation';
  function secureFrame(frame) {
    const url = parse(frame.getAttribute('src') || 'about:blank');
    const existing = frame.getAttribute('sandbox');
    if (http(url) && url.origin !== location.origin) {
      const tokens = (existing === null ? defaultSandbox : existing).split(/\s+/).filter(Boolean)
        .filter(token => !/^allow-(top-navigation(?:-by-user-activation|-to-custom-protocols)?|popups(?:-to-escape-sandbox)?)$/.test(token));
      const policy = tokens.join(' ');
      if (existing !== policy) {
        frame.setAttribute('sandbox', policy); counts.guardedFrames++;
        // Sandbox applies to the next document. Reload an already inserted
        // frame once so a page injected late is protected before interaction.
        if (!securedFrames.has(frame)) { securedFrames.add(frame); frame.src = frame.src; }
      }
    }
    protectFrameWindow(frame.contentWindow);
  }
  function scanFrames(root) {
    if (root.nodeType !== 1 && root.nodeType !== 9) return;
    if (root.matches?.('iframe')) secureFrame(root);
    root.querySelectorAll?.('iframe').forEach(secureFrame);
  }
  scanFrames(document);
  new MutationObserver(records => {
    for (const r of records) {
      if (r.type === 'attributes') { if (r.target.matches('iframe')) secureFrame(r.target); }
      else for (const node of r.addedNodes) scanFrames(node);
    }
  }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'sandbox'] });

})();
