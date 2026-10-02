package com.cashcraft.ads.mediation.smoke;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import com.cashcraft.ads.mediation.AdPlatform;
import com.cashcraft.ads.mediation.AdsNativeView;
import com.cashcraft.ads.mediation.NativeAssets;
import com.cashcraft.ads.mediation.NativeLayoutBinding;
import com.cashcraft.ads.mediation.internal.nativeads.NativeAdHandle;
import com.cashcraft.ads.mediation.internal.nativeads.NativeAvailability;
import com.cashcraft.ads.mediation.internal.nativeads.NativeCallbacks;
import com.cashcraft.ads.mediation.internal.nativeads.NativeLoad;
import java.lang.reflect.Field;
import java.util.List;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

/** Debug 测试桥：Java 可使用目标 APK 的 JVM 接口，无需抑制 Kotlin internal 可见性检查。 */
public final class NativeRetentionTestAd implements NativeAdHandle {
    public View view;
    public int pauses, resumes, renders, destroyed;

    public static Object presentation(AdsNativeView card) {
        return read(read(card, "entry"), "value");
    }

    public static void install(AdsNativeView card, List<Object> loads, Runnable cancel) {
        Object record = presentation(card);
        Object controller = read(record, "controller");
        write(controller, "availability", (Function0<NativeAvailability>) () -> new NativeAvailability(true, null));
        write(controller, "load", (Function1<NativeCallbacks, NativeLoad>) callbacks -> {
            AdsNativeView current = (AdsNativeView) read(read(record, "entry"), "connection");
            write(record, "requestedWidth", current.getWidth() - current.getPaddingLeft() - current.getPaddingRight());
            loads.add(callbacks);
            return cancel::run;
        });
    }

    public void deliver(Object callbacks) { ((NativeCallbacks) callbacks).loaded(this); }
    public static Object read(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void write(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    @Override public NativeAssets getAssets() { return new NativeAssets(); }
    @Override public boolean pauseForRetention() { pauses++; return true; }
    @Override public boolean resumeAfterRetention() { resumes++; return true; }
    @Override public AdPlatform getPlatform() { return AdPlatform.ADMOB; }
    @Override public Double getBidPriceUsd() { return null; }
    @Override public String getAdSource() { return "controlled"; }
    @Override public String getResponseId() { return "controlled"; }
    @Override public boolean isTemplate() { return false; }
    @Override public boolean isValid() { return true; }
    @Override public boolean getCanCache() { return false; }
    @Override public void setCallbacks(NativeCallbacks callbacks) { }
    @Override public Long getExpiresAtMillis() { return null; }
    @Override public View render(Activity activity, NativeLayoutBinding binding, int widthPx) {
        renders++;
        view = new FrameLayout(activity);
        return view;
    }
    @Override public void destroy() { destroyed++; }
}
