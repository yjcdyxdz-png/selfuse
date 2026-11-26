// MediaHub 内置：纯 JS 实现的迷你 Jsoup。
// 书源 JS 引擎里没有 Java，这里模拟书源常用的那部分 Jsoup 接口：
// select / first / size / get / text / html / attr / absUrl / parent / remove / title / body
var MJ = (function () {
  var VOID = {area:1,base:1,br:1,col:1,embed:1,hr:1,img:1,input:1,link:1,meta:1,param:1,source:1,track:1,wbr:1};
  var RAW = {script:1,style:1,textarea:1,title:1};
  var BLOCK = {p:1,div:1,br:1,li:1,ul:1,ol:1,tr:1,td:1,th:1,table:1,h1:1,h2:1,h3:1,h4:1,h5:1,h6:1,pre:1,center:1,blockquote:1,hr:1,dd:1,dt:1,dl:1,section:1,article:1,header:1,footer:1,form:1,tbody:1,thead:1};
  // 开始某标签时自动闭合当前同类标签
  var AUTOCLOSE = {li:{li:1},p:{p:1},tr:{tr:1,td:1,th:1},td:{td:1,th:1},th:{td:1,th:1},option:{option:1},dt:{dt:1,dd:1},dd:{dt:1,dd:1}};
  var ENT = {nbsp:' ',amp:'&',lt:'<',gt:'>',quot:'"',apos:"'",copy:'©',reg:'®',hellip:'…',mdash:'—',ndash:'–',ldquo:'“',rdquo:'”',lsquo:'‘',rsquo:'’',middot:'·',times:'×'};

  function decode(s) {
    return String(s).replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, function (m, e) {
      if (e.charAt(0) === '#') {
        var c = e.charAt(1).toLowerCase() === 'x' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
        return isNaN(c) ? m : String.fromCharCode(c);
      }
      var v = ENT[e.toLowerCase()];
      return v === undefined ? m : v;
    });
  }

  function El(tag, attrs, parent) { this.tag = tag; this.attrs = attrs || {}; this.children = []; this.parentNode = parent || null; }
  function Txt(t, parent) { this.text0 = t; this.parentNode = parent; }

  function parseAttrs(s) {
    var a = {}, re = /([^\s=\/>"']+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g, m;
    while ((m = re.exec(s))) {
      var v = m[2] != null ? m[2] : m[3] != null ? m[3] : m[4] != null ? m[4] : '';
      a[m[1].toLowerCase()] = decode(v);
    }
    return a;
  }

  function parse(html) {
    html = String(html == null ? '' : html);
    var root = new El('#root', {}, null), stack = [root], i = 0, n = html.length;
    function cur() { return stack[stack.length - 1]; }
    while (i < n) {
      var lt = html.indexOf('<', i);
      if (lt < 0) lt = n;
      if (lt > i) cur().children.push(new Txt(html.slice(i, lt), cur()));
      if (lt >= n) break;
      if (html.substr(lt, 4) === '<!--') {
        var ce = html.indexOf('-->', lt + 4); i = ce < 0 ? n : ce + 3; continue;
      }
      if (html.charAt(lt + 1) === '!' || html.charAt(lt + 1) === '?') {
        var de = html.indexOf('>', lt); i = de < 0 ? n : de + 1; continue;
      }
      var m = /^<(\/?)([a-zA-Z][a-zA-Z0-9:-]*)([^>]*)>/.exec(html.slice(lt, lt + 4096));
      if (!m) { cur().children.push(new Txt('<', cur())); i = lt + 1; continue; }
      i = lt + m[0].length;
      var tag = m[2].toLowerCase();
      if (m[1]) {                       // 结束标签：闭合到最近的同名标签
        for (var k = stack.length - 1; k > 0; k--) if (stack[k].tag === tag) { stack.length = k; break; }
        continue;
      }
      var ac = AUTOCLOSE[tag];
      if (ac) {
        for (var j = stack.length - 1; j > 0; j--) {
          var t = stack[j].tag;
          if (ac[t]) { stack.length = j; break; }
          if (t === 'ul' || t === 'ol' || t === 'table' || t === 'div') break;
        }
      }
      var el = new El(tag, parseAttrs(m[3]), cur());
      cur().children.push(el);
      if (RAW[tag]) {                   // script/style 等：内容原样保存
        var endRe = new RegExp('</' + tag + '\\s*>', 'i'), rest = html.slice(i), em = endRe.exec(rest);
        var inner = em ? rest.slice(0, em.index) : rest;
        if (inner) el.children.push(new Txt(inner, el));
        i += em ? em.index + em[0].length : rest.length;
        continue;
      }
      if (!VOID[tag] && !/\/\s*$/.test(m[3])) stack.push(el);
    }
    return root;
  }

  // ---------- 选择器 ----------
  function parseCompound(s) {
    var c = { tag: null, id: null, cls: [], attrs: [] }, m, re = /([a-zA-Z*][\w-]*)|#([\w-]+)|\.([\w-]+)|\[\s*([\w-]+)\s*(?:([*^$~|]?=)\s*(?:"([^"]*)"|'([^']*)'|([^\]\s]*)))?\s*\]|:[\w-]+(?:\([^)]*\))?/g;
    while ((m = re.exec(s))) {
      if (m[1]) c.tag = m[1] === '*' ? null : m[1].toLowerCase();
      else if (m[2]) c.id = m[2];
      else if (m[3]) c.cls.push(m[3]);
      else if (m[4]) c.attrs.push({ k: m[4].toLowerCase(), op: m[5] || '', v: m[6] != null ? m[6] : m[7] != null ? m[7] : (m[8] || '') });
    }
    return c;
  }
  function matchOne(el, c) {
    if (!(el instanceof El) || el.tag === '#root') return false;
    if (c.tag && el.tag !== c.tag) return false;
    if (c.id && el.attrs.id !== c.id) return false;
    if (c.cls.length) {
      var cl = ' ' + String(el.attrs['class'] || '').replace(/\s+/g, ' ') + ' ';
      for (var i = 0; i < c.cls.length; i++) if (cl.indexOf(' ' + c.cls[i] + ' ') < 0) return false;
    }
    for (var j = 0; j < c.attrs.length; j++) {
      var a = c.attrs[j], v = el.attrs[a.k];
      if (v === undefined) return false;
      v = String(v);
      if (a.op === '=' && v !== a.v) return false;
      if (a.op === '*=' && v.indexOf(a.v) < 0) return false;
      if (a.op === '^=' && v.indexOf(a.v) !== 0) return false;
      if (a.op === '$=' && v.slice(-a.v.length) !== a.v) return false;
      if (a.op === '~=' && (' ' + v + ' ').indexOf(' ' + a.v + ' ') < 0) return false;
    }
    return true;
  }
  function parseSel(sel) {
    // 返回 [{c:compound, comb:' '|'>'}]，从左到右
    var parts = [], tokens = String(sel).trim().replace(/\s*>\s*/g, ' > ').split(/\s+/), comb = ' ';
    for (var i = 0; i < tokens.length; i++) {
      if (tokens[i] === '>') { comb = '>'; continue; }
      if (tokens[i]) { parts.push({ c: parseCompound(tokens[i]), comb: comb }); comb = ' '; }
    }
    return parts;
  }
  function matchChain(el, parts, idx, scope) {
    if (!matchOne(el, parts[idx].c)) return false;
    if (idx === 0) return true;
    var comb = parts[idx].comb, p = el.parentNode;
    if (comb === '>') return p && p !== scope.parentNode ? matchChain(p, parts, idx - 1, scope) : false;
    while (p && p !== scope.parentNode) { if (matchChain(p, parts, idx - 1, scope)) return true; p = p.parentNode; }
    return false;
  }
  function selectFrom(scope, sel) {
    var groups = String(sel).split(','), out = [], seen = [];
    var chains = [];
    for (var g = 0; g < groups.length; g++) { var p = parseSel(groups[g]); if (p.length) chains.push(p); }
    (function walk(node) {
      for (var i = 0; i < node.children.length; i++) {
        var ch = node.children[i];
        if (!(ch instanceof El)) continue;
        for (var k = 0; k < chains.length; k++) {
          if (matchChain(ch, chains[k], chains[k].length - 1, scope)) { out.push(ch); break; }
        }
        walk(ch);
      }
    })(scope);
    return new Elements(out);
  }

  // ---------- 文本 / HTML ----------
  function rawText(node, buf) {
    for (var i = 0; i < node.children.length; i++) {
      var ch = node.children[i];
      if (ch instanceof Txt) buf.push(decode(ch.text0));
      else if (ch.tag !== 'script' && ch.tag !== 'style') {
        if (BLOCK[ch.tag]) buf.push(' ');
        rawText(ch, buf);
        if (BLOCK[ch.tag]) buf.push(' ');
      }
    }
    return buf;
  }
  function outer(el) {
    if (el instanceof Txt) return el.text0;
    var a = '';
    for (var k in el.attrs) if (Object.prototype.hasOwnProperty.call(el.attrs, k))
      a += ' ' + k + '="' + String(el.attrs[k]).replace(/"/g, '&quot;') + '"';
    if (VOID[el.tag]) return '<' + el.tag + a + '>';
    return '<' + el.tag + a + '>' + inner(el) + '</' + el.tag + '>';
  }
  function inner(el) { var s = ''; for (var i = 0; i < el.children.length; i++) s += outer(el.children[i]); return s; }

  function resolve(base, href) {
    href = String(href || '').trim();
    if (!href) return '';
    if (/^[a-z][a-z0-9+.-]*:/i.test(href)) return /^(javascript|mailto|tel):/i.test(href) ? '' : href;
    var bm = /^([a-z][a-z0-9+.-]*:)\/\/([^\/?#]*)([^?#]*)(\?[^#]*)?/i.exec(String(base || ''));
    if (!bm) return '';
    if (href.indexOf('//') === 0) return bm[1] + href;
    var origin = bm[1] + '//' + bm[2];
    if (href.charAt(0) === '/') return origin + href;
    if (href.charAt(0) === '?') return origin + (bm[3] || '/') + href;
    if (href.charAt(0) === '#') return String(base).replace(/#.*$/, '') + href;
    var dir = (bm[3] || '/').replace(/[^\/]*$/, ''), segs = (dir + href).split('/'), outS = [];
    for (var i = 0; i < segs.length; i++) {
      if (segs[i] === '..') { if (outS.length > 1) outS.pop(); }
      else if (segs[i] !== '.') outS.push(segs[i]);
    }
    return origin + outS.join('/');
  }

  // ---------- Jsoup 风格的对象 ----------
  function Element(node, doc) { this._n = node; this._d = doc; }
  Element.prototype.select = function (s) { return wrap(selectFrom(this._n, s)._a, this._d); };
  Element.prototype.text = function () { return rawText(this._n, []).join('').replace(/\s+/g, ' ').trim(); };
  Element.prototype.ownText = function () {
    var s = ''; for (var i = 0; i < this._n.children.length; i++) if (this._n.children[i] instanceof Txt) s += decode(this._n.children[i].text0);
    return s.replace(/\s+/g, ' ').trim();
  };
  Element.prototype.html = function () { return inner(this._n); };
  Element.prototype.outerHtml = function () { return outer(this._n); };
  Element.prototype.attr = function (k) { var v = this._n.attrs[String(k).toLowerCase()]; return v == null ? '' : String(v); };
  Element.prototype.hasAttr = function (k) { return this._n.attrs[String(k).toLowerCase()] != null; };
  Element.prototype.absUrl = function (k) { return resolve(this._d.baseUri, this.attr(k)); };
  Element.prototype.tagName = function () { return this._n.tag; };
  Element.prototype.parent = function () { var p = this._n.parentNode; return p && p.tag !== '#root' ? new Element(p, this._d) : null; };
  Element.prototype.children = function () {
    var a = []; for (var i = 0; i < this._n.children.length; i++) if (this._n.children[i] instanceof El) a.push(this._n.children[i]);
    return wrap(a, this._d);
  };
  Element.prototype.remove = function () {
    var p = this._n.parentNode; if (!p) return this;
    var i = p.children.indexOf(this._n); if (i >= 0) p.children.splice(i, 1);
    this._n.parentNode = null; return this;
  };
  Object.defineProperty(Element.prototype, 'toString', { value: function () { return this.outerHtml(); }, writable: true, configurable: true });

  function Elements(a) { this._a = a; }
  function wrap(a, doc) {
    var e = new Elements(a); e._d = doc; e.length = a.length; return e;
  }
  Elements.prototype.size = function () { return this._a.length; };
  Elements.prototype.isEmpty = function () { return this._a.length === 0; };
  Elements.prototype.get = function (i) { var n = this._a[i]; return n ? new Element(n, this._d) : null; };
  Elements.prototype.eq = Elements.prototype.get;
  Elements.prototype.first = function () { return this.get(0); };
  Elements.prototype.last = function () { return this.get(this._a.length - 1); };
  Elements.prototype.text = function () { var s = []; for (var i = 0; i < this._a.length; i++) s.push(this.get(i).text()); return s.join(' '); };
  Elements.prototype.html = function () { var s = []; for (var i = 0; i < this._a.length; i++) s.push(this.get(i).html()); return s.join('\n'); };
  Elements.prototype.attr = function (k) { for (var i = 0; i < this._a.length; i++) { var e = this.get(i); if (e.hasAttr(k)) return e.attr(k); } return ''; };
  Elements.prototype.remove = function () { for (var i = 0; i < this._a.length; i++) this.get(i).remove(); return this; };
  Elements.prototype.select = function (s) {
    var out = [], seen = [];
    for (var i = 0; i < this._a.length; i++) {
      var r = selectFrom(this._a[i], s)._a;
      for (var j = 0; j < r.length; j++) if (seen.indexOf(r[j]) < 0) { seen.push(r[j]); out.push(r[j]); }
    }
    return wrap(out, this._d);
  };
  Elements.prototype.toArray = function () { var a = []; for (var i = 0; i < this._a.length; i++) a.push(this.get(i)); return a; };

  function Document(html, baseUri) {
    this.baseUri = String(baseUri || '');
    this._n = parse(html);
    this._d = this;
  }
  Document.prototype = Object.create(Element.prototype);
  Document.prototype.title = function () { var t = this.select('title').first(); return t ? t.text() : ''; };
  Document.prototype.body = function () { var b = this.select('body').first(); return b || new Element(this._n, this); };
  Document.prototype.parent = function () { return null; };

  return { parse: function (html, url) { return new Document(html, url); } };
})();

// 兼容书源里常见的 Java 写法（已经有同名对象时不覆盖）
(function (g) {
  var parse = function (html, url) { return MJ.parse(String(html == null ? '' : html), String(url || '')); };
  if (typeof g.org === 'undefined') g.org = {};
  if (!g.org.jsoup) g.org.jsoup = { Jsoup: { parse: parse } };
  if (typeof g.Packages === 'undefined') g.Packages = {};
  if (!g.Packages.org) g.Packages.org = g.org;
  if (!g.Packages.java) {
    g.Packages.java = {
      lang: { String: { valueOf: function (s) { return String(s == null ? '' : s); } } }
    };
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
