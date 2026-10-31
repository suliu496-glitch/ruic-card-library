# 映光卡匣前端 1.0.3

启动：项目根目录运行 `node scripts/dev-server.mjs`，打开 `http://127.0.0.1:4173/`。页面无运行时外网依赖。`viewer/app.bundle.js` 内联 Three.js 0.180.0；修改 RuiC viewer 副本后，在 `web/viewer` 执行 `npm install --ignore-scripts`、`bun build ./app.js --outfile=./app.bundle.js --target=browser`。

首页采用用户选定的「云白相册」，卡库、预览与设置统一使用白底、浅灰和蓝色。封面网格提供搜索、全部/收藏筛选、排序、导入，以及标准／紧凑两种密度；备份入口位于设置页。`library-ui.js` 切换网格密度，并按当前可见卡片保持浏览位置。

轻点封面进入占满视口的单卡预览，同时请求浏览器或安卓系统全屏；主页不会预加载交互展台。即使浏览器拒绝或退出系统全屏，预览仍保持完整视口布局。`preview-ui.js` 管理光影参数面板，每次进入预览或切卡都默认折叠；宽屏在右侧展开，窄屏使用可滚动的浮动底部面板，`preview.css` 控制布局。参数控件始终保留在 DOM 中，展开与折叠不会重建滑块或清除当前参数。

「返回卡库」直接退出预览与系统全屏，并恢复原浏览位置。应用收到 ESC 或安卓返回键时，先关闭已打开的对话框，再收起参数面板，最后返回卡库；返回时保留卡片 DOM、图片节点与筛选条件。`.workspace[data-view="library"]` 和 `[data-view="preview"]` 控制两种界面，`body.preview-screen` 控制预览视口，`#card-list` 是卡库自己的滚动容器。

卡库页头和预览顶部的齿轮打开同一个「观赏设置」，其中包含旋转灵敏度和「备份卡匣与参数」。拖动旋转灵敏度适用于全部卡片与分层展示，范围 0.25–2.00、步长 0.05、默认 1.00×；调整立即生效并保存到本机。「恢复默认灵敏度」只重置这一项，不改收藏或逐卡光影参数。`settings.js` 导出 `initSettings(onChange)` 和 `renderSettings(state.settings)`：回调接收规范化后的完整设置，由 app 保存并调用 `viewer.setRotationSensitivity(value)`；读取或恢复备份后再同步设置界面。对话框支持键盘焦点循环、ESC、安卓返回和关闭后焦点返回。

`cards/catalog.json` 是数组，`cover` 与 `configUrl` 相对该 JSON 的 URL 解析。Android 的首页为 `https://appassets.androidplatform.net/assets/library/index.html`；桌面服务把 `/cards/` 映射到项目 cards 目录。分层卡使用相同的可信 viewer，配置必须包含真实 GLB 和对应 RGBA 层；普通导入图片明确显示「单图光泽」。

原生接口：`window.NativeCards.listCards(): string`、`importFiles()`、`exportLibrary(stateJson)`、`deleteCard(id): boolean`、`setFullscreen(boolean)`。原生通知入口为 `onNativeCardsChanged()`、`onNativeResult({ok,message,state?})`、`onNativeRestoreState(stateObject)`；返回键先调用 `handleNativeBack(): boolean`。图片/卡文件由原生存储，收藏、名称、参数使用版本 1 的 localStorage 状态，导出 ZIP 包含此状态。浏览器预览可用 IndexedDB 保存导入图片；浏览器下载的 JSON 只备份参数。

状态结构：`{schema:1,activeId,filter,sort,auto,fullscreen:false,settings:{rotationSensitivity:1},cards:{[id]:{title?,favorite,parameters?:{foil,subjectScale,subjectDepth,backgroundDepth,effectsDepth,finish}}}}`。`finish` 可为 `pearl`、`silver`、`gold`、`original`。参数逐卡保存，恢复默认从当前卡配置读取。全局 `settings` 随 localStorage、浏览器 JSON 和安卓 ZIP 状态备份保存；旧 schema 1 状态缺少设置时使用默认值。`cleanSettings` 拒绝非有限值，将有效值限制到范围并按 0.05 量化，与控制器和滑块一致。全屏状态不在重启时恢复，重新打开应用先显示卡库。

父子页面通过 `yingguang-card` 消息通信，每次选卡生成随机 session，并校验同源、发送窗口与 session。数据配置只加载同源图片/GLB；不加载卡片 ZIP 中的代码。`window.__cardLibrary` 用于集成检查，RuiC 的 `window.__holo` 保留于 iframe 内。正常模式保留原 RuiC shader 和 GLB 材质角色；CSS3D 降级保留真实图层，并弱化线稿光罩，降级状态在界面明示。

光效由观赏视角驱动：拖动旋转时，分层卡的镭射扫光、闪点与反光随卡面角度变化；「光泽强度」控制效果分量，「原画」关闭镭射覆盖。单图卡保留原图，以视角驱动的光泽覆层模拟反光，不生成独立人物或背景景深。旋转灵敏度控制拖动同样距离时转过的角度，不改变卡片图层、材质强度或自动转动速度。

「分层展示」在同一 Three.js 场景中暂时隐藏成卡 GLB，显示背景、人物、线稿、文字（有特效时也包含特效）的真实三维平面；原始透明度保留，线稿白底通过材质转换为透明光罩。层标与边线说明每张画布，翻面可查看另一方向。`layers` 消息接受 boolean，`layerGap` 接受 0.25–2.00 数值；`window.__holo.getState()` 包含 `layered`、`layerGap`，`inspectionLayers` 提供实际平面与位置用于验证。CSS3D 降级也使用真实 translateZ 图层和透明线稿画布。单图卡禁用此模式。切卡和恢复默认会合回卡片；参数面板折叠时层间距滑块位于展台上方，展开时随参数移入面板。

RuiC Card Skill、Three.js 与 Lucide 图标的许可证位于 `viewer/LICENSE-RuiC.txt`、`viewer/LICENSE-Three.txt`、`viewer/LICENSE-Lucide.txt`。Android 打包必须排除 `node_modules`。
