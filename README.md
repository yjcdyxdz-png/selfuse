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
