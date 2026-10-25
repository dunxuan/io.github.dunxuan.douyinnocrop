package io.github.dunxuan.douyinnocrop;

import android.util.Log;
import android.view.View;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * 抖音不裁剪 —— 抖音视频按原始比例完整显示，不再左右裁剪。
 *
 * 范围（"只处理全屏界面"）：
 *   - 主 Feed / 沉浸式播放页全屏视频、动图、图文 → 收缩为内接矩形（或图文改 FIT）；
 *   - 清屏全屏 → 同上；
 *   - 详情图文浏览界面（描述面板可见、可捏合缩放）、搜索卡片 → 一律不触碰。
 *
 * 五条钩子路径：
 *   1. VideoItemParams.getAweme()          —— 视频/动图绑定，取宽高比收缩渲染 View；
 *   2. KeepSurfaceTextureView.onAttachedToWindow() —— attach 时序补偿；
 *   3. FeedImageViewHolder.bind(Aweme)      —— 静态图文封面（DraweeView 改 scaleType）；
 *   4. CleanModeViewModel.sA()              —— 清屏状态进出，驱动全量重算/还原；
 *   5. VideoPatchLayout.onVideoSizeChanged()—— 播放器上报真实解码宽高的权威回调。
 *
 * 现代 API（libxposed 102）特性使用情况：
 *   - 拦截器链 hook + setId + ExceptionMode.PROTECTIVE —— 所有钩子全用；
 *   - deoptimize()   —— 逐个反优化锚点，杜绝 JIT 内联绕过钩子；
 *   - detach()       —— WebView 沙箱等无关进程直接摘掉，不再收后续回调；
 *   - 热重载         —— onHotReloading 同意并交出适配状态，onHotReloaded 用
 *                       replaceHook 原子接管上代钩子（配合 module.prop 的 autoHotReload）；
 *   - 静态作用域     —— module.prop staticScope=true，作用域锁死 scope.list（仅抖音）。
 *   全程不使用任何旧版 API（de.robv.android.xposed / XposedHelpers / xposed_init 均无）。
 *
 * 依赖（均已用 droidasc 在抖音 40.6.0 上验证为未混淆的稳定名字）：
 *   - com.ss.android.ugc.aweme.feed.model.VideoItemParams#getAweme()
 *   - VideoItemParams#mAweme / #mBaseFeedPlayerView / #isDetailPagePanelShow
 *   - com.ss.android.ugc.aweme.feed.model.Aweme#getVideo() / #imageInfos
 *   - BaseFeedPlayerView#getVideoView()
 *   - com.ss.android.ugc.playerkit.videoview.KeepSurfaceTextureView#onAttachedToWindow()
 *   - FeedImageViewHolder#bind(Aweme)（封面 RemoteImageView 按字段类型定位）
 *   - CleanModeViewModel#sA(CleanModeCommand, String, boolean)
 *   - com.ss.android.videoshop.mediaview.VideoPatchLayout#onVideoSizeChanged(...)
 */
public final class NoCropModule extends XposedModule {

    static final String TAG = "DouYinNoCrop";

    private static final String TARGET_PACKAGE = "com.ss.android.ugc.aweme";
    private static final String PARAMS_CLASS =
            "com.ss.android.ugc.aweme.feed.model.VideoItemParams";
    private static final String TEXTURE_VIEW_CLASS =
            "com.ss.android.ugc.playerkit.videoview.KeepSurfaceTextureView";
    private static final String IMAGE_HOLDER_CLASS =
            "com.ss.android.ugc.aweme.feed.adapter.FeedImageViewHolder";
    private static final String CLEAN_VM_CLASS =
            "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.cleanmode.CleanModeViewModel";
    private static final String CLEAN_CMD_CLASS =
            "com.ss.android.ugc.aweme.feed.cleanmode.CleanModeCommand";
    private static final String REMOTE_IMAGE_VIEW_CLASS =
            "com.ss.android.ugc.aweme.base.ui.RemoteImageView";

    /** 本模块钩子 id 前缀。API 102：同 id 再安装即原子替换；热重载时用它区分
     *  "本代要接管的钩子"与"上代已废弃、该卸掉的钩子"（图片 bind 的 id 含类名，
     *  因此用前缀匹配而非枚举）。 */
    private static final String HOOK_ID_PREFIX = "nocrop-";

