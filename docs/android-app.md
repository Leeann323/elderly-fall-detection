# 手机远程告警 App（Android）

配套 App：`com.leeann323.fallalert`（安装包见 [Releases](https://github.com/Leeann323/elderly-fall-detection/releases)）。
作用只有一个——**让不在家的子女，在自己的手机上第一时间收到老人的跌倒告警，哪怕手机和家里的电脑不在同一个网络。**

---

## 1. 为什么需要中转服务

居家摄像头那台电脑大概率在 NAT 后面（没有公网 IP），手机 4G/5G 直连它是不可能的。三种做法：

| 方案 | 可行性 | 代价 |
| --- | --- | --- |
| 端口映射 / 内网穿透（frp、ngrok） | 可行 | 要买服务器或依赖第三方隧道，配置复杂，还暴露端口 |
| 自建 MQTT / WebSocket 服务器 | 可行 | 要一台 7×24 的云主机 + 运维 |
| **公共发布-订阅中转（ntfy.sh）** | ✅ 本项目采用 | 依赖第三方公共服务的稳定性；频道名即"密码" |

选 ntfy.sh 的理由：免费、无需注册、纯 HTTPS（443 端口能出去就行，很多家庭/校园网络只放行 443）、单条消息可保留 12 小时以上、支持 `poll` 拉取模式——**不需要长连接、不需要推送 SDK、不需要 Google 服务框架**，因此在国产 ROM（无 GMS）上同样可用。

数据流：

```
电脑（家里 Wi-Fi / 4G 路由）                    公网                    手机（外地 4G/5G）
┌──────────────────────────┐            ┌──────────────┐            ┌──────────────────────┐
│ elderly_fall_monitor.py  │  HTTPS POST│              │ HTTPS GET  │ App 前台服务轮询      │
│ 判决确认 → Pusher._ntfy  │ ─────────► │   ntfy.sh    │ ◄───────── │ /topic/json?poll=1   │
│ JSON: kind/area/score    │  443       │  topic: xxx  │  每 8 秒   │ → 通知 + 全屏告警页   │
└──────────────────────────┘            └──────────────┘            └──────────────────────┘
```

**关键点：Topic 就是密钥。** 谁知道了 topic 谁就能收到（也能伪造）告警，所以：

- App 首次启动会**自动生成**一个 8 位随机后缀的 topic（形如 `ltc-fall-k7m2xq9p`），不要改成 `test`、`fall` 这类可猜的名字。
- 电脑端配置的 topic 必须和手机里的**逐字符一致**（区分大小写，不能有空格）。

---

## 2. 安装（手机端）

1. 手机浏览器打开 <https://github.com/Leeann323/elderly-fall-detection/releases>，下载 `FallAlert-1.0.apk`。
2. 点击安装，系统提示"未知来源应用"时选择**允许**（这是自签名 APK，不是应用商店分发的，属正常现象）。
3. 打开 App，按提示允许**通知权限**（Android 13+ 必须授予，否则收不到告警）。
4. 记下页面上的「推送地址」，例如 `https://ntfy.sh/ltc-fall-k7m2xq9p`。
5. 点 **启动监听** → 顶部出现常驻通知「跌倒监测 · 监听中」。
6. 点 **发送测试告警** 自检：2~10 秒内应弹出一条红色通知（若没反应，见第 6 节排查）。

> 也可以从**电脑侧**自检整条链路（手机 App 需先「启动监听」）：
> `python test_alert.py ltc-fall-k7m2xq9p` —— 能一次验证「电脑 → ntfy → 手机」是否全通。

> 建议顺手把这些打开，夜里才不会漏：
> 设置 → 应用 → 跌倒告警 → **电池 → 不限制/允许后台活动**；**通知 → 允许打扰/横幅**；
> 锁屏通知设为"显示全部内容"。

---

## 3. 配置（电脑端）

在电脑端配置 topic，**推荐用文件方式**（在 Spyder 里 F5、双击 .py 都能生效）：

```bash
copy ntfy_topic.example.txt ntfy_topic.txt
# 编辑 ntfy_topic.txt，写一行：ltc-fall-k7m2xq9p
python elderly_fall_monitor.py
```

三种方式优先级：环境变量 `NTFY_TOPIC` > 同目录 `ntfy_topic.txt` > 代码里直接写死。

```bash
set NTFY_TOPIC=ltc-fall-k7m2xq9p     # cmd
$env:NTFY_TOPIC="ltc-fall-k7m2xq9p"  # PowerShell
export NTFY_TOPIC=ltc-fall-k7m2xq9p  # Linux / macOS
```

启动时会打印一行自检结果，**这是排查的第一步**：

```
[info] 手机远程推送：已启用 -> https://ntfy.sh/ltc-fall-k7m2xq9p
[info] 手机远程推送：未启用（在 ntfy_topic.txt 写入 Topic，或 set NTFY_TOPIC=...；否则告警只留在本机）
```

每次推送成功也会打印 `[info] 已推送手机告警：F20261105-1730-002 (B)`；失败打印 `[warn] ntfy 推送失败/拒绝`。

> ⚠️ 常见事故：在 Spyder 里按 F5 运行，环境变量不生效 → `NTFY_TOPIC` 为空 → `_ntfy()` 直接 return → 手机永远收不到，只在本地存了骨架图。用 `ntfy_topic.txt` 可以彻底避免。

也可在代码里直接改（不推荐，容易误提交）：

```python
NTFY_SERVER = "https://ntfy.sh"     # 换自建服务就改这里
NTFY_TOPIC  = "ltc-fall-k7m2xq9p"   # 与手机 App 内完全一致
```

钉钉 webhook 与 ntfy 可以**同时启用**，互不影响：钉钉给家里人/群里播报，ntfy 给在外地的子女手机。

### 推送报文

电脑发出（`Content-Type: application/json; charset=utf-8`，UTF-8 编码）：

```json
{"kind":"A","kind_name":"跌倒（冲击型）","area":"客厅",
 "eid":"F20261104-1650-001","time":"2026-11-04 16:50:12",
 "hold":8.0,"score":0.83}
```

手机端字段映射：

| 字段 | 用途 |
| --- | --- |
| `kind` | A/B/C/S，用于标题图标与分级 |
| `kind_name` | 直接显示的中文类型名 |
| `area` | 区域，标题显示为「跌倒（冲击型） · 客厅」 |
| `eid` | 事件编号，和 `alerts/` 里的骨架图同名，方便事后对图 |
| `hold` / `score` | 持续时间 / 置信度 |
| `time` | 事件时间（电脑本地时间） |

---

## 4. App 内部实现（给想看代码的人）

零第三方依赖：**不用 AndroidX、不用 Firebase、不用 Gradle**，纯 `android.*` + `org.json`，APK 只有 30 KB 左右。

| 组件 | 职责 |
| --- | --- |
| `MainActivity` | 配置 server/topic、启停监听、测试推送、本地告警列表 |
| `AlertService` | 前台服务，每 8 秒 `GET /topic/json?poll=1&since=<lastId>`；解析 ndjson，逐条入库并通知 |
| `AlertActivity` | 全屏红色告警页，锁屏点亮（`setShowWhenLocked` + `setTurnScreenOn`），按钮「已处理」 |
| `AlertAdapter` | `ListView` 适配器（不用 RecyclerView，避免 AndroidX） |
| `BootReceiver` | 开机自启（前提是上次处于监听状态） |
| `P` / `Alert` / `Net` / `C` | 配置存储（SharedPreferences）、模型、HTTP、常量 |

设计取舍：

- **轮询而非长连接**：`poll=1` 每次只取增量，断网/切后台都能自愈；代价是最坏情况 8 秒延迟、以及每 8 秒一次 1 KB 请求的耗电（前台服务常驻约 1~2%/小时）。
- **`since` 游标持久化**：把最后一条消息 id 存进 SharedPreferences，App 被杀掉重启后不会漏消息，也不会重复报警。
- **全屏 Intent**：`USE_FULL_SCREEN_INTENT` + `CATEGORY_ALARM`，锁屏状态下直接亮屏弹出红色告警页——夜里老人跌倒，子女手机能"响起来"而不只是静默通知。
- **告警通道独立**：`fall_alert` 通道是 `IMPORTANCE_HIGH` + 闹钟音轨 + 震动；`fall_status` 通道是 `IMPORTANCE_MIN`，常驻通知不打扰。

---

## 5. 从源码重新编译

需要 JDK（8~21 均可）与 Android SDK（platform 34+、build-tools 34+）。**不需要 Gradle**，脚本直接调 `aapt2 / javac / d8 / zipalign / apksigner`：

```powershell
# 先装好 SDK，脚本默认找 D:\AndroidSdk，可自行改文件头部的 $sdk
powershell -ExecutionPolicy Bypass -File android-app\build.ps1
# 产物：android-app\dist\FallAlert-1.0.apk
```

脚本会：生成图标 → `aapt2 compile/link` → `javac`（`-source 8 -target 8`，`-bootclasspath android.jar`）→ `d8` 转 dex → 塞进 APK → `zipalign` → `apksigner` 签名。

仓库里的 `keystore.jks` 是**公开的调试签名**（口令 `android`）。它的意义是让你自己重编后能**覆盖安装**（签名一致才能升级）。**不要用它上架任何应用商店**；要上架请自己 `keytool -genkeypair` 生成新密钥。

---

## 6. 排查清单

| 现象 | 原因 / 处理 |
| --- | --- |
| 点「发送测试告警」提示发送失败 | 手机没网 / 该网络封了 443 / `ntfy.sh` 被劫持。改用 `curl https://ntfy.sh/v1/health` 验证 |
| 测试能发出去但收不到 | 通知权限没给；或 App 被 ROM 清理后台。检查「监听中」常驻通知是否还在 |
| 收到但无声音 | 告警通道被静音。设置 → 应用 → 跌倒告警 → 通知 → 告警通道 → 允许声音/横幅 |
| 延迟很久才收到 | 系统 Doze 压制后台。关闭该 App 的电池优化；或把轮询间隔 `C.POLL_MS` 调小重编 |
| 电脑端推送失败（`[warn] ntfy 推送失败`） | 电脑无法访问 `ntfy.sh`；公司网络常封。可换自建 ntfy：`NTFY_SERVER=http://your-host:8080`（App 里填同一地址） |
| 别人也会收到？ | 说明 topic 泄露或太简单。改一个随机 topic，两端同步更新 |

---

## 7. 安全与隐私说明（务必读）

1. **ntfy.sh 是公共服务，消息在第三方服务器上最多缓存 12 小时**，且默认未加密（服务端可见明文）。因此本项目**只推送"类型/区域/置信度"这类元数据，绝不推送视频帧、骨架图、人脸**。要传图请自建 ntfy 或走局域网 `PUBLIC_IMAGE_BASE`。
2. **Topic 可被猜中即被窃听**，也可被伪造。要更强安全：自建 ntfy 并开启 Access Control（[文档](https://docs.ntfy.sh/config/#access-control)），或改用 `https://ntfy.sh/<topic>?auth=...`。
3. **APK 是自签名**，无法验证"是不是官方版"。请只从本仓库 Releases 下载，核对 SHA-256（见 Release 说明）。
4. 本项目**不是医疗器械**，告警延迟最坏可达 8~15 秒，不能作为唯一的监护手段。
