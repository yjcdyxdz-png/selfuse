// MediaHub 内置：给 JS 引擎补上常用的全局函数。引擎本身已经有的不会覆盖。
(function (g) {
  var C = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

  if (typeof g.btoa === 'undefined') {
    g.btoa = function (input) {
      var s = String(input), out = '';
      for (var i = 0; i < s.length; i += 3) {
        var a = s.charCodeAt(i);
        var b = i + 1 < s.length ? s.charCodeAt(i + 1) : -1;
        var c = i + 2 < s.length ? s.charCodeAt(i + 2) : -1;
        if (a > 255 || b > 255 || c > 255) throw new Error('btoa: 字符超出 Latin1 范围，请先 encodeURIComponent');
        var n = (a << 16) | ((b < 0 ? 0 : b) << 8) | (c < 0 ? 0 : c);
        out += C.charAt((n >> 18) & 63) + C.charAt((n >> 12) & 63) +
          (b < 0 ? '=' : C.charAt((n >> 6) & 63)) + (c < 0 ? '=' : C.charAt(n & 63));
      }
      return out;
    };
  }

  if (typeof g.atob === 'undefined') {
    g.atob = function (input) {
      var s = String(input).replace(/[\s=]/g, '').replace(/-/g, '+').replace(/_/g, '/');
      var out = '', buf = 0, bits = 0;
      for (var i = 0; i < s.length; i++) {
        var v = C.indexOf(s.charAt(i));
        if (v < 0) throw new Error('atob: 非法字符 ' + s.charAt(i));
        buf = ((buf << 6) | v) & 0xffffff;
        bits += 6;
        if (bits >= 8) { bits -= 8; out += String.fromCharCode((buf >> bits) & 255); }
      }
      return out;
    };
  }

  // 定时器：App 在等待模块返回结果时会不断调用 __runTimers() 执行到期的回调
  if (typeof g.setTimeout === 'undefined') {
    var timers = [], seq = 0;
    var add = function (fn, ms, args, repeat) {
      var id = ++seq;
      ms = Math.max(0, Number(ms) || 0);
      timers.push({ id: id, due: Date.now() + ms, ms: ms, fn: fn, args: args, repeat: repeat });
      return id;
    };
    g.setTimeout = function (fn, ms) { return add(fn, ms, Array.prototype.slice.call(arguments, 2), false); };
    g.setInterval = function (fn, ms) { return add(fn, ms, Array.prototype.slice.call(arguments, 2), true); };
    g.setImmediate = function (fn) { return add(fn, 0, Array.prototype.slice.call(arguments, 1), false); };
    g.clearTimeout = g.clearInterval = g.clearImmediate = function (id) {
      for (var i = 0; i < timers.length; i++) if (timers[i].id === id) { timers.splice(i, 1); return; }
    };
    g.__runTimers = function () {
      for (var guard = 0; guard < 1000; guard++) {
        var now = Date.now(), idx = -1;
        for (var i = 0; i < timers.length; i++) {
          if (timers[i].due <= now && (idx < 0 || timers[i].due < timers[idx].due)) idx = i;
        }
        if (idx < 0) break;
        var t = timers[idx];
        if (t.repeat) t.due = now + Math.max(1, t.ms); else timers.splice(idx, 1);
        try {
          if (typeof t.fn === 'function') t.fn.apply(g, t.args); else (0, eval)(String(t.fn));
        } catch (e) {
          if (g.console && g.console.log) g.console.log('定时器回调出错: ' + (e && e.message || e));
        }
      }
      return timers.length;
    };
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
