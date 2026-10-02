package com.cashcraft.ads.mediation.smoke;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.cashcraft.ads.mediation.admob.AdMobAds;
import com.cashcraft.ads.mediation.admob.AdMobNextGenBidPrice;
import com.cashcraft.ads.mediation.admob.AdMobState;
import com.cashcraft.ads.mediation.internal.nativeads.DefaultNativeLayoutKt;
import com.cashcraft.ads.mediation.internal.AdLifecycleMonitor;
import com.google.android.libraries.ads.mobile.sdk.MobileAds;
import com.google.android.libraries.ads.mobile.sdk.common.AdValue;
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError;
import com.google.android.libraries.ads.mobile.sdk.common.PreloadCallback;
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration;
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo;
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView;
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd;
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdEventCallback;
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoadResult;
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdPreloader;
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest;
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

/** SDK 调用与核心取价均在目标 APK 内接受 R8；测试 APK 仅通过这个诊断入口驱动。 */
public final class NativePreloadProbeAccess {
    private static final String ID = "native-r8-probe";
    private static final ArrayList<NativeAd> owned = new ArrayList<>();
    private static final ArrayList<NativeAdView> ownedViews = new ArrayList<>();
    private static final AtomicInteger loaded = new AtomicInteger();
    private static final AtomicInteger impressions = new AtomicInteger();
    private static final AtomicInteger paid = new AtomicInteger();
    private static final AtomicInteger clicks = new AtomicInteger();
    private static final AtomicInteger unknownPrices = new AtomicInteger();
    private static final AtomicInteger zeroPrices = new AtomicInteger();
    private static final AtomicInteger positivePrices = new AtomicInteger();
    private static final AtomicInteger backgrounds = new AtomicInteger();
    private static final AdLifecycleMonitor.Listener lifecycleObserver = new AdLifecycleMonitor.Listener() {
        @Override public void onAppEnteredBackground() { backgrounds.incrementAndGet(); }
    };

    private static volatile String lastQuotedResponseId;
    private static volatile Object lastQuotedConfig;
    private static volatile Double lastQuotedPrice;
    private static volatile NativeAdView lastRenderedView;
    private static volatile MediaView lastRenderedMedia;
    private static volatile String failure;

    private static com.cashcraft.ads.mediation.internal.nativeads.AdMobNativeInventory managed;
    private static com.cashcraft.ads.mediation.internal.nativeads.NativeAdHandle managedAd;
    private static long managedDeadline;

    public static void startManaged() {
        managed = new com.cashcraft.ads.mediation.internal.nativeads.AdMobNativeInventory(
                "native-managed-r8", "ca-app-pub-3940256099942544/2247696110");
        managedDeadline = android.os.SystemClock.elapsedRealtime() + 3600000L;
        managed.prepare(managedDeadline, ready -> kotlin.Unit.INSTANCE);
    }

    public static boolean managedReady() { return managed != null && managed.available(); }

    public static void takeManaged() {
        String identity = managed.peekIdentity();
        managedAd = managed.take(new com.cashcraft.ads.mediation.internal.nativeads.NativeCallbacks() {
            @Override public void loaded(com.cashcraft.ads.mediation.internal.nativeads.NativeAdHandle ad) {}
            @Override public void failed(String reason, String code) { throw new AssertionError(reason); }
            @Override public void impression(String source, String response) {}
            @Override public void clicked(String source, String response) {}
            @Override public void closed() {}
            @Override public void overlayOpened() {}
            @Override public void overlayClosed() {}
            @Override public void paid(com.cashcraft.ads.mediation.internal.nativeads.NativeRevenue revenue) {}
        });
        check(managedAd != null, "受管理库存取货为空");
        check(identity != null && identity.equals(managedAd.getResponseId()), "受管理库存身份不一致");
        check(managedAd.getExpiresAtMillis() == managedDeadline, "领取改变会话期限");
    }

    public static void closeManaged() {
        if (managed != null) managed.close();
        if (managedAd != null) managedAd.destroy();
        managed = null;
        managedAd = null;
    }

    private NativePreloadProbeAccess() {}

    public static boolean isReady() {
        check(AdMobAds.INSTANCE.getState() != AdMobState.FAILED, "AdMob 初始化失败");
        return AdMobAds.INSTANCE.getState() == AdMobState.READY;
    }


    public static void observeLifecycle() {
        backgrounds.set(0);
        AdLifecycleMonitor.INSTANCE.addListener(lifecycleObserver);
    }