    /** 每个 ClassLoader 只安装一次（进程可能加载多个包） */
    private static final Map<ClassLoader, Boolean> INSTALLED = new WeakHashMap<>();

    /** 首次安装时没有"上代钩子"可接管。 */
    private static final List<HookHandle> HOOKS_NONE = Collections.emptyList();

    /** 本模块所处进程名：onModuleLoaded 记录，供 detach 判据使用
     *  （PackageLoadedParam/PackageReadyParam 不带进程名）。 */
    private volatile String processName;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, TAG, "loaded in " + processName
                + ", framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + " (" + getFrameworkVersionCode() + ")"
                + ", api=" + getApiVersion()
                + ", props=0x" + Long.toHexString(getFrameworkProperties()));
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (detachIfIrrelevant()) {
            return;
        }
        if (!TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        synchronized (INSTALLED) {
            if (INSTALLED.containsKey(param.getClassLoader())) {
                log(Log.DEBUG, TAG, "hooks already installed");
                return;
            }
            INSTALLED.put(param.getClassLoader(), Boolean.TRUE);
        }
        FitApplier.init(param.getClassLoader());
        installVideoBindHook(param.getClassLoader(), HOOKS_NONE);
        installTextureAttachHook(param.getClassLoader(), HOOKS_NONE);
        installImageHolderHook(param.getClassLoader(), HOOKS_NONE);
        installCleanModeHook(param.getClassLoader(), HOOKS_NONE);
        installPlayerSizeHook(param.getClassLoader(), HOOKS_NONE);
        log(Log.INFO, TAG, "hooks installed for " + param.getPackageName());
    }

    // ------------------------------------------------------- 进程过滤 (detach)

    /**
     * 把模块从不可能出现目标界面的进程里摘掉（API 102 detach）：摘掉后不再收到
     * 任何生命周期回调，省掉无谓的反射安装。
     *
     * 判据保守——只摘两类：
     *   1) WebView 沙箱进程（名字含 sandboxed，永远不会跑 Feed 界面）；
     *   2) 进程名不属于抖音的（静态作用域下不应出现，纯防御）。
     * 抖音自己的子进程（:push 等）一律保留：无法静态断定它不承载界面，
     * 宁可多装也不漏装。返回 true 表示已摘掉、本次回调应直接返回。
     */
    private boolean detachIfIrrelevant() {
        String proc = processName;
        if (proc == null || proc.isEmpty()) {
            return false; // 拿不到进程名就不摘
        }
        boolean ours = proc.equals(TARGET_PACKAGE) || proc.startsWith(TARGET_PACKAGE + ":");
        if (ours && !proc.contains("sandboxed")) {
            return false;
        }
        try {
            detach();
            log(Log.INFO, TAG, "detached from irrelevant process " + proc);
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "detach failed in " + proc + ", keep going: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------ 钩子安装

    /**
     * Hook VideoItemParams.getAweme()：
     * 1) 绑定/换视频时取到 Aweme → 提取宽高比（视频字段优先，图文回退 imageInfos）；
     * 2) 顺手通过 params.mBaseFeedPlayerView.getVideoView() 拿到渲染 View 并适配；
     * 3) 读不到宽高比时释放该 View（还原 + 忘记旧比例），绝不套用旧内容的比例。
     */
    private void installVideoBindHook(ClassLoader loader, List<HookHandle> olds) {
        try {
            Class<?> paramsCls = Class.forName(PARAMS_CLASS, false, loader);
            final Method getAweme = paramsCls.getDeclaredMethod("getAweme");
            final Field awemeField = paramsCls.getField("mAweme");
            final Field playerField = paramsCls.getField("mBaseFeedPlayerView");
            deopt("VideoItemParams.getAweme()", getAweme);

            Hooker hooker = chain -> {
                Object result = chain.proceed();
                try {
                    Object params = chain.getThisObject();
                    Object aweme = result != null ? result : awemeField.get(params);
                    if (aweme == null) {
                        return result; // 瞬时解绑：保持现状
                    }
                    float aspect = VideoAspect.read(aweme);
                    boolean imagePost = VideoAspect.isImagePost(aweme);
                    Object player = playerField.get(params);
                    View view = resolveVideoView(player);
                    if (aspect > 0f) {
                        // player 一并记录：attach 补偿时验证"该播放器的渲染 View"
                        // 才是本 View，杜绝评论区 TextureView 误吃 pending
                        FitApplier.apply(view, new FitApplier.FitState(
                                aspect, imagePost, params, false,
                                VideoAspect.aid(aweme), player));
                    } else {
                        FitApplier.release(view);
                    }
                } catch (Throwable t) {
                    log(Log.DEBUG, TAG, "bind-time apply skipped: " + t);
                }
                return result;
            };
            boolean replaced = installOrReplace("nocrop-video-bind", getAweme, hooker, olds);
            log(Log.INFO, TAG, (replaced ? "replaced" : "hooked")
                    + " VideoItemParams.getAweme");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "failed to hook VideoItemParams.getAweme", t);
        }
    }

    /**
     * Hook KeepSurfaceTextureView.onAttachedToWindow()：
     * 覆盖"attach 晚于最后一次 getAweme"的时序；只用该 View 自己记录的
     * 状态或最近一次视频 pending，不碰全局比例。幂等。
     */
    private void installTextureAttachHook(ClassLoader loader, List<HookHandle> olds) {
        try {
            Class<?> viewCls = Class.forName(TEXTURE_VIEW_CLASS, false, loader);
            final Method onAttach = viewCls.getDeclaredMethod("onAttachedToWindow");
            deopt("KeepSurfaceTextureView.onAttachedToWindow()", onAttach);

            Hooker hooker = chain -> {
                Object r = chain.proceed();
                try {
                    FitApplier.applyOnAttach((View) chain.getThisObject());
                } catch (Throwable t) {
                    log(Log.DEBUG, TAG, "attach-time apply skipped: " + t);
                }
                return r;
            };
            boolean replaced = installOrReplace("nocrop-texture-attach", onAttach, hooker, olds);
            log(Log.INFO, TAG, (replaced ? "replaced" : "hooked")
                    + " KeepSurfaceTextureView.onAttachedToWindow");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "failed to hook KeepSurfaceTextureView", t);
        }
    }

    /**
     * Hook FeedImageViewHolder.bind(Aweme) 及子类重载（FullFeed/Detail 继承或重写）：
     * 静态图文封面是 Fresco DraweeView，改 scaleType 而非 LayoutParams——
     * 不破坏捏合缩放，多图切换由 Fresco 自动处理。封面 View 按字段类型
     * （RemoteImageView）定位，不依赖混淆字段名。面板/详情/清屏门控在 fit() 内统一执行。
     */
    private void installImageHolderHook(ClassLoader loader, List<HookHandle> olds) {
        String[] holderNames = {
                IMAGE_HOLDER_CLASS,
                "com.ss.android.ugc.aweme.feed.adapter.FullFeedImageViewHolder",
                "com.ss.android.ugc.aweme.detail.ui.DetailFeedImageViewHolder",
        };
        Class<?> awemeCls;
        Class<?> paramsCls;
        Class<?> rivCls;
        try {
            awemeCls = Class.forName("com.ss.android.ugc.aweme.feed.model.Aweme", false, loader);
            paramsCls = Class.forName(PARAMS_CLASS, false, loader);
            rivCls = Class.forName(REMOTE_IMAGE_VIEW_CLASS, false, loader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "failed to resolve image holder deps", t);
            return;
        }
        int hooked = 0;
        for (String holderName : holderNames) {
            try {
                Class<?> holderCls = Class.forName(holderName, false, loader);
                hooked += hookImageBind(holderCls, awemeCls, paramsCls, rivCls, olds);
            } catch (Throwable t) {
                log(Log.DEBUG, TAG, "image holder not hookable: " + holderName + " (" + t + ")");
            }
        }
        if (hooked > 0) {
            log(Log.INFO, TAG, "hooked image holder binds: " + hooked);
        }
    }

    /** 对单个 holder 类尝试 hook 其声明的 bind 重载；返回实际 hook 数。 */
    private int hookImageBind(Class<?> holderCls, Class<?> awemeCls,
                              Class<?> paramsCls, Class<?> rivCls, List<HookHandle> olds) {
        int count = 0;
        Class<?>[][] sigs = {
                {awemeCls},
                {awemeCls, int.class},
        };
        for (Class<?>[] sig : sigs) {
            Method bind;
            try {
                bind = holderCls.getDeclaredMethod("bind", sig);
            } catch (Throwable ignored) {
                continue; // 该类未声明此重载（继承的由父类 hook 覆盖）
            }
            deopt(holderCls.getSimpleName() + ".bind(" + sig.length + ")", bind);
            Hooker hooker = chain -> {
                Object result = chain.proceed();
                try {
                    Object holder = chain.getThisObject();
                    Object aweme = chain.getArgs().get(0);
                    if (aweme != null && VideoAspect.isImagePost(aweme)) {
                        Object coverObj = findFieldOfType(holder, rivCls, null);
                        Object params = findFieldOfType(holder, paramsCls, null);
                        if (coverObj instanceof View) {
                            FitApplier.apply((View) coverObj, new FitApplier.FitState(
                                    VideoAspect.read(aweme), true, params, true,
                                    VideoAspect.aid(aweme)));
                        } else {
                            log(Log.DEBUG, TAG, "image holder: cover view not found");
                        }
                    }
                } catch (Throwable t) {
                    log(Log.DEBUG, TAG, "image bind apply skipped: " + t);
                }
                return result;
            };
            String id = "nocrop-image-bind-" + holderCls.getSimpleName() + "-" + sig.length;
            installOrReplace(id, bind, hooker, olds);
            count++;
        }
        return count;
    }

    /**
     * Hook CleanModeViewModel.sA(CleanModeCommand, String, boolean)：
     * 清屏进入/退出的状态机入口（详情与 Feed 共用该 ViewModel 类）。
     * sA 执行后直接读该 VM 的活跃来源列表 a（LinkedList，语义名）的空否——
     * 这是状态机真值，避免镜像命令在守卫分支下漂移。
     * 任一 VM 活跃 = 清屏中；随后全量重算：进清屏 → 图文收缩，退清屏 → 面板门控还原。
     */
    private void installCleanModeHook(ClassLoader loader, List<HookHandle> olds) {
        try {
            Class<?> vmCls = Class.forName(CLEAN_VM_CLASS, false, loader);
            final Method sA = vmCls.getDeclaredMethod("sA",
                    Class.forName(CLEAN_CMD_CLASS, false, loader),
                    String.class, boolean.class);
            // CleanModeViewModel.a：活跃清屏来源列表（LinkedList，空 = 非清屏）
            final Field activeList = vmCls.getField("a");
            deopt("CleanModeViewModel.sA()", sA);

            Hooker hooker = chain -> {
                Object r = chain.proceed();
                try {
                    Object vm = chain.getThisObject();
                    Object list = activeList.get(vm);
                    boolean active = (list instanceof java.util.Collection)
                            && !((java.util.Collection<?>) list).isEmpty();
                    FitApplier.noteCleanVm(vm, active);
                    FitApplier.refitAll();
                } catch (Throwable t) {
                    log(Log.DEBUG, TAG, "clean-mode refit skipped: " + t);
                }
                return r;
            };
            boolean replaced = installOrReplace("nocrop-clean-mode", sA, hooker, olds);
            log(Log.INFO, TAG, (replaced ? "replaced" : "hooked") + " CleanModeViewModel.sA");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "failed to hook CleanModeViewModel.sA", t);
        }
    }

    /**
     * Hook VideoPatchLayout.onVideoSizeChanged(VideoStateInquirer, PlayEntity, int, int)：
     * 播放器上报**真实解码宽高**的权威回调（Feed 与详情页共用 videoshop 链路，
     * LayerHostMediaLayout 继承并 super 转发到此类）。
     * Aweme 字段可能给的是封面/海报尺寸、多图帖的 imageInfos[0] 也可能不是当前显示的图
     * —— 此前图文界面动图"退出放大后变窄、比例错误"的根因就是错误比例被写进绝对像素。
     * 回调携带的 (width, height) 覆盖猜测值并立即重算；aid 记账保证内容切换后旧值作废。
     * 参数顺序 sanity：若 w/h 比例越界则交换（防个别实现顺序相反）。
     */
    private void installPlayerSizeHook(ClassLoader loader, List<HookHandle> olds) {
        try {
            Class<?> layoutCls = Class.forName(
                    "com.ss.android.videoshop.mediaview.VideoPatchLayout", false, loader);
            Class<?> inquirerCls = Class.forName(
                    "com.ss.android.videoshop.api.VideoStateInquirer", false, loader);
            Class<?> entityCls = Class.forName(
                    "com.ss.android.videoshop.entity.PlayEntity", false, loader);
            final Method m = layoutCls.getDeclaredMethod(
                    "onVideoSizeChanged", inquirerCls, entityCls, int.class, int.class);
            deopt("VideoPatchLayout.onVideoSizeChanged()", m);

            Hooker hooker = chain -> {
                Object r = chain.proceed();
                try {
                    java.util.List<?> args = chain.getArgs();
                    int w = (Integer) args.get(2);
                    int h = (Integer) args.get(3);
                    if (w > 0 && h > 0) {
                        float aspect = (float) w / (float) h;
                        if (aspect < 0.1f || aspect > 20f) {
                            int t = w;
                            w = h;
                            h = t; // 个别实现顺序相反，交换后重验
                            aspect = (float) w / (float) h;
                        }
                        if (aspect >= 0.1f && aspect <= 20f
                                && chain.getThisObject() instanceof View) {
                            FitApplier.onPlayerLayoutSize(
                                    (View) chain.getThisObject(), w, h);
                        }
                    }
                } catch (Throwable t) {
                    log(Log.DEBUG, TAG, "player-size apply skipped: " + t);
                }
                return r;
            };
            boolean replaced = installOrReplace("nocrop-player-size", m, hooker, olds);
            log(Log.INFO, TAG, (replaced ? "replaced" : "hooked")
                    + " VideoPatchLayout.onVideoSizeChanged");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "failed to hook VideoPatchLayout.onVideoSizeChanged", t);
        }
    }

    /**
     * 安装钩子；若热重载场景下已有同 id 的上代钩子，则用 replaceHook 原子替换
     * （新旧钩子之间不留空档，调用中的链快照不受影响）。返回是否走了替换路径。
     */
    private boolean installOrReplace(String id, Executable target, Hooker hooker,
                                     List<HookHandle> olds) {
        if (olds != null) {
            for (HookHandle old : olds) {
                if (old == null || !id.equals(old.getId())) {
                    continue;
                }
                try {
                    old.replaceHook(hooker);
                    return true;
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "replaceHook failed for " + id
                            + ", fallback to hook(): " + t);
                    break;
                }
            }
        }
        hook(target)
                .setId(id)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(hooker);
        return false;
    }

    /**
     * 反优化锚点方法（API 101+ 的 deoptimize）：JIT 会把小方法内联进调用点，
     * 被内联的调用不走方法入口 → 绕过钩子。反优化让方法退出内联路径，
     * 保证钩子稳定触发（抖音 Feed 这类高频小 getter 尤其需要）。
     */
    private void deopt(String what, Executable exe) {
        try {
            boolean ok = deoptimize(exe);
            log(Log.INFO, TAG, "deoptimize " + what + " = " + ok);
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "deoptimize " + what + " skipped: " + t);
        }
    }

    // ------------------------------------------------------------ 热重载 (API 102)

    /**
     * 热重载前回调（旧代码执行）：返回 true 表示同意换代。
     *
     * 这里做两件事：
     *   1) 让本代退役——摘掉容器布局监听并拒绝后续监听回调，防止旧代码在换代后
     *      继续写布局、和新代打架（钩子本身在本回调前已被框架冻结）；
     *   2) 把适配状态打包交出去。状态只含「宿主 View / 宿主对象 + 基本类型/String」——
     *      setSavedInstanceState 不允许携带旧模块类加载器创建的对象，
     *      FitState/WeakReference/匿名监听器等模块类一律不能过桥。
     * 交接失败也照样同意热重载，只是新代从零开始记账。
     */
    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        log(Log.INFO, TAG, "hot reloading accepted in " + processName);
        try {
            HashMap<String, Object> state = FitApplier.exportAndRetireSync();
            param.setSavedInstanceState(state);
            int views = (state.get("views") instanceof HashMap)
                    ? ((HashMap<?, ?>) state.get("views")).size() : 0;
            log(Log.INFO, TAG, "hot reload state handed over: " + views + " view(s)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hand over state failed (reload continues): " + t);
        }
        return true;
    }

    /**
     * 热重载完成回调（新代码执行）。框架**不会**为新代重放任何生命周期回调，
     * 所以接管动作全在这里做：
     *   1) 从上代钩子里恢复宿主 ClassLoader（正常安装时 onPackageReady 给的就是它），
     *      并重跑 FitApplier.init()（Fresco ScaleType 类要按包 ClassLoader 解析）；
     *   2) 所有钩子按同 id 原子替换为本代实现；
     *   3) 上代遗留、本代不再拥有的钩子（改版删掉的锚点）卸掉，避免新旧两套并存；
     *   4) 导入上代交接的适配状态，恢复记账 + 幂等重算。
     * 恢复不出 ClassLoader 时保留上代钩子继续跑（旧代码仍能工作），只记错误。
     */
    @Override
    public void onHotReloaded(HotReloadedParam param) {
        List<HookHandle> olds = param.getOldHookHandles();
        int oldCount = (olds != null) ? olds.size() : 0;
        log(Log.INFO, TAG, "hot reloaded in " + param.getProcessName()
                + ", old hooks=" + oldCount);
        if (oldCount == 0) {
            return; // 本进程没装过钩子：没有可接管的东西
        }
        ClassLoader loader = recoverClassLoader(olds);
        if (loader == null) {
            log(Log.ERROR, TAG, "cannot recover host ClassLoader from old hooks;"
                    + " keep previous hooks running");
            return;
        }
        synchronized (INSTALLED) {
            INSTALLED.put(loader, Boolean.TRUE);
        }
        FitApplier.init(loader);
        installVideoBindHook(loader, olds);
        installTextureAttachHook(loader, olds);
        installImageHolderHook(loader, olds);
        installCleanModeHook(loader, olds);
        installPlayerSizeHook(loader, olds);

        // 本代不再拥有的上代钩子（本次改版删除的锚点）→ 卸载
        for (HookHandle h : olds) {
            String id = h.getId();
            if (id != null && id.startsWith(HOOK_ID_PREFIX)) {
                continue;
            }
            try {
                h.unhook();
                log(Log.INFO, TAG, "unhooked stale hook " + id);
            } catch (Throwable t) {
                log(Log.WARN, TAG, "unhook stale hook " + id + " failed: " + t);
            }
        }

        // 状态交接：上一代已适配的 View 交回本代继续记账
        Object saved = param.getSavedInstanceState();
        if (saved instanceof HashMap) {
            @SuppressWarnings("unchecked")
            HashMap<String, Object> state = (HashMap<String, Object>) saved;
            FitApplier.importState(state);
        } else {
            log(Log.INFO, TAG, "no saved state from previous generation");
        }
    }

    /** 从上代钩子的锚点反推宿主 ClassLoader（优先主锚点，回退任意非 boot 锚点）。 */
    private static ClassLoader recoverClassLoader(List<HookHandle> olds) {
        for (HookHandle h : olds) {
            try {
                Executable e = h.getExecutable();
                Class<?> declaring = (e != null) ? e.getDeclaringClass() : null;
                if (declaring != null && PARAMS_CLASS.equals(declaring.getName())) {
                    return declaring.getClassLoader();
                }
            } catch (Throwable ignored) {
                // 单个句柄异常不影响其余
            }
        }
        for (HookHandle h : olds) {
            try {
                ClassLoader cl = h.getExecutable().getDeclaringClass().getClassLoader();
                if (cl != null) {
                    return cl; // boot 类返回 null（如 android.app.Activity），跳过
                }
            } catch (Throwable ignored) {
                // 同上
            }
        }
        return null;
    }

    /** 按字段类型在对象（含父类）上找第一个匹配字段并取值。找不到返回 fallback。 */
    private static Object findFieldOfType(Object obj, Class<?> type, Object fallback) {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        return f.get(obj);
                    } catch (Throwable ignored) {
                        // 继续找下一个
                    }
                }
            }
        }
        return fallback;
    }

    /** playerView（BaseFeedPlayerView 子类）→ 真正的渲染 View（TextureView 或 SurfaceView 容器）。 */
    private static View resolveVideoView(Object playerView) {
        if (playerView == null) {
            return null;
        }
        try {
            Method getter = playerView.getClass().getMethod("getVideoView");
            Object v = getter.invoke(playerView);
            return (v instanceof View) ? (View) v : null;
        } catch (Throwable ignored) {
            // 不同 holder 子类可能没有该方法（如空播放器），跳过即可
            return null;
        }
    }
}
