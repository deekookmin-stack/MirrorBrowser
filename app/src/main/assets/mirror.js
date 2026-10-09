(function () {
  if (window.__mirrorInit) return;
  window.__mirrorInit = true;

  var B = window.AndroidMirror;
  var isSource = !!(B && B.isSource());

  function esc(s) {
    return (window.CSS && CSS.escape) ? CSS.escape(s) : String(s).replace(/[^a-zA-Z0-9_-]/g, '\\$&');
  }

  function selector(el) {
    if (!el || el.nodeType !== 1) return null;
    if (el === document.body) return 'body';
    if (el === document.documentElement) return 'html';
    if (el.id) {
      var q = '#' + esc(el.id);
      try { if (document.querySelectorAll(q).length === 1) return q; } catch (e) {}
    }
    var parts = [];
    while (el && el.nodeType === 1 && el !== document.body && el !== document.documentElement) {
      var i = 1, sib = el;
      while ((sib = sib.previousElementSibling)) i++;
      parts.unshift(el.tagName.toLowerCase() + ':nth-child(' + i + ')');
      el = el.parentElement;
    }
    return 'body > ' + parts.join(' > ');
  }

  function send(o) {
    try { B.send(JSON.stringify(o)); } catch (e) {}
  }

  /* ---------------- SOURCE: capture and forward ---------------- */
  if (isSource) {
    var BTN = 'button, a[href], [role="button"], input[type="submit"], input[type="button"]';

    // Taps: normal mirroring, or "fire sync" for buttons/links when armed
    document.addEventListener('click', function (e) {
      if (!e.isTrusted) return;
      var t = e.target;
      if (t.tagName === 'INPUT' && (t.type === 'checkbox' || t.type === 'radio')) return;
      var armed = false;
      try { armed = B.armed(); } catch (err) {}
      var b = (armed && t.closest) ? t.closest(BTN) : null;
      if (b) {
        e.preventDefault();
        e.stopImmediatePropagation();
        try { B.fire(JSON.stringify({ t: 'click', s: selector(b), f: 1 })); } catch (err) {}
      } else {
        send({ t: 'click', s: selector(t) });
      }
    }, true);

    function onValue(e) {
      if (!e.isTrusted) return;
      var el = e.target, o = { t: 'val', s: selector(el) };
      if (el.tagName === 'INPUT' && (el.type === 'checkbox' || el.type === 'radio')) o.c = el.checked;
      else if ('value' in el) o.v = el.value;
      else return;
      send(o);
    }
    document.addEventListener('input', onValue, true);
    document.addEventListener('change', onValue, true);

    // Enter: normal mirroring, or "fire sync" when armed
    document.addEventListener('keydown', function (e) {
      if (!e.isTrusted || e.key !== 'Enter') return;
      var t = e.target;
      var o = { t: 'key', s: selector(t), k: 'Enter' };
      var armed = false;
      try { armed = B.armed(); } catch (err) {}
      if (armed && t.tagName !== 'TEXTAREA' && !t.isContentEditable) {
        e.preventDefault();
        e.stopImmediatePropagation();
        o.f = 1;
        try { B.fire(JSON.stringify(o)); } catch (err) {}
      } else {
        send(o);
      }
    }, true);

    // Scrolling: throttled to ~25 updates/sec, selectors cached
    var sp = {}, timer = null, last = 0, cache = new WeakMap();
    function cached(el) {
      var s = cache.get(el);
      if (!s) { s = selector(el); cache.set(el, s); }
      return s;
    }
    function flushScroll() {
      timer = null;
      last = Date.now();
      for (var k in sp) send({ t: 'scroll', s: k, y: sp[k] });
      sp = {};
    }
    document.addEventListener('scroll', function (e) {
      var t = e.target, key, r, m;
      if (t === document || t === document.documentElement || t === document.body) {
        key = '';
        m = document.documentElement.scrollHeight - window.innerHeight;
        r = m > 0 ? window.scrollY / m : 0;
      } else if (t.nodeType === 1) {
        key = cached(t);
        m = t.scrollHeight - t.clientHeight;
        r = m > 0 ? t.scrollTop / m : 0;
      } else return;
      sp[key] = r;
      if (!timer) timer = setTimeout(flushScroll, Math.max(0, 40 - (Date.now() - last)));
    }, true);
  }

  /* ---------------- REPLAY (mirrors, and the source when firing) ---------------- */
  function apply(o) {
    if (o.t === 'scroll' && !o.s) {
      var max = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
      try { window.scrollTo({ top: o.y * max, left: window.scrollX, behavior: 'instant' }); }
      catch (e) { window.scrollTo(window.scrollX, o.y * max); }
      return;
    }

    var el = o.s ? document.querySelector(o.s) : null;
    if (!el) return;

    if (o.t === 'scroll') {
      el.scrollTop = o.y * Math.max(0, el.scrollHeight - el.clientHeight);

    } else if (o.t === 'click') {
      ['pointerdown', 'mousedown', 'pointerup', 'mouseup'].forEach(function (type) {
        try {
          var C = type.indexOf('pointer') === 0 ? PointerEvent : MouseEvent;
          el.dispatchEvent(new C(type, { bubbles: true, cancelable: true, view: window }));
        } catch (e) {}
      });
      if (el.focus) try { el.focus(); } catch (e) {}
      el.click();

    } else if (o.t === 'val') {
      if (o.c !== undefined) {
        if (el.checked !== o.c) {
          el.checked = o.c;
          el.dispatchEvent(new Event('input', { bubbles: true }));
          el.dispatchEvent(new Event('change', { bubbles: true }));
        }
      } else if (el.value !== o.v) {
        var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype
                  : el.tagName === 'SELECT' ? HTMLSelectElement.prototype
                  : HTMLInputElement.prototype;
        var d = Object.getOwnPropertyDescriptor(proto, 'value');
        if (d && d.set) d.set.call(el, o.v); else el.value = o.v;
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
      }

    } else if (o.t === 'key') {
      var opts = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
      var ok = el.dispatchEvent(new KeyboardEvent('keydown', opts));
      el.dispatchEvent(new KeyboardEvent('keypress', opts));
      el.dispatchEvent(new KeyboardEvent('keyup', opts));
      var form = el.form;
      if (ok && form && el.tagName === 'INPUT') {
        if (form.requestSubmit) form.requestSubmit(); else form.submit();
      } else if (ok && o.f && (el.tagName === 'BUTTON' || el.tagName === 'A')) {
        el.click();
      }
    }
  }

  window.__mirrorReplay = function (json) {
    if (isSource) return;
    var list;
    try { list = JSON.parse(json); } catch (e) { return; }
    if (!Array.isArray(list)) list = [list];
    for (var i = 0; i < list.length; i++) {
      try { apply(list[i]); } catch (e) {}
    }
  };

  // Synchronized fire: runs in the source AND every mirror
  window.__mirrorFire = function (json) {
    var o;
    try { o = JSON.parse(json); } catch (e) { return; }
    try { apply(o); } catch (e) {}
  };
})();
