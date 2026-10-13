package com.moshangssh.bandhealth.keepalive;

import android.app.Application;
import android.content.Context;
import android.content.Intent;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 让 health band sync 只要进程在，就有一个前台服务在跑。
 *
 * <p>要解决的不是「进程被杀之后怎么办」，而是「进程明明活着，却处在缓存档、随手可杀」。
 * 实测（小米 12X / HyperOS / Android 13）空闲时 {@code dumpsys activity processes} 里本包只剩
 * 系统绑定的 {@code NotificationListener}，{@code oom_adj=250}、{@code empty=true}，
 * {@code DeviceCommunicationService} 和 {@code BLEScanService} 都不在跑 —— 前台服务没了，
 * MIUI 的 camera boost 与一键清理都在这个档位上杀它。
 *
 * <p>Gadgetbridge 自己的 {@code DeviceCommunicationService} 本来就是一个前台服务：
 * {@code onCreate()} 里无条件调 {@code startForeground()}。它平时由「连接设备」这类动作拉起，
 * 空闲时没有谁去启动它。这里补的就是这一次启动：进程一创建，就把它拉起来，于是进程立刻从前台
 * 服务拿到前台档位，而不再是缓存档。
 *
 * <p>只 hook {@code Application.onCreate} 一个点，理由有两条：
 * <ul>
 *   <li>它是「进程被创建」的唯一可靠信号。系统在杀掉本包之后会重新创建进程（因为
 *       {@code NotificationListener} 还被 system 绑着），重建同样会走到这里，所以被杀之后不需要
 *       另写一个复活逻辑。</li>
 *   <li>启动服务是幂等的：服务已经在跑时，这次的 {@code onStartCommand} 会以一个没有 action 的
 *       intent 进入，打一行 {@code no action} 后返回 {@code START_STICKY}，不会重复干活。</li>
 * </ul>
 */
public class KeepAliveModule implements IXposedHookLoadPackage {

    private static final String TAG = "BandHealthKeepAlive";

    /** 只作用于这个 fork 的包名，与原版 Gadgetbridge 共存时互不干扰。 */
    private static final String TARGET_PACKAGE = "nodomain.freeyourgadget.gadgetbridge.toge";

    /**
     * 拉起的就是应用自己那个前台服务，不新增组件、不动 fork 的 manifest。
     * 模块只能启动目标应用清单里已经声明的服务，而恰好已经有一个现成的。
     */
    private static final String SERVICE_CLASS =
            "nodomain.freeyourgadget.gadgetbridge.service.DeviceCommunicationService";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        // 基类 Application 而不是 GBApplication：GBApplication.onCreate() 的两条分支
        // （首次初始化、以及 robolectric 那种二次调用）都会先调 super.onCreate()，所以挂在基类上
        // 一样会触发，同时不必假设 GBApplication 在某个版本里还覆写着这个方法。
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                startKeepAlive((Application) param.thisObject);
            }
        });
    }

    private static void startKeepAlive(Context context) {
        Intent intent = new Intent();
        intent.setClassName(TARGET_PACKAGE, SERVICE_CLASS);
        try {
            // startForegroundService，不是 startService：这个 hook 跑在进程刚创建的时候，
            // 用户界面多半不在前台，普通的后台服务启动从 Android 8 起就是 IllegalStateException。
            context.startForegroundService(intent);
            XposedBridge.log(TAG + ": started " + SERVICE_CLASS);
        } catch (IllegalStateException e) {
            // Android 12 起，后台启动前台服务可能被系统拒绝（ForegroundServiceStartNotAllowedException
            // 是 IllegalStateException 的子类）。这里不吞掉这个事实：记一行日志说明这一趟没拉起来，
            // 而不是让异常穿过 hook 把应用打成启动即崩。本包在 NotificationListener 被绑定时会拿到
            // 系统临时白名单（dumpsys 里的 SYSTEM_ALLOW_LISTED / PROC_STATE_PERSISTENT），正常路径
            // 不会走到这里。
            XposedBridge.log(TAG + ": foreground service start denied: " + e);
        }
    }
}
