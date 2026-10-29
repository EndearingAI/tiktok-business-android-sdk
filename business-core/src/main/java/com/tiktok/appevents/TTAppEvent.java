/*******************************************************************************
 * Copyright (c) 2020. Tiktok Inc.
 *
 * This source code is licensed under the MIT license found in the LICENSE file in the root directory of this source tree.
 ******************************************************************************/

package com.tiktok.appevents;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.Base64;
import android.view.View;

import com.tiktok.util.TTTrackingConsent;

import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class TTAppEvent implements Serializable {

    public enum TTAppEventType {
        track,
        identify
    }

    private static final long serialVersionUID = 2L;
    private List<String> tiktokAppIds = new ArrayList<>();
    private final TTAppEventType type;
    private String eventName;
    private Date timeStamp;
    private String propertiesJson;
    private String eventId;
    private String transportEventId;
    private Boolean isEdp;
    private static final AtomicLong counter = new AtomicLong(new Date().getTime());
    private final Long uniqueId;
    private final TTUserInfo userInfo;
    private String screenShot;

    TTAppEvent(
            TTAppEventType type,
            String eventName,
            String propertiesJson,
            String eventId,
            String[] ttAppId) {
        this(type, eventName, new Date(), propertiesJson, eventId, ttAppId);
    }

    TTAppEvent(
            TTAppEventType type,
            String eventName,
            Date timeStamp,
            String propertiesJson,
            String eventId,
            String[] ttAppId) {
        this.type = type;
        this.eventName = eventName;
        this.timeStamp = timeStamp;
        this.propertiesJson = propertiesJson;
        this.eventId = eventId;
        this.uniqueId = TTAppEvent.counter.getAndIncrement();
        this.userInfo = TTUserInfo.sharedInstance.clone();
        if (ttAppId != null && ttAppId.length > 0) {
            for (int i = 0; i < ttAppId.length; i++) {
                tiktokAppIds.add(ttAppId[i]);
            }
        }
    }

    public Boolean getEdp() {
        return isEdp;
    }

    public void setEdp(Boolean edp) {
        isEdp = edp;
    }

    public TTUserInfo getUserInfo() {
        return userInfo;
    }

    public String getEventName() {
        return eventName;
    }

    public String getType() {
        return this.type.name();
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public Date getTimeStamp() {
        return timeStamp;
    }

    public void setTimeStamp(Date timeStamp) {
        this.timeStamp = timeStamp;
    }

    public String getEventId() {
        return eventId;
    }

    synchronized String getTransportEventId() {
        // Populate lazily for events persisted by upstream versions before this field existed.
        if (transportEventId == null) {
            transportEventId =
                    eventId == null || eventId.isEmpty()
                            ? java.util.UUID.randomUUID().toString()
                            : eventId;
        }
        return transportEventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getPropertiesJson() {
        return propertiesJson;
    }

    public void setPropertiesJson(String propertiesJson) {
        this.propertiesJson = propertiesJson;
    }

    public Long getUniqueId() {
        return this.uniqueId;
    }

    public List<String> getTiktokAppIds() {
        return tiktokAppIds;
    }

    public void setTiktokAppIds(List<String> tiktokAppIds) {
        this.tiktokAppIds = tiktokAppIds;
    }

    public String getScreenShot() {
        return screenShot;
    }

    public void setScreenShot() {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            try {
                View view = TTLifecycleListener.getActivityRef().get().getWindow().getDecorView();
                Bitmap bitmap =
                        Bitmap.createBitmap(
                                view.getWidth(), view.getHeight(), Bitmap.Config.RGB_565);
                Canvas canvas = new Canvas(bitmap);
                view.draw(canvas);
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 5, outputStream);
                this.screenShot = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP);
            } catch (Throwable ignore) {
            }
        }
    }

    @Override
    public String toString() {
        return "TTAppEvent{"
                + "eventName='"
                + eventName
                + '\''
                + ", timeStamp="
                + timeStamp
                + ", propertiesJson='"
                + propertiesJson
                + '\''
                + ", eventId='"
                + eventId
                + '\''
                + ", uniqueId="
                + uniqueId
                + ", tiktokAppIds="
                + tiktokAppIds
                + '}';
    }
}