    public static boolean isAppInForeground() {
        return AdLifecycleMonitor.INSTANCE.isAppInForeground();
    }

    public static int getBackgroundCount() {
        return backgrounds.get();
    }

    public static void start(Context context) {
        var version = MobileAds.getVersion();
        check(version.getMajorVersion() == 1 && version.getMinorVersion() == 2 && version.getMicroVersion() == 1, "版本须为 1.2.1");
        AdMobNextGenBidPrice.INSTANCE.initialize(context);
        loaded.set(0);
        failure = null;
        var request = new NativeAdRequest.Builder("ca-app-pub-3940256099942544/2247696110",
                Collections.singletonList(NativeAd.NativeAdType.NATIVE)).build();
        check(NativeAdPreloader.start(ID, new PreloadConfiguration(request, 1), new PreloadCallback() {
            @Override public void onAdPreloaded(String id, ResponseInfo info) { loaded.incrementAndGet(); }
            @Override public void onAdFailedToPreload(String id, LoadAdError error) { failure = "预加载失败 code=" + error.getCode(); }
            @Override public void onAdsExhausted(String id) {}
        }), "预加载未启动");
    }

    public static boolean available(int callbacks) {
        check(failure == null, failure);
        return NativeAdPreloader.getNumAdsAvailable(ID) == 1 && loaded.get() >= callbacks;
    }

    public static void checkPeekAndQuote() {
        var first = NativeAdPreloader.peekAdResponseInfo(ID);
        check(first != null && first.getResponseId() != null, "队首身份缺失");
        for (int i = 0; i < 3; i++) {
            var current = NativeAdPreloader.peekAdResponseInfo(ID);
            check(current != null && first.getResponseId().equals(current.getResponseId()), "peek 改变身份");
            check(NativeAdPreloader.getNumAdsAvailable(ID) == 1, "peek 消费库存");
        }
        Quote quote = reflectQueueHead(ID);
        check(first.getResponseId().equals(quote.responseId), "反射队首身份与公开 peek 不一致");
        lastQuotedResponseId = quote.responseId;
        lastQuotedConfig = quote.config;
        lastQuotedPrice = quote.usd;
    }
    public static void checkNewSessionQuote() {
        String previousResponseId = lastQuotedResponseId;
        check(previousResponseId != null, "上一代队首身份缺失");
        checkPeekAndQuote();
        check(!previousResponseId.equals(lastQuotedResponseId), "关闭重建后混入上一代队首");
    }


    public static void checkSurvivesActivityADestruction() {
        check(NativeAdPreloader.getNumAdsAvailable(ID) == 1, "Activity A 销毁后预加载库存丢失");
        var peekAfter = NativeAdPreloader.peekAdResponseInfo(ID);
        check(peekAfter != null && peekAfter.getResponseId() != null, "Activity A 销毁后队首身份缺失");
        check(lastQuotedResponseId != null && lastQuotedResponseId.equals(peekAfter.getResponseId()),
                "Activity A 销毁后身份改变: expected=" + lastQuotedResponseId + " actual=" + peekAfter.getResponseId());
    }

