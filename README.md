# MediaHub

Kotlin + Jetpack Compose 通用内容客户端。App 不内置任何规则，只支持从手机本地导入：
- JS 模块 zip（`manifest.json` + `main.js`，`module.exports` 导出接口）
- Legado 书源 JSON

## 编译
GitHub Actions（JDK 17）自动编译 release APK。在仓库 Settings → Secrets 中配置：

| Secret | 说明 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` 的输出 |
| `SIGNING_STORE_PASSWORD` | keystore 密码 |
| `SIGNING_KEY_ALIAS` | key 别名 |
| `SIGNING_KEY_PASSWORD` | key 密码 |

本地编译：安装 Gradle 8.9 后执行 `gradle assembleDebug`（或用 `gradle wrapper` 生成 wrapper）。

## 阶段
1. ✅ 工程骨架、Gradle、Actions、规则导入页
2. ✅ JS 模块引擎（QuickJS + host 对象）
3. ✅ 视频播放（ExoPlayer）
4. ✅ 漫画阅读器
5. ✅ Legado 书源解析
6. ✅ 搜索 / 分类浏览 / 书架 / 断点续看 / 阅读设置 / 规则测试 / 日志页

## 使用
1. 「规则」页右下角从本地导入模块 zip 或书源 JSON；点击某条规则进入测试页，右上角是日志
2. 「搜索」并发搜索所有已启用规则；「发现」按规则浏览分类
3. 详情页右上角 ☆ 加入书架；书架点击直接继续，长按可查看详情或移出
4. 小说阅读页点屏幕中间呼出菜单：目录、阅读设置（字号、行距、背景、翻页方式、夜间模式）

## transformImage 返回格式
- 网格：`{rows, cols, order:[...], direction?:"vertical"}`，输出第 i 块取原图第 order[i] 块
- 逐块：`{width, height, pieces:[{sx,sy,sw,sh,dx,dy,dw?,dh?}]}`
- 返回 null/false 表示不处理

## resolveMedia 返回格式
`{url, referer?, quality?, headers?}`。`headers` 是可选的额外请求头对象，例如 `{"Cookie": "a=1", "User-Agent": "..."}`，
播放视频和加载漫画图片时都会带上；同名的会覆盖默认的 User-Agent / Referer。

## JS 环境补充
- 内置 `setTimeout` / `setInterval` / `clearTimeout` / `atob` / `btoa`（引擎自带的不覆盖）
- `host.cookie.get(url)` / `host.cookie.set(url, "a=1; b=2")` / `host.cookie.remove(url)`：读写 App 保存的 Cookie
- 网站下发的 Cookie 自动保存，重启后仍有效；在 设置 里可以清除
- 书源 JS 内置迷你 Jsoup：`org.jsoup.Jsoup.parse(html, url)`、`Packages.java.lang.String.valueOf` 可直接用，
  支持 `select / first / get / size / text / html / attr / absUrl / parent / remove / title / body`
- 书源 `cookie.getCookie(url, key?)` / `cookie.setCookie(url, str)` / `cookie.removeCookie(url)` 和 `java.getCookie(url, key?)`

## 缓存
详情、小说目录和正文缓存在本地（cacheDir/mh_cache），打开时先显示缓存，超过 30 分钟会在后台自动更新。

## 视频播放
- 控制栏：倍速（0.5x～3x，会记住）、跳过片头片尾（⏱，按剧设置）、上一集 / 下一集、进度条、全屏
- 手势：单击显示/隐藏控制栏，双击暂停，长按临时倍速（默认 2x，设置里可改或关闭）
- 左右滑快进快退；左半屏上下滑调亮度，右半屏调音量（离开播放页恢复系统亮度）
- 竖屏短剧：上滑下一集、下滑上一集（设置里可关闭，关闭后上下滑恢复亮度/音量）
- 跳过片头：从片头结束处开始播放（已看过片头的不受影响）；跳过片尾：播到片尾直接下一集

## 书架
- 顶部「继续观看」：最近有进度的 8 部，点一下直接接着看
- 按 视频 / 漫画 / 小说 分类，右上角排序：最近阅读、最近加入、名称、有更新的优先（会记住）
- 更新提醒：打开书架时自动检查（距上次超过 30 分钟，设置里可关闭），右上角 ↻ 手动检查；
  有新集数 / 新章节的封面上显示「更新 N」，打开详情或继续观看后消失

## 规则页
- 卡片显示最近一次测试结果：✅ 正常（条数、耗时、多久前）/ ❌ 失败（哪一步、错误信息）/ ⚪ 未测试
- 右上角「检测全部」：每个规则请求一页分类（书源用发现页），没有的用关键词搜索，最多 4 个同时进行
- 按 类型 / 失败 / 未测试 / 已停用 筛选；「失败」里可以只重新检测失败的
- 测试页运行完也会更新卡片上的结果；重新导入规则后旧结果会清除
