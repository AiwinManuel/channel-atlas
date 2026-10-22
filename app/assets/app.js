/* Channel Atlas: browse iptv-org's free channels by region, country, language and genre with a TV remote. */
(function () {
  'use strict';

  var A = window.Android || null;
  var COLS = 5, ROWS = 3, PAGE = COLS * ROWS, PICK_ROWS = 8;

  var SORTS = [
    { v: 'name', name: 'Name A–Z' },
    { v: 'namez', name: 'Name Z–A' },
    { v: 'country', name: 'Country' },
    { v: 'quality', name: 'Best quality first' },
    { v: 'streams', name: 'Most backup streams' }
  ];
  var CHIPS = [
    { id: 'region', label: 'Region' },
    { id: 'country', label: 'Country' },
    { id: 'lang', label: 'Language' },
    { id: 'genre', label: 'Genre' },
    { id: 'sort', label: 'Sort' },
    { id: 'search', label: 'Search' },
    { id: 'fav', label: '★ Favorites' },
    { id: 'random', label: 'Random' },
    { id: 'clear', label: 'Clear' }
  ];

  var D = null;                                   // the catalog
  var IX = { country: {}, lang: {}, genre: {}, region: {} };
  var S = load('ca.state', { region: '', country: '', lang: '', genre: '', sort: 'name', q: '', fav: false });
  var favs = load('ca.favs', {});
  var V = { zone: 'grid', chip: 0, idx: 0, top: -1, list: [], shownTop: -1, version: 0, shownVersion: -1, picker: null, pIdx: 0, pTop: 0, search: false };

  function $(id) { return document.getElementById(id); }
  function load(k, d) { try { var v = localStorage.getItem(k); if (v) { var o = JSON.parse(v); for (var p in o) d[p] = o[p]; } } catch (e) {} return d; }
  function save() { try { localStorage.setItem('ca.state', JSON.stringify(S)); localStorage.setItem('ca.favs', JSON.stringify(favs)); } catch (e) {} }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function fmt(n) { return n.toLocaleString('en-US'); }
  function log(m) { if (A) A.log(String(m)); }

  // ---------- Catalog

  function useCatalog(text) {
    var d;
    try { d = JSON.parse(text); } catch (e) { return false; }
    if (!d || !d.channels || !d.channels.length) return false;
    D = d;
    IX = { country: {}, lang: {}, genre: {}, region: {} };
    D.countries.forEach(function (c) { IX.country[c.code] = c; });
    D.languages.forEach(function (l) { IX.lang[l.code] = l; });
    D.genres.forEach(function (g) { IX.genre[g.id] = g; });
    D.regions.forEach(function (r) { r.set = {}; r.countries.forEach(function (c) { r.set[c] = 1; }); IX.region[r.code] = r; });
    D.channels.forEach(function (ch, i) {
      ch._o = i;
      ch._k = (ch.n + ' ' + (ch.a || []).join(' ')).toLowerCase();
      ch._q = 0;
      ch.s.forEach(function (s) { if (s.q > ch._q) ch._q = s.q; });
      ch._cn = IX.country[ch.c] ? IX.country[ch.c].name : ch.c;
    });
    // Filters saved earlier may point at something that's gone.
    if (S.region && !IX.region[S.region]) S.region = '';
    if (S.country && !IX.country[S.country]) S.country = '';
    if (S.lang && !IX.lang[S.lang]) S.lang = '';
    if (S.genre && !IX.genre[S.genre]) S.genre = '';
    status();
    refresh(null);
    return true;
  }

  function status() {
    if (!D) return;
    var h = Math.round((Date.now() - D.updated) / 3600000);
    $('status').textContent = fmt(D.channels.length) + ' channels · updated ' + (h < 1 ? 'just now' : h < 24 ? h + ' h ago' : Math.round(h / 24) + ' d ago');
  }

  function start() {
    var text = A ? A.catalog() : '';
    if (text && useCatalog(text)) {
      $('loading').classList.add('hidden');
      if (A.age() > 24 * 3600 * 1000) { $('status').textContent = 'Updating channel list…'; A.sync(); }
    } else if (A) {
      A.sync();
    } else {
      $('ltitle').textContent = 'Open this inside the app';
      $('ltext').textContent = 'The channel list is downloaded by the Android app.';
    }
  }

  // ---------- Filtering and sorting

  function match(ch, skip) {
    if (S.fav && !favs[ch.id]) return false;
    if (skip !== 'region' && S.region && !IX.region[S.region].set[ch.c]) return false;
    if (skip !== 'country' && S.country && ch.c !== S.country) return false;
    if (skip !== 'lang' && S.lang && ch.l.indexOf(S.lang) < 0) return false;
    if (skip !== 'genre' && S.genre && ch.g.indexOf(S.genre) < 0) return false;
    if (S.q && ch._k.indexOf(S.q.toLowerCase()) < 0) return false;
    return true;
  }

  function sorter() {
    switch (S.sort) {
      case 'namez': return function (a, b) { return b._o - a._o; };
      case 'country': return function (a, b) { return a._cn < b._cn ? -1 : a._cn > b._cn ? 1 : a._o - b._o; };
      case 'quality': return function (a, b) { return b._q - a._q || a._o - b._o; };
      case 'streams': return function (a, b) { return b.s.length - a.s.length || a._o - b._o; };
      default: return null;
    }
  }

  function refresh(keepId) {
    if (!D) return;
    var cur = keepId || (V.list[V.idx] && V.list[V.idx].id);
    var list = D.channels.filter(function (ch) { return match(ch, null); });
    var f = sorter();
    if (f) list.sort(f);
    V.list = list;
    V.version++;
    V.idx = 0;
    if (cur) for (var i = 0; i < list.length; i++) if (list[i].id === cur) { V.idx = i; break; }
    if (!list.length && V.zone === 'grid') V.zone = 'chips';
    render();
  }

  // ---------- Rendering

  function flag(c) { return IX.country[c] ? IX.country[c].flag : ''; }
  function genreOf(ch) { var g = ch.g[0]; return g ? (IX.genre[g] ? IX.genre[g].name : g) : ''; }
  function initials(n) { return n.replace(/[^A-Za-z0-9 ]/g, '').split(' ').filter(Boolean).slice(0, 2).map(function (w) { return w[0]; }).join('').toUpperCase() || '•'; }

  function chipValue(id) {
    switch (id) {
      case 'region': return S.region ? IX.region[S.region].name : 'All';
      case 'country': return S.country ? flag(S.country) + ' ' + IX.country[S.country].name : 'All';
      case 'lang': return S.lang ? IX.lang[S.lang].name : 'All';
      case 'genre': return S.genre ? IX.genre[S.genre].name : 'All';
      case 'sort': for (var i = 0; i < SORTS.length; i++) if (SORTS[i].v === S.sort) return SORTS[i].name; return '';
      case 'search': return S.q ? '“' + S.q + '”' : '';
      case 'fav': return S.fav ? 'On' : '';
    }
    return '';
  }
  function chipOn(id) {
    return (id === 'region' && S.region) || (id === 'country' && S.country) || (id === 'lang' && S.lang) ||
      (id === 'genre' && S.genre) || (id === 'search' && S.q) || (id === 'fav' && S.fav);
  }

  function renderChips() {
    var h = '';
    CHIPS.forEach(function (c, i) {
      var v = chipValue(c.id);
      h += '<div class="chip' + (chipOn(c.id) ? ' on' : '') + (V.zone === 'chips' && V.chip === i ? ' focus' : '') + '">' +
        '<span class="k">' + esc(c.label) + '</span>' + (v ? '<span class="v">' + esc(v) + '</span>' : '') + '</div>';
    });
    var row = $('chips');
    row.innerHTML = h;
    // Keep the focused chip on screen when the row is wider than the TV.
    var el = row.children[V.chip];
    if (el) {
      if (el.offsetLeft + el.offsetWidth + 40 > row.scrollLeft + row.clientWidth) row.scrollLeft = el.offsetLeft + el.offsetWidth + 40 - row.clientWidth;
      if (el.offsetLeft - 40 < row.scrollLeft) row.scrollLeft = Math.max(0, el.offsetLeft - 40);
    }
  }

  function card(ch, focus) {
    var tags = [];
    if (ch.geo) tags.push('<span class="tag">geo-blocked</span>');
    else if (ch.part) tags.push('<span class="tag">not 24/7</span>');
    var meta = flag(ch.c) + ' ' + esc(genreOf(ch) || ch._cn);
    return '<div class="card' + (focus ? ' focus' : '') + '">' +
      '<div class="lg"><span class="ini">' + esc(initials(ch.n)) + '</span>' +
      (ch.i ? '<img loading="lazy" src="' + esc(ch.i) + '" onload="this.parentNode.classList.add(\'ok\')" onerror="this.remove()">' : '') + '</div>' +
      '<div class="nm">' + (favs[ch.id] ? '<span class="star">★</span>' : '') + esc(ch.n) + '</div>' +
      '<div class="mt">' + meta + (tags.length ? ' · ' + tags.join(' ') : '') + '</div></div>';
  }

  function renderGrid() {
    var L = V.list, n = L.length, g = $('grid');
    if (!n) {
      V.shownVersion = V.version;
      g.innerHTML = '<div class="empty">No channels match these filters.<br>Try <b>Clear</b>, or another language or genre.</div>';
      $('summary').innerHTML = '<b>0</b> channels';
      return;
    }
    var row = Math.floor(V.idx / COLS);
    if (V.top < 0) V.top = 0;
    if (row < V.top) V.top = row;
    if (row >= V.top + ROWS) V.top = row - ROWS + 1;
    var start = V.top * COLS;
    if (V.top !== V.shownTop || V.version !== V.shownVersion) {
      var h = '';
      for (var i = start; i < Math.min(n, start + PAGE); i++) h += card(L[i], false);
      g.innerHTML = h;
      V.shownTop = V.top;
      V.shownVersion = V.version;
    }
    var cards = g.children;
    for (var j = 0; j < cards.length; j++) cards[j].classList.toggle('focus', V.zone === 'grid' && start + j === V.idx);
    var ch = L[V.idx];
    $('summary').innerHTML = '<b>' + fmt(n) + '</b> channels' + (V.zone === 'grid' && ch ?
      ' <i>·</i> ' + (V.idx + 1) + ' of ' + fmt(n) + ' <i>·</i> <b>' + esc(ch.n) + '</b> · ' + esc(flag(ch.c) + ' ' + ch._cn) +
      (ch.l.length ? ' · ' + esc(ch.l.slice(0, 3).map(function (l) { return IX.lang[l] ? IX.lang[l].name : l; }).join(', ')) : '') : '');
  }

  function redrawCard(i) {
    var start = V.top * COLS, el = $('grid').children[i - start];
    if (!el) return;
    var tmp = document.createElement('div');
    tmp.innerHTML = card(V.list[i], V.zone === 'grid' && i === V.idx);
    $('grid').replaceChild(tmp.firstChild, el);
  }

  function render() { renderChips(); renderGrid(); }

  // ---------- Picker (region, country, language, genre, sort)

  function openPicker(type) {
    var opts = [], base = D.channels.filter(function (ch) { return match(ch, type); }), counts = {};
    var titles = { region: 'Region', country: 'Country', lang: 'Language', genre: 'Genre', sort: 'Sort channels by' };
    if (type === 'sort') {
      opts = SORTS.map(function (s) { return { v: s.v, name: s.name }; });
    } else {
      if (type === 'region') {
        D.regions.forEach(function (r) {
          var n = 0;
          base.forEach(function (ch) { if (r.set[ch.c]) n++; });
          if (n) opts.push({ v: r.code, name: r.name, n: n });
        });
        opts.sort(function (a, b) { return a.name < b.name ? -1 : 1; });
      } else {
        base.forEach(function (ch) {
          if (type === 'country') counts[ch.c] = (counts[ch.c] || 0) + 1;
          else (type === 'lang' ? ch.l : ch.g).forEach(function (k) { counts[k] = (counts[k] || 0) + 1; });
        });
        for (var k in counts) {
          var o = type === 'country' ? IX.country[k] : type === 'lang' ? IX.lang[k] : IX.genre[k];
          if (o) opts.push({ v: k, name: type === 'country' ? o.flag + '  ' + o.name : o.name, sortName: o.name, n: counts[k] });
        }
        // Countries A–Z; languages and genres with the most channels first.
        if (type === 'country') opts.sort(function (a, b) { return a.sortName < b.sortName ? -1 : 1; });
        else opts.sort(function (a, b) { return b.n - a.n; });
      }
      opts.unshift({ v: '', name: 'All', n: base.length });
    }
    V.picker = { type: type, opts: opts, title: titles[type] };
    V.pIdx = 0;
    var cur = type === 'sort' ? S.sort : S[type];
    for (var i = 0; i < opts.length; i++) if (opts[i].v === cur) { V.pIdx = i; break; }
    V.pTop = Math.max(0, V.pIdx - 3);
    $('picker').classList.remove('hidden');
    renderPicker();
  }

  function renderPicker() {
    var P = V.picker, n = P.opts.length;
    if (V.pIdx < V.pTop) V.pTop = V.pIdx;
    if (V.pIdx >= V.pTop + PICK_ROWS) V.pTop = V.pIdx - PICK_ROWS + 1;
    var cur = P.type === 'sort' ? S.sort : S[P.type], h = '';
    for (var i = V.pTop; i < Math.min(n, V.pTop + PICK_ROWS); i++) {
      var o = P.opts[i];
      h += '<div class="opt' + (i === V.pIdx ? ' focus' : '') + (o.v === cur ? ' sel' : '') + '"><span>' + esc(o.name) + '</span>' +
        (o.n != null ? '<span class="n">' + fmt(o.n) + '</span>' : '') + '</div>';
    }
    $('picker').querySelector('.ptitle').textContent = P.title + (n > PICK_ROWS ? '  (' + (V.pIdx + 1) + '/' + n + ')' : '');
    $('picker').querySelector('.plist').innerHTML = h;
  }

  function closePicker() { V.picker = null; $('picker').classList.add('hidden'); }

  function pick() {
    var P = V.picker, o = P.opts[V.pIdx];
    closePicker();
    if (P.type === 'sort') S.sort = o.v;
    else S[P.type] = o.v;
    // A country outside the chosen region would leave nothing to show.
    if (P.type === 'region' && S.region && S.country && !IX.region[S.region].set[S.country]) S.country = '';
    save();
    refresh(null);
  }

  // ---------- Search

  function openSearch() {
    V.search = true;
    $('search').classList.remove('hidden');
    var q = $('q');
    q.value = S.q;
    q.focus();
    if (A) A.keyboard();
  }
  function closeSearch(apply) {
    if (!V.search) return;
    var q = $('q');
    if (apply) { S.q = q.value.trim(); save(); }
    V.search = false;
    q.blur();
    $('search').classList.add('hidden');
    if (apply) refresh(null); else render();
  }

  // ---------- Actions

  function chipAction(id) {
    switch (id) {
      case 'search': openSearch(); return;
      case 'fav': S.fav = !S.fav; save(); refresh(null); return;
      case 'random':
        if (V.list.length) { V.idx = Math.floor(Math.random() * V.list.length); V.zone = 'grid'; render(); play(V.idx); }
        return;
      case 'clear': S.region = S.country = S.lang = S.genre = S.q = ''; S.fav = false; save(); refresh(null); return;
      default: openPicker(id);
    }
  }

  function play(i) {
    var L = V.list, lo = Math.max(0, i - 300), hi = Math.min(L.length, i + 300), out = [];
    for (var j = lo; j < hi; j++) {
      var ch = L[j];
      out.push({ id: ch.id, n: ch.n, c: (flag(ch.c) + ' ' + ch._cn).trim(), g: genreOf(ch), s: ch.s });
    }
    log('play ' + L[i].id);
    if (A) A.play(JSON.stringify(out), i - lo);
  }

  function toggleFav() {
    var ch = V.list[V.idx];
    if (!ch) return;
    if (favs[ch.id]) delete favs[ch.id]; else favs[ch.id] = 1;
    save();
    if (S.fav) refresh(null); else redrawCard(V.idx);
  }

  function key(k) {
    if (V.search) return;
    if (!D) { if (k === 'ok' && A && $('ltitle').textContent.indexOf('Couldn') === 0) { loadingText('Getting the channel list…', 'Trying again…'); A.sync(); } return; }
    if (V.picker) {
      var n = V.picker.opts.length;
      if (k === 'up') V.pIdx = Math.max(0, V.pIdx - 1);
      else if (k === 'down') V.pIdx = Math.min(n - 1, V.pIdx + 1);
      else if (k === 'left' || k === 'rew') V.pIdx = Math.max(0, V.pIdx - PICK_ROWS);
      else if (k === 'right' || k === 'ff') V.pIdx = Math.min(n - 1, V.pIdx + PICK_ROWS);
      else if (k === 'ok') { pick(); return; }
      renderPicker();
      return;
    }
    var L = V.list, len = L.length;
    if (V.zone === 'chips') {
      if (k === 'left') V.chip = Math.max(0, V.chip - 1);
      else if (k === 'right') V.chip = Math.min(CHIPS.length - 1, V.chip + 1);
      else if (k === 'down' && len) V.zone = 'grid';
      else if (k === 'ok') { chipAction(CHIPS[V.chip].id); return; }
      render();
      return;
    }
    var col = V.idx % COLS;
    if (k === 'left') { if (col > 0) V.idx--; }
    else if (k === 'right') { if (col < COLS - 1 && V.idx + 1 < len) V.idx++; }
    else if (k === 'up') { if (V.idx >= COLS) V.idx -= COLS; else V.zone = 'chips'; }
    else if (k === 'down') {
      if (V.idx + COLS < len) V.idx += COLS;
      else if (Math.floor(V.idx / COLS) < Math.floor((len - 1) / COLS)) V.idx = len - 1;
    }
    else if (k === 'ff') V.idx = Math.min(len - 1, V.idx + PAGE);
    else if (k === 'rew') V.idx = Math.max(0, V.idx - PAGE);
    else if (k === 'ok' || k === 'play') { play(V.idx); return; }
    else if (k === 'menu') { toggleFav(); return; }
    render();
  }

  function loadingText(title, text) {
    $('loading').classList.remove('hidden');
    $('ltitle').textContent = title;
    $('ltext').textContent = text;
  }

  document.addEventListener('keydown', function (e) {
    if (V.search) {
      if (e.keyCode === 13) { e.preventDefault(); closeSearch(true); }
      return;
    }
    var k = { 37: 'left', 38: 'up', 39: 'right', 40: 'down', 13: 'ok' }[e.keyCode];
    if (!k) return;
    e.preventDefault();
    key(k);
  });

  // Fire TV's keyboard covers the screen; its Next button and Back both close it and blur the field. Either way, search.
  $('q').addEventListener('blur', function () { if (V.search) closeSearch(true); });

  // ---------- Called from Java

  window.app = {
    key: key,
    back: function () {
      if (V.search) { closeSearch(true); return true; }
      if (V.picker) { closePicker(); return true; }
      if (!D) return false;
      if (V.zone === 'grid' && V.idx >= PAGE) { V.idx = 0; render(); return true; }
      if (V.zone === 'chips' && V.list.length) { V.zone = 'grid'; render(); return true; }
      return false;
    },
    returned: function (id) {
      for (var i = 0; i < V.list.length; i++) if (V.list[i].id === id) { V.idx = i; V.zone = 'grid'; break; }
      render();
    },
    progress: function (msg) {
      if (D) $('status').textContent = msg;
      else loadingText('Getting the channel list…', msg);
    },
    synced: function (error) {
      if (error) {
        log('sync failed: ' + error);
        if (D) $('status').textContent = 'Couldn’t update the channel list';
        else loadingText('Couldn’t get the channel list', error + ' — check the Wi-Fi and press OK to try again.');
        return;
      }
      if (useCatalog(A.catalog())) $('loading').classList.add('hidden');
    }
  };

  start();
})();
