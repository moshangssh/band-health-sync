# Band Health Sync 保活（LSPosed 模块）

让 `nodomain.freeyourgadget.gadgetbridge.toge` 只要进程在，就有一个前台服务在跑。

## 解决的是什么

不是「进程被杀之后怎么办」，而是「进程明明活着却处在缓存档」。

小米 12X / HyperOS / Android 13 实测：空闲时 `dumpsys activity processes` 里本包只剩系统绑定的
`NotificationListener`，`oom_adj=250`、`empty=true`，`DeviceCommunicationService` 与
`BLEScanService` 都不在跑。没有任何前台服务 → 进程是缓存档 → MIUI 的 camera boost、一键清理
随手就杀。AOSP 的 deviceidle 白名单、standby bucket、appops，以及 MIUI 省电策略
`bgControl=noRestrict` 全部放行也拦不住，原因就在这里。

## 怎么做的

Gadgetbridge 自己的 `DeviceCommunicationService` 本来就是一个前台服务 —— `onCreate()` 里无条件
调 `startForeground()`。它平时由「连接设备」这类动作拉起，空闲时没有谁去启动它。模块补的就是
这一次启动：

- 只 hook `android.app.Application#onCreate` 一个点。它是「进程被创建」的唯一可靠信号，
  系统在杀掉本包后重建进程（因为 `NotificationListener` 还被 system 绑着）同样会走到这里，
  所以不需要另写复活逻辑。
- 进程一起来就用 `startForegroundService()` 拉起 `DeviceCommunicationService`。启动是幂等的：
  服务已在跑时，这次的 `onStartCommand` 会收到一个没有 action 的 intent，打一行 `no action`
  后返回 `START_STICKY`，不会重复干活。
- 不新增组件、不改 fork 的 `AndroidManifest.xml`，因此不进 `docs/PATCHES.md` 那张要长期维护的
  上游合并清单。

## 构建

```bash
bash gradlew -p lsposed-keepalive assembleDebug
# 产物：lsposed-keepalive/build/outputs/apk/debug/bandhealth-keepalive-debug.apk
```

## 安装与启用

`adb install` 会被 MIUI 的「USB 安装限制」挡掉（`INSTALL_FAILED_USER_RESTRICTED`），走 root：

```bash
export ADB_SERVER_PORT=5038
ADB=/home/xdd/Android/sdk/platform-tools/adb
$ADB push lsposed-keepalive/build/outputs/apk/debug/bandhealth-keepalive-debug.apk /data/local/tmp/
$ADB shell su -c 'pm install -r /data/local/tmp/bandhealth-keepalive-debug.apk'
```

然后在 **LSPosed Manager → 模块** 里打开「Band Health Sync 保活」。作用域会按 APK 里的
`xposedscope` 元数据默认填成 `nodomain.freeyourgadget.gadgetbridge.toge`，确认一下即可。
启用后不需要重启手机，但目标应用要重启进程才生效。

## 验证

```bash
# 模块有没有加载
$ADB shell su -c 'grep -i BandHealthKeepAlive /data/adb/lspd/log/modules_*.log | tail'

# 进程起来之后有没有前台服务
$ADB shell dumpsys activity services nodomain.freeyourgadget.gadgetbridge.toge | grep -A2 DeviceCommunicationService

# oom_adj 有没有从前台服务拿到前台档（不再停在 250）
$ADB shell dumpsys activity processes | grep -A14 'ProcessRecord.*gadgetbridge.toge' | grep -E 'oom_adj|empty'
```
