package com.tiktok.appevents.edp.proxy;

import android.view.View;

import com.tiktok.util.TTTrackingConsent;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class TouchProxyHelper {
    private static final List<WeakReference<ProxyOnTouchListener>> observers = new ArrayList<>();

    public static void clear() {
        for (WeakReference<ProxyOnTouchListener> observer : observers) {
            ProxyOnTouchListener proxy = observer.get();
            if (proxy != null) proxy.clear();
        }
        observers.clear();
    }

    public static void proxy(WeakReference<View> v, ITouchListener clickListener) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;
            try {
                if (clickListener == null) {
                    return;
                }
                View view = v.get();
                if (view == null) {
                    return;
                }
                Method method = View.class.getDeclaredMethod("getListenerInfo");
                method.setAccessible(true);
                Object mListenerInfo = method.invoke(view);
                Class<?> clz = Class.forName("android.view.View$ListenerInfo");
                Field field = clz.getDeclaredField("mOnTouchListener");
                field.setAccessible(true);
                View.OnTouchListener onTouchListenerInstance =
                        (View.OnTouchListener) field.get(mListenerInfo);
                if (onTouchListenerInstance instanceof ProxyOnTouchListener) {
                    ProxyOnTouchListener previous = (ProxyOnTouchListener) onTouchListenerInstance;
                    previous.clear();
                    onTouchListenerInstance = previous.originOnTouchListener;
                }
                ProxyOnTouchListener proxy =
                        new ProxyOnTouchListener(clickListener, onTouchListenerInstance);
                field.set(mListenerInfo, proxy);
                observers.add(new WeakReference<>(proxy));
            } catch (Throwable ignore) {
            }
        }
    }
}
