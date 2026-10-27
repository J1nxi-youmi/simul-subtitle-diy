# 同声传译（diy版）

个人自用的实时字幕，**安卓 + Windows 双端**，两端功能一致、同一次推送一起构建。
基于 [KorenKrita/simul-subtitle](https://github.com/KorenKrita/simul-subtitle) 修改。

## 下载

两个平台的文件挂在**同一个页面**的 Assets 里（[最新构建](../../releases/tag/diy-latest)），按文件名区分：

### 安卓 —— `app-debug.apk`（约 251 MB）

[app-debug.apk](../../releases/latest/download/app-debug.apk)

需要 Android 10 以上、arm64。装完在 App 里就能检查更新。

### Windows —— `Windows-SimulSub-x64.zip`（约 215 MB）

[Windows-SimulSub-x64.zip](../../releases/latest/download/Windows-SimulSub-x64.zip)

解压到英文路径后双击 `SimulSub.exe`。没有代码签名，首次运行会弹 SmartScreen，点「更多信息 → 仍要运行」。
桌面版没有应用内更新，升级要重新下载。

## 特点

两端相同：

- **识别**：SenseVoice，中/英/日/韩/粤一部模型全认，可锁语种也可自动判断
- **翻译**：填自己的 OpenAI 兼容接口。Qwen-MT 走原生协议，语言和术语用结构化参数传，不塞提示词
- **术语**：只把这一句里真的出现的词发过去，没出现的不发（否则模型会硬塞进译文）
- **残留假名**：译文里还剩成串平假名或片假名时（如「别说是アルバイト」「…像廉んかちゃん那样」），自动带提示重译一次
- **译文检查**：可选，用自己的 LLM 逐句复核，发现译反、漏否定、直译比喻就带提示重译；可单独配地址和 key
- **断句**：停顿切段、单句最长都能调——调小出字快但句子碎，调大句子完整但更晚
- **字幕**：边说边出草稿（灰字会就地修正），或说完再出；可同时显示原文和用时
- **本次会话**：整场说过的句子留在首页，可搜索、导出成文本、清空；停止后仍保留，下一场开始才重置
- **隐私**：声音只在本机识别，不上传；翻译只发识别出的文字

只有安卓有：应用内检查更新、覆盖安装保留配置。

## 用起来

两端流程一样：

1. 装好上面的包
2. **只需要填 API Key**：安卓底部「翻译」→ LLM API → 粘贴 key → 点「测试连接」
   - 地址（`https://dashscope.aliyuncs.com/compatible-mode/v1`）和模型（`qwen-flash`）已经填好了
   - 用别的服务商时再改这两项
   - Windows 还是旧界面：设置 → 翻译 → LLM API
3. 填完语种：「翻译 → 语言 → 识别语种」（默认日语）→ 回「首页」点「开始字幕」→ 选要听的 App / 直接播放
4. 安卓更新：「页面 → 关于 → 检查更新」

手机端是五个栏目挂在底部，设置项都在各自栏目里，没有二级页，也没有「返回」：

| 栏目 | 放什么 |
|---|---|
| 首页 | 本次会话、开始/停止字幕、三颗状态胶囊（引擎 / 翻译模型 / 边听边出）|
| 字幕 | 外观（预览、配色、字号、浓淡）与显示（原文、用时、宽度、高度、保留几句）|
| 翻译 | 语言、LLM API、内容类型、断句、字幕怎么出现、译文检查 |
| 页面 | 页面颜色、通知、关于（版本与检查更新）|
| 记录 | 使用记录开关、保留音频、记录列表 |

第一次用的时候点「开始字幕」，缺什么就跳到哪一步：没填 key → 翻译页；没悬浮窗权限 → 系统授权页，回来会自己接着开始。

默认配置是实测出来的（同一场直播、同一套提示词）：

| 模型 | 翻译 p50 | 端到端 p50 | 上下文 |
|---|---|---|---|
| **`qwen-flash` + 上文 2 句**（默认） | 346 ms | **833 ms** | ✅ |
| `qwen-mt-plus` | 268 ms | 1370 ms | ❌ |
| `qwen3.7-plus` | 1046 ms | 2684 ms | ✅ |

Windows 上因为 CPU 更强，实测端到端中位 **554 ms**（识别 55 ms、翻译 324 ms）。

成本：一场 1 小时直播约 ¥0.1~0.2（`qwen-flash` 前缀缓存命中率 56~66%）。

## 已知限制

- 识别语种选「自动」时，短句容易判错语种（实测 52 句里 19 句被判成中文或英文）。纯日语直播请选「日语」
- 游戏里的玩家名、技能名仍会认错；术语表能修译文，修不了识别
- 直播黑话容易连锁出错：识别听错 → 翻译照着错（如 `エフエッ` 被当成人名「FFJ」）
- 安卓的应用内更新会依次尝试 github.com、gh-proxy、ghfast、ghproxy、gh.llkk 五个源，都不通时只能手动下载
- 选「粤语」时识别正常，但翻译的源语言会退回「自动」（交给翻译模型自己判断，效果不受影响）
- Windows 版没有应用内更新；`sysaudio` 抓的是默认输出设备，正在用的耳机/音箱如果不是默认设备可能抓不到声音

## 说明

- 这是我自己用的改版，**不承诺修问题、不接功能请求**；拿去用没问题，出了问题我不兜底
- 字幕是机器翻译出来的，会有错。**别用在需要准确信息的地方**（看病、法律、交易这类）
- API 费用、额度、账号风险都是你自己的，和原作者、以及文中提到的模型厂商都没关系
- 再分发请保留 [LICENSE](LICENSE) 和致谢，别说成自己的原创

## 构建

推送到 `main`，两条流水线同时跑，各出一个包：

| 工作流 | 产物 | 发布位置 |
|---|---|---|
| `android.yml` | `app-debug.apk` | 滚动 tag `diy-latest`（应用内更新读它）|
| `windows.yml` | `Windows-SimulSub-x64.zip`（挂在同一个 `diy-latest` 的 Assets 里） | 滚动 tag `diy-latest` |

- 两端功能改动请同时改到位，否则同步更新没意义
- 安卓签名固定在 Actions 缓存里，覆盖安装不丢配置
- 版本名是 `DIY` + `versionCode`（如 `DIY1064`），`versionCode` 取 `1000 + 构建序号`，应用内更新靠它判断有没有新版本
- 每个版本都会打个 tag（`vDIYNNNN`），但**只有明确要求的版本才留 APK**：
  - 提交信息**末尾**带 `[keep]`，或
  - 手动 Run workflow 时勾选「保留」
  否则只更新滚动 release，磁盘不会一直涨
- `app/build.gradle.kts` 里只能有**一处** `versionName = "..."` 这种写法——桌面打包脚本用它取版本号
- 每个版本的改动见 [CHANGELOG.md](CHANGELOG.md)

## 致谢

感谢 [KorenKrita](https://github.com/KorenKrita) 的 [simul-subtitle](https://github.com/KorenKrita/simul-subtitle)。

悬浮字幕、音频内录、VAD 断句、草稿/定稿字幕、BYOK 翻译、术语与场景、桌面端……这套东西的骨架全是他一个人搭的。没有他的工作，这个 diy 版根本不存在——我只是在他的成果上换了自己更习惯的识别模型，改了几处交互和译文检查的实现。

也感谢 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（本地推理）、[SenseVoice](https://github.com/FunAudioLLM/SenseVoice)（多语种识别）、[Silero VAD](https://github.com/snakers4/silero-vad)（断句）。

## 许可

沿用 [LICENSE](LICENSE)。
