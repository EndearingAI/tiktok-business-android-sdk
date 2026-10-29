package com.tiktok.iap.billing.client;

import com.tiktok.iap.billing.GPBillVersions;

public interface IBillingProxy {

    GPBillVersions.GPBillingVer getVersion();

    void init();

    void queryPurchaseHistory();

    default void close() {}
}
