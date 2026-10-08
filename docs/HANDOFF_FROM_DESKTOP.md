# Joy Android 交接笔记（从桌面 Joy 会话迁出）

> 用途：新开 Cursor 窗口打开本工程后，把本文件丢给 Agent / 自己当上下文入口。  
> 桌面端仓库：`D:\GitCode\myproject-d\Joy`（本文件**不替代**桌面文档，只搬「安卓要用的经验」）。

---

## 1. 产品定位（安卓 MVP）

- **不是**复刻 Win 贴边 Dock / 智驾自动发。
- **是**：拿到聊天上下文 → 云端出短回复 → **复制到微信粘贴发送**。
- 已有能力：
  1. 粘贴文本 → 生成 → 复制
  2. 设置页（API / 人设 / 识图）
  3. 选图 OCR / 视觉 → 填入文本框
  4. OCR 按左右气泡标 `对方：` / `我：`
  5. 服务商预设默认对齐桌面：**商汤 SenseNova**
  6. **多会话**：按联系人分开存上下文与最近候选

---

## 2. 与桌面端对齐的约定（务必保持）

| 项 | 约定 |
|----|------|
| 默认 Base URL | `https://token.sensenova.cn/v1` |
| 默认 Model | `deepseek-v4-flash` |
| 协议 | OpenAI 兼容 `POST .../chat/completions` |
| 角色标注 | `对方：` / `我：`（生成时站在「我」） |
| 回复形态 | 1～2 条短口语，可直接发送，少官腔 |
| Key | 与电脑端同一把商汤 Token；存在 DataStore，勿打日志 |

桌面预设源码参考：`Joy/src/joy/providers/presets.py`（`sensenova`）。

---

## 3. 工程与工具链

- 路径：`C:\Users\Moky\myproject\apps\project01\apps\joy`
- 包名：`com.joy.android`
- Kotlin + Jetpack Compose + Material3
- Min SDK 26
- 依赖要点：OkHttp、DataStore、Navigation、ML Kit **中文** OCR
- Gradle：国内建议镜像（wrapper 曾用阿里云 macports 的 `gradle-9.4.1-bin`；依赖走 `maven.aliyun.com`）
- IDE：`*-src.zip` 可取消，编译不需要；慢时看 Build → Sync

---

## 4. 代码地图（改功能先看这里）

```
app/src/main/java/com/joy/android/
  MainActivity.kt          # 入口
  data/
    JoySettings.kt         # 默认商汤；imageCaptureMode / visionModel
    JoySettingsStore.kt    # DataStore；旧 OpenAI 空 Key 会迁到商汤
    ChatSession.kt         # 多会话模型 + JSON 编解码
    SessionStore.kt        # DataStore 持久化上下文 / 候选
    ProviderPresets.kt     # 商汤/DeepSeek/Kimi/OpenAI/自定义
    ChatLlmClient.kt       # 回复生成 + 视觉抽聊天
    ImageOcrHelper.kt      # 选图 OCR + 左右分角色 / 嵌图过滤
    ImageVisionEncoder.kt  # 视觉模式：压图 → data URL
    ImageCaptureMode.kt    # OCR | VISION
  ui/
    JoyApp.kt              # 导航：主页 / 会话 / 设置子页
    ReplyScreen.kt         # 主页：会话条 + 选图 / 生成 / 复制
    ReplyViewModel.kt
    SessionsScreen.kt      # 新建 / 切换 / 重命名 / 删除
    SettingsScreen.kt      # 仅菜单入口
    OverlaySettingsScreen.kt # 悬浮球权限与开关
    ModelSettingsScreen.kt # 服务商 / Key / 回复模型 / 视觉模型
    ImageSettingsScreen.kt # OCR|视觉 + 嵌图策略
    PersonaSettingsScreen.kt # 系统提示 / 人设
  overlay/
    OverlayController.kt   # 启停 / 权限
    JoyOverlayService.kt   # 前台服务 + WindowManager 小球/面板
    OverlayContent.kt      # 小球与面板 Compose UI
```

---

## 5. 桌面踩过的坑（安卓要避开的）

1. **顶栏昵称别依赖云端 Vision**（桌面曾 429 / 瞎推理）；本地 OCR 够用。安卓已走 ML Kit。
2. **限流会拖死整条链路**；少重试风暴，失败要冷却。
3. **角色不分会串回复**；左右气泡启发式不完美，允许用户手改文本框。
4. **仓库/会话混用**会丢上下文：安卓请单独 Cursor 窗口 + 本文件续聊。

---

## 6. 已知粗糙点（下一轮打磨）

- [x] OCR：时间条 / 昵称栏 / 底栏 Tab 误入（顶底裁切 + 时间正则；聊天区短「我」保留）
- [x] OCR：左右阈值在全面屏、分屏、放大字体下误判（改左右缘贴边，少靠 centerX）
- [x] OCR：同行气泡拆行合并策略（同侧 + 行距 + 左右缘对齐）
- [x] OCR：嵌图文字噪点（浅色气泡底色 + **字号相对「我」侧基准** + 密集聚类）
- [ ] OCR：暗色主题气泡色专用标定（当前暗色下更依赖字号）
- [ ] OCR：可选「无图截图」校准字号/色号
- [x] 识图双模式：OCR（默认）/ 视觉多模态（设置切换，默认 vision 模型 sensenova-6.8-flash-lite）
- [x] 视觉嵌图策略：忽略 / 标注 `[图片]` / 简述 `[图片:…]`（默认标注）
- [x] 设置：一键「连通测试」
- [x] 多会话 + 上下文持久化（聊天摘录 / 最近候选；按联系人命名）
- [x] 识图自动取顶栏对方昵称并更新当前会话名（OCR / 视觉）
- [x] 悬浮球外观 MVP（Joy 展开：刷新/模式/解析/主页；模式可切换展示，截屏·填入·发送待接）
- [x] 悬浮球：手动截屏解析 → 填入输入栏（无障碍；否则剪贴板）
- [x] 悬浮球：自动看屏变动 + 填入
- [x] 悬浮球：智驾自动发送（无障碍点发送；机型差异大，需实机微调）
- [ ] 历史草稿（跨会话的候选时间线；当前已随会话存最近一轮）
- [ ] 分享到 Joy
- [ ] 提示词与桌面 reply 规则进一步对齐

---

## 7. 建议迭代顺序（别跳）

1. OCR 细节打磨（误识别、角色准一点）
2. ~~设置连通测试 + 错误文案可读~~（已做：模型配置页「连通测试」）
3. ~~多会话 / 上下文存储~~（已做）；可选再做跨会话草稿时间线
4. ~~快捷入口（悬浮）~~（已做 MVP）；可选再做分享到 Joy
5. 很久以后：无障碍自动填微信（机型碎、审核难）

---

## 8. 新会话怎么开场（复制即用）

```text
打开的是 Joy Android 工程。请先读 docs/HANDOFF_FROM_DESKTOP.md，
按里面的约定继续。当前优先：打磨 OCR 对方/我 与噪声过滤。
不要改电脑端 Joy 仓库。
```

---

## 9. 和桌面仓库的关系

| | 桌面 Joy | 本安卓工程 |
|--|----------|------------|
| 职责 | Win 截窗 / Dock / 智驾 | 粘贴·截图·生成·复制 |
| 共享 | 产品规则、预设、提示词思路 | 同上 |
| 不共享 | PySide、PyInstaller、Win OCR 管线 | ML Kit、Compose |

经验以**本文件 + 代码**为准往下传；不必整段搬聊天记录。
