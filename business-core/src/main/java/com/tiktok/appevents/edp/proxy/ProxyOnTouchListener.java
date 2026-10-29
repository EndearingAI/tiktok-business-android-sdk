package com.tiktok.appevents.edp.proxy;

import android.view.MotionEvent;
import android.view.View;

import com.tiktok.util.TTTrackingConsent;

public class ProxyOnTouchListener implements View.OnTouchListener {

    ITouchListener listener;
    View.OnTouchListener originOnTouchListener;
    private final long consentGeneration = TTTrackingConsent.capture();

    public ProxyOnTouchListener(
            ITouchListener listener, View.OnTouchListener originOnTouchListener) {
        this.listener = listener;
        this.originOnTouchListener = originOnTouchListener;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        boolean consumed = false;
        if (originOnTouchListener != null) {
            consumed = originOnTouchListener.onTouch(v, event);
        }
        try {
            TTTrackingConsent.wrap(
                            consentGeneration,
                            () -> {
                                synchronized (TTTrackingConsent.LOCK) {
                                    if (TTTrackingConsent.isGranted() && listener != null)
                                        listener.onTouch(v, event);
                                }
                            })
                    .run();
        } catch (Throwable ignore) {
        }
        return consumed;
    }

    void clear() {
        listener = null;
    }
}
