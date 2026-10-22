/*
 * Channel Atlas: free live channels from iptv-org, browsed like a streaming app with a TV remote.
 * A hero banner for the focused channel, rows of channels (Continue watching, My List, HD picks, one per genre, top
 * countries) and a side menu for search, language, region and sort. While a channel plays and you go Back, Android
 * keeps it playing in a mini window over the hero's art frame (#art).
 */
(function () {
  'use strict';

  var A = window.Android || null;
  var ROW_H = 150, TILE_W = 170, TILE_GAP = 14, STEP = TILE_W + TILE_GAP, PICK_ROWS = 8, PAGE = 6;
  var ART = { x: 560, y: 28, w: 372, h: 209 };   // must match #art in app.css

  var SORTS = [
    { v: 'rec', name: 'Recommended' },
    { v: 'az', name: 'Name A–Z' },
    { v: 'q', name: 'Best quality first' }
  ];
  var ICONS = {
    search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/>',
    home: '<path d="M3 11l9-7 9 7"/><path d="M5 10v10h14V10"/>',
    mylist: '<path d="M12 5v14M5 12h14"/>',
    genre: '<rect x="3" y="4" width="7" height="7" rx="1"/><rect x="14" y="4" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
    lang: '<path d="M4 6h10M9 4v2M6 6c1 4 4 7 7 8M12 6c-1 4-4 7-7 8"/><path d="M13 20l4-9 4 9M14.5 17h5"/>',
    place: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18"/>',
    sort: '<path d="M4 6h16M7 12h10M10 18h4"/>',
    random: '<path d="M3 7h4l10 10h4M3 17h4l3-3M14 10l3-3h4"/><path d="M18 4l3 3-3 3M18 14l3 3-3 3"/>'
  };
  var NAV = [
    { id: 'search', label: 'Search' },
    { id: 'home', label: 'Home' },
    { id: 'mylist', label: 'My List' },
    { id: 'genre', label: 'Genres' },
    { id: 'lang', label: 'Language' },
    { id: 'place', label: 'Region' },
    { id: 'sort', label: 'Sort' },
    { id: 'random', label: 'Random' }
  ];

  var D = null, IX = null, BY_ID = {};
  var S = load('ca.state2', { lang: '', place: '', sort: 'rec', q: '' });
  var favs = load('ca.favs', {});
  var recent = load('ca.recent', { ids: [] }).ids;
  var V = {
    zone: 'rows', nav: 1, row: 0, rows: [], base: [], picker: null, pIdx: 0, pTop: 0, search: false,
    player: { mode: 'none', id: null, name: '' }, heroCh: null
  };

  function $(id) { return document.getElementById(id); }
  function load(k, d) { try { var v = localStorage.getItem(k); if (v) { var o = JSON.parse(v); for (var p in o) d[p] = o[p]; } } catch (e) {} return d; }
  function save() {
    try {
      localStorage.setItem('ca.state2', JSON.stringify(S));
      localStorage.setItem('ca.favs', JSON.stringify(favs));
      localStorage.setItem('ca.recent', JSON.stringify({ ids: recent }));
    } catch (e) {}
  }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function fmt(n) { return n.toLocaleString('en-US'); }
  function log(m) { if (A) A.log(String(m)); }
  function flag(c) { return IX && IX.country[c] ? IX.country[c].flag : ''; }
  function cname(c) { return IX && IX.country[c] ? IX.country[c].name : c; }
  function gname(g) { return IX.genre[g] ? IX.genre[g].name : g === '_other' ? 'More channels' : g; }
  function lname(l) { return IX.lang[l] ? IX.lang[l].name : l; }
  function initials(n) { return n.replace(/[^A-Za-z0-9 ]/g, '').split(' ').filter(Boolean).slice(0, 2).map(function (w) { return w[0]; }).join('').toUpperCase() || '•'; }

  var toastTimer = 0;
  function toast(msg) {
    var t = $('toast');
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.classList.remove('show'); }, 2600);
  }

  // ---------- Catalog

  function useCatalog(text) {
    var d;
    try { d = JSON.parse(text); } catch (e) { return false; }
    if (!d || !d.channels || !d.channels.length) return false;
    D = d;
    IX = { country: {}, lang: {}, genre: {}, region: {} };
    BY_ID = {};
    D.countries.forEach(function (c) { IX.country[c.code] = c; });
    D.languages.forEach(function (l) { IX.lang[l.code] = l; });
    D.genres.forEach(function (g) { IX.genre[g.id] = g; });
    D.regions.forEach(function (r) { r.set = {}; r.countries.forEach(function (c) { r.set[c] = 1; }); IX.region[r.code] = r; });
    D.channels.forEach(function (ch, i) {
      ch._o = i;
      ch._k = (ch.n + ' ' + (ch.a || []).join(' ')).toLowerCase();
      ch._q = 0;
      ch.s.forEach(function (s) { if (s.q > ch._q) ch._q = s.q; });
      ch._score = (ch.geo ? 0 : 4) + (ch.part ? 0 : 2) + (ch.i ? 1 : 0);
      BY_ID[ch.id] = ch;
    });
    if (S.lang && !IX.lang[S.lang]) S.lang = '';
    if (S.place && !(S.place[0] === 'r' ? IX.region[S.place.slice(2)] : IX.country[S.place.slice(2)])) S.place = '';
    build(null);
    return true;
  }

  function start() {
    if (A) A.miniRect(ART.x, ART.y, ART.w, ART.h, window.innerWidth || 960);
    var text = A ? A.catalog() : '';
    if (text && useCatalog(text)) {
      $('loading').classList.add('hidden');
      if (A.age() > 24 * 3600 * 1000) A.sync();
    } else if (A) {
      A.sync();
    } else {
      $('ltitle').textContent = 'Open this inside the app';
      $('ltext').textContent = 'The channel list is downloaded by the Android app.';
    }
  }

  // ---------- Filters, sorting and rows

  function inPlace(ch) {
    if (!S.place) return true;
    var code = S.place.slice(2);
    return S.place[0] === 'r' ? !!IX.region[code].set[ch.c] : ch.c === code;
  }
  function match(ch, skip) {
    if (skip !== 'lang' && S.lang && ch.l.indexOf(S.lang) < 0) return false;
    if (skip !== 'place' && !inPlace(ch)) return false;
    if (S.q && ch._k.indexOf(S.q.toLowerCase()) < 0) return false;
    return true;
  }
  function sorter() {
    if (S.sort === 'az') return function (a, b) { return a._o - b._o; };
    if (S.sort === 'q') return function (a, b) { return b._q - a._q || b._score - a._score || a._o - b._o; };
    return function (a, b) { return b._score - a._score || b._q - a._q || a._o - b._o; };
  }
  function placeName() {
    if (!S.place) return '';
    var code = S.place.slice(2);
    return S.place[0] === 'r' ? IX.region[code].name : flag(code) + ' ' + cname(code);
  }

  /** Rebuilds the rows from the filters, keeping the focus on `keep` ({row, id}) when it's still there. */
  function build(keep) {
    var f = sorter();
    var base = D.channels.filter(function (ch) { return match(ch, null); }).sort(f);
    V.base = base;
    var rows = [];
    if (S.q) {
      // Names that start with the search first, then ones with a word that does, then the rest.
      var q = S.q.toLowerCase();
      var rank = function (ch) { var n = ch.n.toLowerCase(); return n.indexOf(q) === 0 ? 0 : (' ' + n).indexOf(' ' + q) >= 0 ? 1 : 2; };
      var found = base.slice().sort(function (a, b) { return rank(a) - rank(b) || f(a, b); });
      rows.push({ id: 'q', title: 'Results for “' + S.q + '”', list: found });
    }
    var rl = recent.map(function (id) { return BY_ID[id]; }).filter(Boolean);
    if (rl.length) rows.push({ id: 'recent', title: 'Continue watching', list: rl });
    var fl = D.channels.filter(function (ch) { return favs[ch.id]; }).sort(f);
    if (fl.length) rows.push({ id: 'mylist', title: 'My List', list: fl });
    if (!S.q) {
      var hd = base.filter(function (ch) { return ch._q >= 720 && !ch.geo && !ch.part; });
      if (hd.length >= 5) rows.push({ id: 'hd', title: 'Top picks in HD', list: hd });
      var g = {}, order = [];
      base.forEach(function (ch) {
        (ch.g.length ? ch.g : ['_other']).forEach(function (k) { if (!g[k]) { g[k] = []; order.push(k); } g[k].push(ch); });
      });
      order.sort(function (a, b) { return (a === '_other') - (b === '_other') || g[b].length - g[a].length; });
      order.forEach(function (k) { rows.push({ id: 'g:' + k, title: gname(k), list: g[k] }); });
      if (!S.place || S.place[0] === 'r') {
        var c = {}, cs = [];
        base.forEach(function (ch) { if (!c[ch.c]) { c[ch.c] = []; cs.push(ch.c); } c[ch.c].push(ch); });
        cs.sort(function (a, b) { return c[b].length - c[a].length; });
        cs.slice(0, 10).forEach(function (k) { rows.push({ id: 'c:' + k, title: 'From ' + flag(k) + ' ' + cname(k), list: c[k] }); });
      }
    }
    if (!base.length) rows.splice(S.q ? 1 : rows.length, 0, { id: 'none', title: 'Nothing matches', list: [], empty: 'No channels for these settings. Change Language or Region in the menu on the left.' });
    rows.forEach(function (r) { r.idx = 0; });

    // Put the focus back where it was.
    V.row = 0;
    if (keep) {
      for (var i = 0; i < rows.length; i++) {
        if (rows[i].id !== keep.row) continue;
        V.row = i;
        for (var j = 0; j < rows[i].list.length; j++) if (rows[i].list[j].id === keep.id) { rows[i].idx = j; break; }
        break;
      }
    }
    V.rows = rows;
    $('rows-inner').innerHTML = '';
    var parts = [];
    if (S.lang) parts.push(lname(S.lang));
    if (S.place) parts.push(placeName());
    $('scope').textContent = (parts.length ? parts.join(' · ') + ' · ' : '') + fmt(base.length) + ' channels';
    render();
  }

  function focusKey() {
    var r = V.rows[V.row];
    return r ? { row: r.id, id: r.list[r.idx] && r.list[r.idx].id } : null;
  }

  // ---------- Rendering

  function tileHtml(ch) {
    return '<div class="art"><span class="ini">' + esc(initials(ch.n)) + '</span>' +
      (ch.i ? '<img src="' + esc(ch.i) + '" onload="this.parentNode.classList.add(\'ok\')" onerror="this.remove()">' : '') + '</div>' +
      '<span class="fl">' + flag(ch.c) + '</span>' + (ch._q >= 720 ? '<span class="hd">HD</span>' : '') +
      '<div class="cap">' + (favs[ch.id] ? '<span class="st">★</span>' : '') + esc(ch.n) + '</div>';
  }

  function rowEl(r, ri) {
    var el = document.createElement('div');
    el.className = 'row';
    el.style.top = (ri * ROW_H) + 'px';
    el.innerHTML = '<div class="rt">' + esc(r.title) + (r.list.length ? '<span class="rc">' + fmt(r.list.length) + '</span>' : '') +
      '</div><div class="strip"></div>';
    r.el = el;
    r.strip = el.lastChild;
    r.tiles = {};
    if (!r.list.length) r.strip.innerHTML = '<div class="empty-row">' + esc(r.empty || '') + '</div>';
    return el;
  }

  /** Keeps only the tiles near the focus in the page, and slides the strip so the focus sits second from the left. */
  function renderStrip(r, ri) {
    var n = r.list.length;
    if (!n) return;
    var off = Math.max(0, Math.min(r.idx - 1, n - 4));
    var lo = Math.max(0, off - 2), hi = Math.min(n, off + 8);
    for (var k in r.tiles) {
      if (k < lo || k >= hi) { r.strip.removeChild(r.tiles[k]); delete r.tiles[k]; }
    }
    for (var i = lo; i < hi; i++) {
      var t = r.tiles[i];
      if (!t) {
        t = document.createElement('div');
        t.className = 'tile';
        t.style.left = (i * STEP) + 'px';
        t.innerHTML = tileHtml(r.list[i]);
        r.strip.appendChild(t);
        r.tiles[i] = t;
      }
      t.classList.toggle('focus', V.zone === 'rows' && ri === V.row && i === r.idx);
      t.classList.toggle('past', i < off);
      t.classList.toggle('now', V.player.mode !== 'none' && r.list[i].id === V.player.id);
    }
    r.strip.style.transform = 'translateX(' + (-off * STEP) + 'px)';
  }

  function renderRows() {
    var inner = $('rows-inner');
    for (var i = 0; i < V.rows.length; i++) {
      var r = V.rows[i], show = i >= V.row - 1 && i <= V.row + 2;
      if (show && !r.el) inner.appendChild(rowEl(r, i));
      else if (!show && r.el) { inner.removeChild(r.el); r.el = null; }
      if (r.el) renderStrip(r, i);
    }
    inner.style.transform = 'translateY(' + (-V.row * ROW_H + 6) + 'px)';
  }

  function renderNav() {
    var nav = $('nav'), h = '';
    nav.classList.toggle('open', V.zone === 'nav');
    NAV.forEach(function (n, i) {
      var val = n.id === 'lang' ? (S.lang ? lname(S.lang) : 'All') : n.id === 'place' ? (S.place ? placeName() : 'All') :
        n.id === 'sort' ? SORTS.filter(function (s) { return s.v === S.sort; })[0].name : n.id === 'search' && S.q ? '“' + S.q + '”' : '';
      var set = (n.id === 'lang' && S.lang) || (n.id === 'place' && S.place) || (n.id === 'search' && S.q);
      h += '<div class="ni' + (V.zone === 'nav' && V.nav === i ? ' focus' : '') + (set ? ' set' : '') + (n.id === 'home' ? ' cur' : '') + '">' +
        '<svg viewBox="0 0 24 24">' + ICONS[n.id] + '</svg><span class="lb">' + n.label + '</span>' +
        (val ? '<span class="vl">' + esc(val) + '</span>' : '') + '</div>';
    });
    $('nav-items').innerHTML = h;
  }

  var heroTimer = 0;
  function renderHero() {
    var mini = V.player.mode === 'mini';
    document.body.classList.toggle('hero-focus', V.zone === 'hero');
    $('live').innerHTML = mini ? '<span class="dot"></span><b>LIVE</b>&nbsp; ' + esc(V.player.name) + ' <i>·</i> <b>▲ OK</b> full screen' : '';
    var r = V.rows[V.row];
    var ch = V.zone === 'hero' && mini ? BY_ID[V.player.id] : r && r.list[r.idx];
    var hk = (ch ? ch.id : '') + '|' + V.zone + '|' + (r ? r.id : '');
    if (hk === V.heroCh) return;
    V.heroCh = hk;
    clearTimeout(heroTimer);
    heroTimer = setTimeout(function () { fillHero(ch, r); }, 60);
  }

  function fillHero(ch, r) {
    if (!ch) {
      $('kicker').textContent = r ? r.title : '';
      $('title').textContent = r && !r.list.length ? (r.id === 'mylist' ? 'Your list is empty' : 'Nothing here') : '';
      $('meta').innerHTML = '';
      $('hint').innerHTML = r && r.empty ? esc(r.empty) : '';
      $('art-img').removeAttribute('src');
      $('art').classList.remove('ok');
      $('art-ini').textContent = '';
      return;
    }
    $('kicker').textContent = V.zone === 'hero' ? 'Now playing' : r ? r.title : '';
    $('title').textContent = ch.n;
    var m = [];
    m.push('<span class="m">' + flag(ch.c) + ' ' + esc(cname(ch.c)) + '</span>');
    if (ch.l.length) m.push('<span class="m">' + esc(ch.l.slice(0, 2).map(lname).join(', ')) + '</span>');
    if (ch.g.length) m.push('<span class="m">' + esc(ch.g.slice(0, 2).map(gname).join(', ')) + '</span>');
    var meta = m.join('<span class="dot">•</span>');
    if (ch._q >= 1080) meta += ' <span class="badge">FULL HD</span>';
    else if (ch._q >= 720) meta += ' <span class="badge">HD</span>';
    if (ch.geo) meta += ' <span class="m warn">May be geo-blocked</span>';
    else if (ch.part) meta += ' <span class="m warn">Not on 24/7</span>';
    $('meta').innerHTML = meta;
    $('hint').innerHTML = V.zone === 'hero' ? '<span class="key">OK</span> Full screen' :
      '<span class="key">▶ OK</span>Watch <i>·</i> <b>☰</b> ' + (favs[ch.id] ? 'Remove from My List' : 'Add to My List');
    var img = $('art-img');
    $('art').classList.remove('ok');
    $('art-ini').textContent = initials(ch.n);
    if (ch.i) { img.onload = function () { $('art').classList.add('ok'); }; img.src = ch.i; }
    else img.removeAttribute('src');
  }

  function render() {
    renderNav();
    renderRows();
    renderHero();
  }

  // ---------- Picker (language, region, sort, genre)

  function openPicker(type) {
    var opts = [], titles = { lang: 'Language', place: 'Region or country', sort: 'Sort channels by', genre: 'Jump to a genre' };
    var cur = '';
    if (type === 'sort') {
      opts = SORTS.map(function (s) { return { v: s.v, name: s.name }; });
      cur = S.sort;
    } else if (type === 'genre') {
      V.rows.forEach(function (r) { if (r.id.indexOf('g:') === 0) opts.push({ v: r.id, name: r.title, n: r.list.length }); });
      if (!opts.length) { toast('Clear the search to see genres'); return; }
    } else {
      var base = D.channels.filter(function (ch) { return match(ch, type); });
      opts.push({ v: '', name: type === 'lang' ? 'All languages' : 'Everywhere', n: base.length });
      if (type === 'lang') {
        var counts = {}, list = [];
        base.forEach(function (ch) { ch.l.forEach(function (k) { counts[k] = (counts[k] || 0) + 1; }); });
        for (var k in counts) if (IX.lang[k]) list.push({ v: k, name: IX.lang[k].name, n: counts[k] });
        list.sort(function (a, b) { return b.n - a.n; });
        opts = opts.concat(list);
        cur = S.lang;
      } else {
        var cc = {};
        base.forEach(function (ch) { cc[ch.c] = (cc[ch.c] || 0) + 1; });
        var regs = [];
        D.regions.forEach(function (r) {
          var n = 0;
          for (var c in cc) if (r.set[c]) n += cc[c];
          if (n) regs.push({ v: 'r:' + r.code, name: '🌐  ' + r.name, n: n, s: r.name });
        });
        regs.sort(function (a, b) { return a.s < b.s ? -1 : 1; });
        var cs = [];
        for (var c2 in cc) if (IX.country[c2]) cs.push({ v: 'c:' + c2, name: flag(c2) + '  ' + cname(c2), n: cc[c2], s: cname(c2) });
        cs.sort(function (a, b) { return a.s < b.s ? -1 : 1; });
        opts = opts.concat(cs, regs);
        cur = S.place;
      }
    }
    V.picker = { type: type, opts: opts, title: titles[type], cur: cur };
    V.pIdx = 0;
    for (var i = 0; i < opts.length; i++) if (opts[i].v === cur) { V.pIdx = i; break; }
    V.pTop = Math.max(0, V.pIdx - 3);
    $('picker').classList.remove('hidden');
    renderPicker();
  }

  function renderPicker() {
    var P = V.picker, n = P.opts.length, h = '';
    if (V.pIdx < V.pTop) V.pTop = V.pIdx;
    if (V.pIdx >= V.pTop + PICK_ROWS) V.pTop = V.pIdx - PICK_ROWS + 1;
    for (var i = V.pTop; i < Math.min(n, V.pTop + PICK_ROWS); i++) {
      var o = P.opts[i];
      h += '<div class="opt' + (i === V.pIdx ? ' focus' : '') + (P.type !== 'genre' && o.v === P.cur ? ' sel' : '') + '"><span>' + esc(o.name) + '</span>' +
        (o.n != null ? '<span class="n">' + fmt(o.n) + '</span>' : '') + '</div>';
    }
    $('picker').querySelector('.ptitle').textContent = P.title + (n > PICK_ROWS ? '  (' + (V.pIdx + 1) + '/' + n + ')' : '');
    $('picker').querySelector('.plist').innerHTML = h;
  }

  function closePicker() { V.picker = null; $('picker').classList.add('hidden'); }

  function pick() {
    var P = V.picker, o = P.opts[V.pIdx];
    closePicker();
    V.zone = 'rows';
    if (P.type === 'genre') {
      for (var i = 0; i < V.rows.length; i++) if (V.rows[i].id === o.v) { V.row = i; break; }
      render();
      return;
    }
    if (P.type === 'sort') S.sort = o.v; else S[P.type] = o.v;
    save();
    build(null);
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
  function closeSearch() {
    if (!V.search) return;
    var q = $('q');
    S.q = q.value.trim();
    save();
    V.search = false;
    q.blur();
    $('search').classList.add('hidden');
    V.zone = 'rows';
    build(null);
  }
  // Fire TV's keyboard covers the screen; its Next button and Back both close it and blur the field. Either way, search.
  $('q').addEventListener('blur', function () { if (V.search) closeSearch(); });

  // ---------- Actions

  function play(r, i) {
    var L = r.list, lo = Math.max(0, i - 300), hi = Math.min(L.length, i + 300), out = [];
    for (var j = lo; j < hi; j++) {
      var ch = L[j];
      out.push({ id: ch.id, n: ch.n, c: (flag(ch.c) + ' ' + cname(ch.c)).trim(), g: ch.g.length ? gname(ch.g[0]) : '', s: ch.s });
    }
    log('play ' + L[i].id + ' from ' + r.id);
    if (A) A.play(JSON.stringify(out), i - lo);
  }

  function toggleFav() {
    var r = V.rows[V.row], ch = r && r.list[r.idx];
    if (!ch) return;
    var on = !favs[ch.id];
    if (on) favs[ch.id] = 1; else delete favs[ch.id];
    save();
    toast(on ? '★ Added ' + ch.n + ' to My List' : 'Removed ' + ch.n + ' from My List');
    V.heroCh = null;
    build(focusKey());
  }

  function navAction(id) {
    switch (id) {
      case 'search': openSearch(); return;
      case 'home':
        V.zone = 'rows';
        if (S.q) { S.q = ''; save(); build(null); } else { V.row = 0; V.rows.forEach(function (r) { r.idx = 0; }); render(); }
        return;
      case 'mylist':
        V.zone = 'rows';
        var found = false;
        for (var i = 0; i < V.rows.length; i++) if (V.rows[i].id === 'mylist') { V.row = i; found = true; }
        if (!found) toast('My List is empty: press ☰ on any channel to add it');
        render();
        return;
      case 'random':
        if (!V.base.length) { toast('No channels for these settings'); return; }
        var pool = V.base.filter(function (ch) { return !ch.geo && !ch.part; });
        if (!pool.length) pool = V.base;
        var ch = pool[Math.floor(Math.random() * pool.length)];
        V.zone = 'rows';
        render();
        play({ id: 'random', list: pool }, pool.indexOf(ch));
        return;
      default: openPicker(id);
    }
  }

  function key(k) {
    if (V.search) return;
    if (!D) {
      if (k === 'ok' && A && $('ltitle').textContent.indexOf('Couldn') === 0) { loadingText('Getting the channel list…', 'Trying again…'); A.sync(); }
      return;
    }
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
    var mini = V.player.mode === 'mini';
    if (V.zone === 'nav') {
      if (k === 'up') V.nav = Math.max(0, V.nav - 1);
      else if (k === 'down') V.nav = Math.min(NAV.length - 1, V.nav + 1);
      else if (k === 'right') V.zone = 'rows';
      else if (k === 'ok') { navAction(NAV[V.nav].id); return; }
      render();
      return;
    }
    if (V.zone === 'hero') {
      if (k === 'down') V.zone = 'rows';
      else if (k === 'left') V.zone = 'nav';
      else if ((k === 'ok' || k === 'play') && A) { A.expand(); return; }
      render();
      return;
    }
    var r = V.rows[V.row], len = r ? r.list.length : 0;
    if (k === 'left') { if (r && r.idx > 0) r.idx--; else V.zone = 'nav'; }
    else if (k === 'right') { if (r && r.idx < len - 1) r.idx++; }
    else if (k === 'up') { if (V.row > 0) V.row--; else if (mini) V.zone = 'hero'; }
    else if (k === 'down') { if (V.row < V.rows.length - 1) V.row++; }
    else if (k === 'ff') { if (r) r.idx = Math.min(len - 1, r.idx + PAGE); }
    else if (k === 'rew') { if (r) r.idx = Math.max(0, r.idx - PAGE); }
    else if (k === 'ok') { if (len) play(r, r.idx); return; }
    else if (k === 'play') { if (mini && A) A.expand(); else if (len) play(r, r.idx); return; }
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
      if (e.keyCode === 13) { e.preventDefault(); closeSearch(); }
      return;
    }
    var k = { 37: 'left', 38: 'up', 39: 'right', 40: 'down', 13: 'ok' }[e.keyCode];
    if (!k) return;
    e.preventDefault();
    key(k);
  });

  // ---------- Called from Java

  window.app = {
    key: key,
    back: function () {
      if (V.search) { closeSearch(); return true; }
      if (V.picker) { closePicker(); return true; }
      if (!D) return false;
      if (V.zone === 'nav' || V.zone === 'hero') { V.zone = 'rows'; render(); return true; }
      var r = V.rows[V.row];
      if (V.row > 0 || (r && r.idx > 0)) { V.row = 0; if (r) r.idx = 0; render(); return true; }
      if (V.player.mode === 'mini' && A) { A.stop(); return true; }
      return false;
    },
    /** The video panel changed: mode "full", "mini" or "none", and the channel on it. */
    player: function (p) {
      var was = V.player.mode;
      V.player = { mode: p.mode, id: p.id || null, name: p.name || '' };
      if (p.id) {
        recent = [p.id].concat(recent.filter(function (x) { return x !== p.id; })).slice(0, 20);
        save();
      }
      if (p.mode === 'none' && V.zone === 'hero') V.zone = 'rows';
      // Back from full screen: refresh Continue watching and put the focus on what was playing.
      if (was === 'full' && p.mode !== 'full' && D) {
        var keep = focusKey();
        build(keep);
        if (p.id) {
          var r = V.rows[V.row];
          for (var i = 0; r && i < r.list.length; i++) if (r.list[i].id === p.id) { r.idx = i; break; }
        }
      }
      V.heroCh = null;
      render();
    },
    progress: function (msg) {
      if (D) $('scope').textContent = msg;
      else loadingText('Getting the channel list…', msg);
    },
    synced: function (error) {
      if (error) {
        log('sync failed: ' + error);
        if (D) toast('Couldn’t update the channel list');
        else loadingText('Couldn’t get the channel list', error + ' — check the Wi-Fi and press OK to try again.');
        return;
      }
      if (useCatalog(A.catalog())) $('loading').classList.add('hidden');
    }
  };

  start();
})();
