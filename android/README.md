# 映光卡匣 Android

原生 Java Activity + 离线 WebView，Android8.0（API26）及以上，target API35。当前1.0.4（versionCode5），内置8张RuiC层卡。横竖屏和分屏自适应，Three.js与资源打包于APK，不申请网络权限；下载扫描首次请求相应文件读取权限。APK无按CPU架构区分的原生库，可用于ARM64平板。

## 下载扫描与免导入预览

「导入卡片」后台扫描公共Download和子目录，支持单卡.ruic、单卡ZIP、只含ruic子包的合集ZIP；普通ZIP和带library-state.json的备份不进入快捷导入。一次最多选择50个文件，显示至多100个候选；目录深度、遍历数和实际解压预算均有界。合集共用768MiB实际读取/展开预算，不逐子包重置；SHA去重记录指向实际活动ID，删除卡片后可重导。记账失败仅撤回本次新增卡，保留已有去重卡。

Android11+通过MANAGE_EXTERNAL_STORAGE特殊设置授权，返回onResume重查真实权限后自动扫描；Android8–10使用READ_EXTERNAL_STORAGE（maxSdk29）和Android10兼容标记。权限被拒绝或撤回时仍保留SAF手动入口。依据：[所有文件访问](https://developer.android.com/training/data-storage/manage-all-files)、[SAF的Download目录限制](https://developer.android.com/training/data-storage/shared/documents-files#access-restrictions)。

每份下载可先预览；VIEW/SEND接收已授权content文档或本地闪卡文件。临时CardStore位于独立私有cache，正式库不变；固定源/preview/session/id路由只暴露经过验证的数据文件，返回或销毁后清理。只有明确点击收入卡库才按共享去重逻辑提交。合集可前后切换，临时调参不写正式参数。文件关联是否在“打开方式”显示取决于发送端的MIME与URI，未暴露打开入口时可先保存到Download，再从App扫描。

## 构建

JDK 17，Android SDK 35，Gradle 9.1.0，Android Gradle Plugin 8.11.1。先设置 `ANDROID_HOME` 和 `JAVA_HOME`，在本目录执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
# 已有 Gradle 和插件缓存时可加 --offline
# 本机优先复用已解压的 Gradle 缓存，避免 wrapper 因 URL 不同重新下载
pwsh -File .\build.ps1 -Configuration Release -Offline
```

`preBuild` 自动将项目 `web/` 同步至 `app/generated-assets/library/`，并将根目录 `cards/` 中的目录索引、增强封面、卡配置与渲染资源同步至 `library/cards/`。仅复制运行时文件，不重复打包根 assets、Blender、验证图或 node_modules。更换图片或卡包后重新构建即可。`app/generated-assets/` 与 build 输出不入版本库。

正式签名从环境变量 `RUIC_SIGNING_PROPERTIES` 指定的文件读取；缺省位置为用户目录 `.codex/signing/ruic-card-library/signing.properties`。属性键：`storeFile`、`storePassword`、`keyAlias`、`keyPassword`。签名文件与密码留在本机独立目录，不放入源码或交付压缩包。配置后运行 `:app:assembleRelease`。

## 导入与备份

系统文件选择器支持多选 PNG、JPEG、WebP 或闪卡 ZIP / `.ruic`。普通图片展示为单图光泽卡；完整层卡需四张层图和 GLB。缺少层或模型时使用封面作为单图，不伪造深度。

单卡包可为根目录或一层外目录，包含 `card.json`（元数据）或 `card-config.json`（RuiC 配置），以及 `assets/` 图片、GLB、封面。JSON 仅保留已知字段，并将资源重新写为当前卡内部相对地址；HTML、JavaScript、SVG 等不可导入。文件名越界、重复条目、超量文件和超大图片会被拒绝。

上限：单文件 96 MiB，ZIP 512 MiB，展开总量 768 MiB，1024 条目；每张图任一边不超过 8192 像素且不超过 3200 万像素。最多一次选择 50 个文件。

备份 ZIP 包含导入卡片的资源以及 `library-state.json`（收藏、重命名、微调参数和全局观赏设置）；内置卡随 APK 提供。1.0.1 延续 schema 1 与存储键，旧状态缺少 settings 时使用默认旋转灵敏度 1.00。导出先检查与恢复相同的条目、展开大小及实际 ZIP 大小上限，超限时明确失败。备份在私有缓存完整生成后才写入所选位置，参数快照通过私有 AtomicFile 跨系统保存器的 Activity / 进程重建保留。恢复时保留卡片 ID，与已存在 ID 冲突的卡片文件跳过，随后恢复网页状态。只有内置卡时也可备份和恢复参数。同签名 1.0.1（versionCode 2）可覆盖安装 1.0.0 保留数据；卸载会清除应用私有卡库，请先保存备份。

## 原生桥

- `window.NativeCards.listCards()`：同步 JSON 数组。
- `importFiles()`：系统导入选择器。
- `scanDownloads()` / `requestDownloadAccess()`：后台扫描与一次授权，回调`onNativeDownloadsResult`。
- `importDownloads(idsJson)`：仅接受当前opaque ID，回调导入进度和结果；不接受网页路径。
- `previewDownload(id)` / `commitPreview(sessionId)` / `closePreview(sessionId)`：临时预览、显式保存和串行清理。
- `exportLibrary(stateJson)`：系统备份保存选择器。
- `deleteCard(id)`：同步删除导入卡，返回布尔值。
- `setFullscreen(boolean)`：显示或隐藏系统栏。
- 网页回调：`onNativeCardsChanged()`、`onNativeResult({ok,message})`、`onNativeRestoreState(stateObject)`。
- `handleNativeBack()` 返回 `true` 时网页已处理返回；否则原生显示退出提示。

1.0.3 使用统一的云白界面：原生启动背景、WebView、状态栏和导航栏为白色，系统图标为深色，原生提示框使用浅色主题和蓝色强调色。预览进入时网页通过 `setFullscreen(true)` 隐藏系统栏，返回卡库后恢复；系统文件选择器返回和窗口重新获得焦点时重新应用当前状态。Android 11 及以上使用 WindowInsetsController，Android 8–10 使用兼容的沉浸式标记；两条路径都由容器处理可见系统栏的安全边距。Android 返回键始终先调用网页 `handleNativeBack()`，原生不另行切换预览状态。

固定可信源 `https://appassets.androidplatform.net/`，仅打包主页可成为顶层，固定RuiC viewer可成为子框架；`/assets/library/`提供内置资源，`/storage/`提供正式卡库数据，`/preview/`提供当前临时会话的已验证数据。其余请求404；网页直接文件访问、任意导航、外部框架和插件对象禁用。

本机已创建独立 Android 15 x86_64 平板测试 AVD `ruic_magicpad_qa`，使用 WHPX + SwiftShader，无窗口运行。Android 原生存储、备份恢复、恶意导入包拒绝的 27 项断言通过；系统文件选择器导入普通图片和 RuiC 层卡、导出、删除后恢复已实际点击验证。打开保存器后销毁 Activity 并杀死后台应用，返回重建后备份仍保留收藏与微调参数。离线 WebView 已验证 GLB、全息材质、翻面、四层展开/合回及层间距调节，并检查 1920×1280 横屏、1280×1920 竖屏布局。竖屏时光影调校位于展台下方，可滚动操作。荣耀平板的实际 GPU 表现仍需真机验证。

路径/数据白名单、UTF-8 备份大小边界与实际恶意 ZIP 的主机检查：先构建 Debug Java 类，再执行 `pwsh -File tests/run-host-checks.ps1`；共 25 项路径/类型检查、5 项 UTF-8 边界检查、8 项备份限额边界检查和 5 项实际 ZIP 读入攻击检查。Android 原生测试入口为 `CardStoreInstrumentation`，无第三方测试依赖。
