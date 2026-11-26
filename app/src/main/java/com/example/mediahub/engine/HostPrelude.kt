package com.example.mediahub.engine

/**
 * 在 main.js 之前执行的 JS：构造 host 对象与 CommonJS 环境。
 * __h_* 为 Kotlin 注册的全局函数（参数/返回均为字符串）。
 */
internal object HostPrelude {
    fun build(sourceIdJson: String) = """
var globalThis = this;
(function(){
  function j(x){ return JSON.parse(x); }
  function s(x){ return JSON.stringify(x == null ? {} : x); }
  function nodeOf(n){ if (typeof n === 'string') return j(__h_parse(n)); return n; }
  function textOf(n){
    if (n == null) return '';
    if (typeof n === 'string') return n;
    var out = (n.texts || []).join('');
    var ch = n.children || [];
    for (var i = 0; i < ch.length; i++) out += textOf(ch[i]);
    return out;
  }
  var host = {
    source: $sourceIdJson,
    fetch: function(opt){ if (typeof opt === 'string') opt = {url: opt}; return j(__h_fetch(s(opt))); },
    fetchText: function(opt){ if (typeof opt === 'string') opt = {url: opt}; return j(__h_fetchText(s(opt))); },
    parseHtml: function(html){ return j(__h_parse(String(html == null ? '' : html))); },
    queryAll: function(node, sel){ if (node == null) return []; return j(__h_queryAll(s(nodeOf(node)), String(sel))); },
    query: function(node, sel){ var r = host.queryAll(node, sel); return r.length ? r[0] : null; },
    queryText: function(node, sel){
      var n = sel == null ? nodeOf(node) : host.query(node, sel);
      return n ? textOf(n).replace(/\s+/g, ' ').trim() : '';
    },
    // attr(node, name) 或 attr(node, selector, name)
    attr: function(node, a, b){
      var n = b === undefined ? nodeOf(node) : host.query(node, a);
      var name = b === undefined ? a : b;
      if (!n || !n.attrs) return '';
      var v = n.attrs[name];
      return v == null ? '' : String(v);
    },
    nodeText: function(node){ return textOf(nodeOf(node)).replace(/\s+/g, ' ').trim(); },
    dramaId: function(a){ return __h_dramaId(String(a)); },
    chapterId: function(a, b){ return __h_chapterId(String(a), String(b == null ? '' : b)); },
    log: function(){
      var parts = [];
      for (var i = 0; i < arguments.length; i++) {
        var v = arguments[i]; parts.push(typeof v === 'string' ? v : JSON.stringify(v));
      }
      __h_log(parts.join(' '));
    }
  };
  globalThis.host = host;
  globalThis.console = { log: host.log, info: host.log, warn: host.log, error: host.log, debug: host.log };
  globalThis.module = { exports: {} };
  globalThis.exports = globalThis.module.exports;
})();
"""

    /** main.js 执行完后调用，固定导出对象引用 */
    const val CAPTURE = "globalThis.__mod = module.exports; typeof __mod;"

    /** 调用模块函数，结果统一 JSON 化（支持同步返回值与 Promise） */
    fun call(fn: String, argsJson: String) = """
(async function(){
  var f = __mod[$fn];
  if (typeof f !== 'function') throw new Error('模块未导出函数: ' + $fn);
  var r = await f.apply(__mod, $argsJson);
  return JSON.stringify(r === undefined ? null : r);
})()
"""
}
