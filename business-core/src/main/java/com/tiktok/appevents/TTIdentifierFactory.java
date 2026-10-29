/*******************************************************************************
 * Copyright (c) 2020. Tiktok Inc.
 *
 * This source code is licensed under the MIT license found in the LICENSE file in the root directory of this source tree.
 ******************************************************************************/

package com.tiktok.appevents;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.SystemClock;
import android.text.TextUtils;

import com.tiktok.TikTokBusinessSdk;
import com.tiktok.util.IOUtils;
import com.tiktok.util.JSON;
import com.tiktok.util.TTLogger;
import com.tiktok.util.TTReflect;
import com.tiktok.util.TTTrackingConsent;
import com.tiktok.util.TTUtil;

import org.json.JSONObject;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** get advertiser id info using Google Play API */
public class TTIdentifierFactory {
    private static final int UPDATE_TIMES = 3600 * 1000; // if successful, refresh periodically
    private static final String TAG = "TTIdentifierFactory";
    private static final TTLogger logger = new TTLogger(TAG, TikTokBusinessSdk.getLogLevel());

    private static volatile String sGAID;
    private static volatile boolean sAdTrackingEnabled;
    private static final java.util.Set<AdIdConnection> activeConnections =
            new java.util.concurrent.CopyOnWriteArraySet<>();

    private static final AtomicInteger sMaxRetry = new AtomicInteger(0);
    private static volatile long sNextUpdateTime = SystemClock.elapsedRealtime() + UPDATE_TIMES;