    public static void pollAndRenderInActivity(Activity activity) {
        check(activity != null && !activity.isDestroyed() && !activity.isFinishing(), "Activity B 无效");

        NativeAd ad = takeQuoted();

        impressions.set(0);
        paid.set(0);
        clicks.set(0);

        ad.setAdEventCallback(new NativeAdEventCallback() {
            @Override public void onAdImpression() { impressions.incrementAndGet(); }
            @Override public void onAdPaid(AdValue adValue) { paid.incrementAndGet(); }
            @Override public void onAdClicked() { clicks.incrementAndGet(); }
            @Override public void onAdSwipeGestureClicked() { clicks.incrementAndGet(); }
        });

        NativeAdView adView = new NativeAdView(activity);
        ownedViews.add(adView);

        // 与 Debug 转移探针共用库内默认布局，不另建缺少披露或依赖主题文字色的广告卡片。
        var binding = DefaultNativeLayoutKt.createDefaultNativeLayout(activity);
        check(ad.getHeadline() != null && !ad.getHeadline().isBlank(), "真实标题缺失");
        check(ad.getCallToAction() != null && !ad.getCallToAction().isBlank(), "真实 CTA 缺失");
        binding.getHeadline().setText(ad.getHeadline());
        binding.getCallToAction().setText(ad.getCallToAction());
        binding.getBody().setText(ad.getBody());
        binding.getBody().setVisibility(ad.getBody() == null ? View.GONE : View.VISIBLE);
        binding.getAdvertiser().setText(ad.getAdvertiser());
        binding.getAdvertiser().setVisibility(ad.getAdvertiser() == null ? View.GONE : View.VISIBLE);
        adView.setHeadlineView(binding.getHeadline());
        adView.setCallToActionView(binding.getCallToAction());
        adView.setBodyView(binding.getBody());
        adView.setAdvertiserView(binding.getAdvertiser());

        if (ad.getIcon() != null && ad.getIcon().getDrawable() != null) {
            ImageView iconView = new ImageView(activity);
            iconView.setImageDrawable(ad.getIcon().getDrawable());
            binding.getIcon().addView(iconView, new ViewGroup.LayoutParams(-1, -1));
            adView.setIconView(iconView);
        } else {
            binding.getIcon().setVisibility(View.GONE);
        }
        MediaView mediaView = new MediaView(activity);
        binding.getMedia().addView(mediaView, new ViewGroup.LayoutParams(-1, -1));

        adView.addView(binding.getRoot(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        adView.registerNativeAd(ad, mediaView);

        FrameLayout root = new FrameLayout(activity);
        // API 36 的系统栏和宿主原生 ActionBar 不得盖住默认布局的广告披露。
        if (activity.getActionBar() != null) activity.getActionBar().hide();
        root.setFitsSystemWindows(true);
        root.addView(adView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        activity.setContentView(root);

        lastRenderedView = adView;
        lastRenderedMedia = mediaView;
    }

    public static boolean isRenderedViewVisible() {
        return lastRenderedView != null && lastRenderedView.isShown();
    }

    public static int getImpressionCount() {
        return impressions.get();
    }

    public static int getPaidCount() {
        return paid.get();
    }

    public static int getClickCount() {
        return clicks.get();
    }

    public static void verifyRenderedAdExposure() {
        check(lastRenderedView != null, "未渲染 NativeAdView");
        check(lastRenderedView.isShown(), "NativeAdView 未显示");
        check(lastRenderedMedia != null && lastRenderedMedia.getChildCount() > 0, "SDK 未填充媒体子 View");
        check(impressions.get() > 0, "SDK 未触发真实曝光");
        check(clicks.get() == 0, "探针不得点击广告");
    }

    public static int getUnknownPriceCount() {
        return unknownPrices.get();
    }

    public static int getZeroPriceCount() {
        return zeroPrices.get();
    }

    public static int getPositivePriceCount() {
        return positivePrices.get();
    }

    public static boolean isNonzeroPriceVerified() {
        return positivePrices.get() > 0;
    }

    public static String getPriceBoundarySummary() {
        return "prices: unknown=" + unknownPrices.get() + ", zero=" + zeroPrices.get() +
                ", positive=" + positivePrices.get() + ", nonzeroVerified=" + (positivePrices.get() > 0);
    }

    public static void checkPeekAndPoll() {
        checkPeekAndQuote();
        takeQuoted();
    }

    private static NativeAd takeQuoted() {
        var result = NativeAdPreloader.pollAd(ID);
        check(result instanceof NativeAdLoadResult.NativeAdSuccess, "有库存却取货失败");
        var ad = ((NativeAdLoadResult.NativeAdSuccess) result).getAd();
        owned.add(ad);
        check(lastQuotedResponseId != null && lastQuotedResponseId.equals(ad.getResponseInfo().getResponseId()),
                "领取身份变化");
        try {
            check(lastQuotedConfig == getField(getField(ad, "a"), "b"), "报价配置与领取对象配置不一致");
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("领取对象配置匹配失败", error);
        }
        Double price = AdMobNextGenBidPrice.INSTANCE.fromNative(ad);
        recordPrice(price);
        check(price != null && lastQuotedPrice != null && price.equals(lastQuotedPrice), "报价与实际对象价格不一致");
        return ad;
    }

    public static void close() {
        check(NativeAdPreloader.destroy(ID), "预加载 key 未关闭");
        checkEmpty();
    }

    public static void checkEmpty() {
        check(NativeAdPreloader.getNumAdsAvailable(ID) == 0, "关闭后库存非空");
        check(NativeAdPreloader.peekAdResponseInfo(ID) == null, "关闭后身份未清空");
        var result = NativeAdPreloader.pollAd(ID);
        if (result instanceof NativeAdLoadResult.NativeAdSuccess) {
            owned.add(((NativeAdLoadResult.NativeAdSuccess) result).getAd());
            throw new AssertionError("关闭后仍能取货");
        }
    }

    public static void cleanup() {
        AdLifecycleMonitor.INSTANCE.removeListener(lifecycleObserver);
        Throwable error = null;
        try { NativeAdPreloader.destroy(ID); } catch (Throwable next) { error = next; }
        for (NativeAd ad : owned) {
            try {
                ad.setAdEventCallback(null);
            } catch (Throwable next) {
                if (error == null) error = next; else error.addSuppressed(next);
            }
        }
        for (NativeAdView view : ownedViews) {
            try {
                if (view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
                view.destroy();
            } catch (Throwable next) {
                if (error == null) error = next; else error.addSuppressed(next);
            }
        }
        ownedViews.clear();
        for (NativeAd ad : owned) {
            try { ad.destroy(); } catch (Throwable next) {
                if (error == null) error = next; else error.addSuppressed(next);
            }
        }
        owned.clear();
        lastRenderedView = null;
        lastRenderedMedia = null;
        lastQuotedResponseId = null;
        lastQuotedConfig = null;
        lastQuotedPrice = null;
        if (error != null) throw new AssertionError("探针清理失败", error);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void recordPrice(Double price) {
        if (price == null) {
            unknownPrices.incrementAndGet();
        } else {
            check(price >= 0.0 && Double.isFinite(price), "价格必须非负且有限: " + price);
            if (price == 0.0) {
                zeroPrices.incrementAndGet();
            } else {
                positivePrices.incrementAndGet();
            }
        }
    }

    private static Quote reflectQueueHead(String preloadId) {
        try {
            Class<?> rootClass = Class.forName("ads_mobile_sdk.gt0");
            Method rootMethod = rootClass.getDeclaredMethod("a");
            rootMethod.setAccessible(true);
            Object service = rootMethod.invoke(null);
            check(service != null, "gt0.a() 返回 null");

            Object provider = getField(service, "P0");
            Method getMethod = provider.getClass().getMethod("get");
            getMethod.setAccessible(true);
            Object registry = getMethod.invoke(provider);
            check(registry != null, "registry 为 null");

            Map<?, ?> map = findManagerMap(registry, preloadId);
            check(map != null, "未找到包含 preloadId 的 map: " + preloadId);

            Object manager = map.get(preloadId);
            check(manager != null, "manager 为 null: " + preloadId);

            Object queueObj = getField(manager, "B");
            check(queueObj instanceof Queue, "Queue 字段 B 类型不符");
            Queue<?> queue = (Queue<?>) queueObj;
            Object item = queue.peek();
            check(item != null, "队首 item 为 null");

            Object itemResult = getField(item, "a");
            Object internalAd = getField(itemResult, "a");

            Method getResponseInfo = internalAd.getClass().getMethod("getResponseInfo");
            getResponseInfo.setAccessible(true);
            ResponseInfo responseInfo = (ResponseInfo) getResponseInfo.invoke(internalAd);
            String responseId = responseInfo != null ? responseInfo.getResponseId() : null;
            check(responseId != null, "反射 responseId 为 null");

            Method configMethod = internalAd.getClass().getMethod("b");
            configMethod.setAccessible(true);
            Object config = configMethod.invoke(internalAd);
            check(config != null, "internalAd configuration 为 null");

            Object priceObj = getField(config, "m");
            check(priceObj != null, "config.m 为 null");
            long micros = ((Number) getField(priceObj, "b")).longValue();
            String currency = (String) getField(priceObj, "d");
            check("USD".equals(currency), "报价货币非 USD: " + currency);
            check(micros >= 0, "报价 micros 负数: " + micros);
            double usd = micros / 1_000_000.0;

            return new Quote(config, responseId, usd);
        } catch (AssertionError e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError("锁定版本队列反射失败", t);
        }
    }


    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
            current = current.getSuperclass();
        }
        return null;
    }

    private static Object getField(Object target, String name) throws IllegalAccessException {
        Field f = findField(target.getClass(), name);
        if (f == null) {
            throw new NoSuchFieldError(target.getClass().getName() + "." + name);
        }
        return f.get(target);
    }

    private static Map<?, ?> findManagerMap(Object registry, String preloadId) throws IllegalAccessException {
        for (Class<?> current = registry.getClass(); current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Map<?, ?> map = (Map<?, ?>) field.get(registry);
                if (map != null && map.containsKey(preloadId)) return map;
            }
        }
        return null;
    }

    private static final class Quote {
        final Object config;
        final String responseId;
        final double usd;

        Quote(Object config, String responseId, double usd) {
            this.config = config;
            this.responseId = responseId;
            this.usd = usd;
        }
    }
}