    public static void clear(Context context) {
        for (AdIdConnection connection : activeConnections) connection.close();
        sGAID = null;
        sAdTrackingEnabled = true;
        sMaxRetry.set(0);
        sNextUpdateTime = 0;
        if (sExecutor != null) {
            sExecutor.shutdownNow();
            sExecutor = null;
        }
        if (context != null
                && !context.getSharedPreferences("com.tiktok.sdk.ids", Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .commit()) {
            throw new IllegalStateException(
                    "Unable to erase TikTok advertising identity preferences");
        }
        GAIDCache.sInstance = null;
    }

    public static AdIdInfo getGoogleAdIdInfo(Context context) {
        return TTTrackingConsent.call(
                TTTrackingConsent.capture(),
                () -> getGoogleAdIdInfoWithConsent(context),
                AdIdInfo.buildDefault());
    }

    private static AdIdInfo getGoogleAdIdInfoWithConsent(Context context) {
        if (!TTTrackingConsent.isGranted()) return AdIdInfo.buildDefault();
        if (sMaxRetry.get() > 20) {
            return AdIdInfo.buildDefault();
        }

        AdIdInfo adIdInfo = null;

        // 1 from cache
        adIdInfo = getByCache(context);

        // 2 from reflect
        if (adIdInfo == null) {
            adIdInfo = getByReflect(context);
        }

        // 3 from service
        if (adIdInfo == null) {
            adIdInfo = getByService(context);
        }

        // 4 default
        if (adIdInfo == null) {
            adIdInfo = AdIdInfo.buildDefault();
        }

        // only get success need update
        if (adIdInfo.from == AdIdInfo.FROM_ROM || adIdInfo.from == AdIdInfo.FROM_SP) {
            updateAdIdInfo(context);
        }

        // send monitor event
        sendMonitor(adIdInfo);

        // mark no gaid
        if (TextUtils.isEmpty(adIdInfo.adId)) {
            sMaxRetry.getAndIncrement();
        }

        return TTTrackingConsent.isGranted() ? adIdInfo : AdIdInfo.buildDefault();
    }

    private static void sendMonitor(AdIdInfo info) {
        try {
            if (info != null) {
                JSONObject meta = TTUtil.getMetaWithTS(null);
                JSON.putLong(meta, "duration", info.duration);
                JSON.putInt(meta, "from", info.from);
                JSON.putInt(meta, "result", TextUtils.isEmpty(info.getAdId()) ? 0 : 1);
                JSON.putInt(meta, "lat", info.isAdTrackingEnabled() ? 1 : 0);
                TikTokBusinessSdk.getAppEventLogger().monitorMetric("gaid_result", meta, null);
            }
        } catch (Throwable ignore) {
        }
    }

    private static ExecutorService sExecutor;

    private static void updateAdIdInfo(Context context) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            try {
                long currentTime = SystemClock.elapsedRealtime();
                if (sNextUpdateTime > 0 && sNextUpdateTime < currentTime) {
                    logger.info("gaid is not updated yet");
                    return;
                }

                logger.info("gaid is updated");

                // after one hour update
                sNextUpdateTime = currentTime + UPDATE_TIMES;

                if (sExecutor == null) {
                    sExecutor = Executors.newSingleThreadScheduledExecutor(new TTThreadFactory());
                }

                sExecutor.submit(
                        TTTrackingConsent.wrap(
                                () -> {
                                    // first reflect, then service
                                    if (getByReflect(context) == null) {
                                        getByService(context);
                                    }
                                }));
            } catch (Throwable ignore) {
            }
        }
    }

    private static AdIdInfo getByCache(Context context) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return null;

            // rom cache first
            if (!TextUtils.isEmpty(sGAID)) {
                AdIdInfo info = new AdIdInfo(sGAID, sAdTrackingEnabled);
                info.from = AdIdInfo.FROM_ROM;
                return info;
            }

            // sp cache second
            if (TextUtils.isEmpty(sGAID)) {
                long start = SystemClock.elapsedRealtime();
                String gaid = GAIDCache.getInstance(context).getGAID();
                boolean enable = GAIDCache.getInstance(context).trackEnable();
                if (!TextUtils.isEmpty(gaid)) {
                    sGAID = gaid;
                    sAdTrackingEnabled = enable;

                    AdIdInfo info = new AdIdInfo(sGAID, sAdTrackingEnabled);
                    info.from = AdIdInfo.FROM_SP;
                    info.duration = SystemClock.elapsedRealtime() - start;
                    return info;
                }
            }

            return null;
        }
    }

    private static AdIdInfo getByReflect(Context context) {
        if (!TTTrackingConsent.isGranted()) return null;
        try {
            long start = SystemClock.elapsedRealtime();

            Object infoObj =
                    TTReflect.on("com.google.android.gms.ads.identifier.AdvertisingIdClient")
                            .findMethod("getAdvertisingIdInfo", Context.class)
                            .call(null, context);
            if (infoObj == null) {
                return null;
            }

            String adid =
                    (String) TTReflect.on(infoObj.getClass()).findMethod("getId").call(infoObj);
            Boolean limit =
                    (Boolean)
                            TTReflect.on(infoObj.getClass())
                                    .findMethod("isLimitAdTrackingEnabled")
                                    .call(infoObj);

            if (TTTrackingConsent.isGranted() && !TextUtils.isEmpty(adid) && limit != null) {
                TTTrackingConsent.runIfGranted(
                        () -> {
                            sGAID = adid;
                            sAdTrackingEnabled = limit;
                            sNextUpdateTime = SystemClock.elapsedRealtime() + UPDATE_TIMES;
                            GAIDCache.getInstance(context).update(adid, limit);
                        });

                AdIdInfo info = new AdIdInfo(adid, limit);
                info.from = AdIdInfo.FROM_REFLECT;
                info.duration = SystemClock.elapsedRealtime() - start;
                return info;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private static AdIdInfo getByService(Context context) {
        if (!TTTrackingConsent.isGranted()) return null;
        // service binding intent
        AdIdConnection serviceConnection = null;
        try {
            long start = SystemClock.elapsedRealtime();

            Intent intent = new Intent("com.google.android.gms.ads.identifier.service.START");
            intent.setPackage("com.google.android.gms");
            serviceConnection = new AdIdConnection(context);

            // if connection is successful
            boolean bound;
            synchronized (TTTrackingConsent.LOCK) {
                if (!TTTrackingConsent.isGranted()) return null;
                bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
                serviceConnection.bound = bound;
                if (bound) activeConnections.add(serviceConnection);
            }
            if (bound) {
                AdIdInterface adIdInterface = new AdIdInterface(serviceConnection.getBinder());
                if (!TTTrackingConsent.isGranted()) return null;
                String adId = adIdInterface.getAdId();
                boolean isAdTrackingEnabled = adIdInterface.isAdIdTrackingEnabled();
                if (TTTrackingConsent.isGranted() && !TextUtils.isEmpty(adId)) {
                    TTTrackingConsent.runIfGranted(
                            () -> {
                                sGAID = adId;
                                sAdTrackingEnabled = isAdTrackingEnabled;
                                sNextUpdateTime = SystemClock.elapsedRealtime() + UPDATE_TIMES;
                                GAIDCache.getInstance(context).update(adId, isAdTrackingEnabled);
                            });

                    // everything is ok, call listener
                    AdIdInfo info = new AdIdInfo(adId, isAdTrackingEnabled);
                    info.from = AdIdInfo.FROM_SERVICE;
                    info.duration = SystemClock.elapsedRealtime() - start;
                    return info;
                }
            } else {
                // connection to service was not successful
                logger.info("Failed to detect google play identifier service on this phone");
            }
        } catch (Throwable e) {
            logger.error(e, "remote exception");
        } finally {
            // finally unbind from service
            try {
                if (serviceConnection != null) serviceConnection.close();
            } catch (Throwable ignore) {
            }
        }
        return null;
    }

    /** Holds 'Ad ID and 'Is Limited Ad Tracking' flag */
    public static class AdIdInfo {
        public static final int FROM_DEFAULT = 0;
        public static final int FROM_ROM = 10;
        public static final int FROM_SP = 12;
        public static final int FROM_REFLECT = 13;
        public static final int FROM_SERVICE = 14;

        public int from = FROM_DEFAULT;
        public long duration = 0;

        private final String adId;
        private final boolean isAdTrackingEnabled;

        static AdIdInfo buildDefault() {
            return new AdIdInfo("", true);
        }

        private AdIdInfo(String adId, boolean isAdTrackingEnabled) {
            this.adId = adId;
            this.isAdTrackingEnabled = isAdTrackingEnabled;
        }

        public String getAdId() {
            return adId;
        }

        public boolean isAdTrackingEnabled() {
            return isAdTrackingEnabled;
        }
    }

    private static class GAIDCache {
        private static final String SP_NAME = "com.tiktok.sdk.ids";
        private static final String SP_K_GAID = "gaid";
        private static final String SP_K_TRACK = "t_enable";
        private static volatile GAIDCache sInstance;

        private SharedPreferences mSP = null;

        public static GAIDCache getInstance(Context context) {
            if (sInstance == null) {
                synchronized (GAIDCache.class) {
                    if (sInstance == null) {
                        sInstance = new GAIDCache(context);
                    }
                }
            }
            return sInstance;
        }

        private GAIDCache(Context context) {
            try {
                mSP =
                        context.getApplicationContext()
                                .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            } catch (Throwable ignore) {
            }
        }

        private SharedPreferences mySP() {
            if (mSP == null) {
                try {
                    Context ctx = TikTokBusinessSdk.getApplicationContext();
                    if (ctx != null) {
                        mSP = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
                    }
                } catch (Throwable ignore) {
                }
            }
            return mSP;
        }

        public String getGAID() {
            try {
                return mySP().getString(SP_K_GAID, null);
            } catch (Throwable ignore) {
            }
            return null;
        }

        public boolean trackEnable() {
            try {
                return mySP().getBoolean(SP_K_TRACK, true);
            } catch (Throwable ignore) {
            }
            return true;
        }

        public void update(String gaid, boolean enable) {
            try {
                mySP().edit().putString(SP_K_GAID, gaid).putBoolean(SP_K_TRACK, enable).apply();
            } catch (Throwable ignore) {
            }
        }
    }

    /** Service connection that retrieves Binder object from connected service */
    private static class AdIdConnection implements ServiceConnection {
        private final Context context;
        private final long generation = TTTrackingConsent.capture();
        private boolean bound;

        AdIdConnection(Context context) {
            this.context = context;
        }

        void close() {
            synchronized (TTTrackingConsent.LOCK) {
                if (bound) {
                    bound = false;
                    context.unbindService(this);
                }
                queue.clear();
                activeConnections.remove(this);
            }
        }

        private final BlockingQueue<IBinder> queue = new ArrayBlockingQueue<>(1);

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            if (!TTTrackingConsent.isCurrent(generation)) return;
            try {
                this.queue.put(iBinder);
            } catch (Throwable ignore) {
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {}

        public IBinder getBinder() {
            try {
                return queue.poll(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Throwable ignore) {
            }
            return null;
        }
    }

    /** Interface that deals with advertising service's Binder */
    private static class AdIdInterface implements IInterface {
        private static final String INTERFACE_TOKEN =
                "com.google.android.gms.ads.identifier.internal.IAdvertisingIdService";
        private static final int AD_ID_TRANSACTION_CODE = 1;
        private static final int AD_TRACKING_TRANSACTION_CODE = 2;

        private final IBinder mIBinder;

        private AdIdInterface(IBinder binder) {
            this.mIBinder = binder;
        }

        @Override
        public IBinder asBinder() {
            return mIBinder;
        }

        private String getAdId() {
            Parcel data = null;
            Parcel reply = null;
            String adId = null;
            try {
                if (mIBinder != null) {
                    data = Parcel.obtain();
                    reply = Parcel.obtain();
                    data.writeInterfaceToken(INTERFACE_TOKEN);
                    mIBinder.transact(AD_ID_TRANSACTION_CODE, data, reply, 0);
                    reply.readException();
                    adId = reply.readString();
                }
            } catch (Throwable ignore) {
            } finally {
                IOUtils.close(data, reply);
            }
            return adId;
        }

        private boolean isAdIdTrackingEnabled() {
            Parcel data = null;
            Parcel reply = null;
            boolean limitedTrackingEnabled = true;
            try {
                if (mIBinder != null) {
                    data = Parcel.obtain();
                    reply = Parcel.obtain();
                    data.writeInterfaceToken(INTERFACE_TOKEN);
                    data.writeInt(1);
                    mIBinder.transact(AD_TRACKING_TRANSACTION_CODE, data, reply, 0);
                    reply.readException();
                    limitedTrackingEnabled = 0 != reply.readInt();
                }
            } catch (Throwable ignore) {
            } finally {
                IOUtils.close(data, reply);
            }
            return limitedTrackingEnabled;
        }
    }
}
