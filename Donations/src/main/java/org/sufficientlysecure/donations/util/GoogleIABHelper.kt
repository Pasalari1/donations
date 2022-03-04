package org.sufficientlysecure.donations.util

import android.app.Activity
import android.os.Handler
import android.util.Log
import com.android.billingclient.api.*

class GoogleIABHelper(private val context: Activity, private val listener: GoogleIABListener) : PurchasesUpdatedListener {


    private lateinit var billingClient: BillingClient
    private var connected = false
    private var handler = Handler(context.mainLooper)

    private fun ensureConnected(callback: () -> Unit) {
        if (!connected)
            connect({
                if (!connected) {
                    listener.donationFailed()
                } else {
                    callback()
                }
            })
        else
            callback()
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK || purchases == null)
            listener.donationFailed()
        else {
            Log.d(tag, purchases.toString())
            for (purchase in purchases) {
                Log.d(tag, "Purchased ${purchase.skus} successfully. State is ${purchase.purchaseState}")
                if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED)
                    continue
                ensureConnected {
                    billingClient.consumeAsync(ConsumeParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken)
                            .build()) { consumeBillingResult: BillingResult, purchaseToken: String ->
                        Log.d(tag, "Consumed")
                        if (purchase.purchaseToken != purchaseToken)
                            throw RuntimeException("purchase.purchaseToken != purchaseToken")
                        if (consumeBillingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                            Log.e(tag, "Consumption failed!")
                            listener.donationFailed()
                        } else {
                            Log.d(tag, "Consumption successful")
                            for (sku in purchase.skus) {
                                listener.donationSuccess(sku)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun connect(callback: () -> Unit = {}, retry: Int = 20) {
        try {
            billingClient =
                BillingClient.newBuilder(context).setListener(this).enablePendingPurchases().build()
            billingClient.startConnection(object : BillingClientStateListener {
                /**
                 * Called to notify that connection to billing service was lost
                 *
                 *
                 * Note: This does not remove billing service connection itself - this binding to the service
                 * will remain active, and you will receive a call to [.onBillingSetupFinished] when billing
                 * service is next running and setup is complete.
                 */
                override fun onBillingServiceDisconnected() {
                    Log.e(tag, "Connection lost!")
                    connected = false
                    // We lost connection but don't reconnect, because we might not be active
                }

                /**
                 * Called to notify that setup is complete.
                 *
                 * @param billingResult The [BillingResult] which returns the status of the setup process.
                 */
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.d(tag, "Connected")
                        connected = true
                        callback()
                    } else {
                        Log.e(
                            tag,
                            "Connection was not successful (${billingResult.responseCode}:${billingResult.debugMessage})"
                        )
                        connected = false
                        if (retry > 0)
                            handler.postDelayed(
                                { connect(callback, retry - 1) },
                                10000L / retry
                            )
                        else
                            callback()
                    }
                }
            })
        } catch (e: IllegalStateException) {
            connected = false
            if (retry > 0)
                handler.postDelayed(
                    { connect(callback, retry - 1) },
                    10000L / retry
                )
            else
                callback()
        }
    }

    fun makePayment(productId: String) {
        ensureConnected { makePaymentInternal(productId) }
    }

    private fun makePaymentInternal(productId: String) {
            Log.d(tag, "Getting SKUs for $productId")
            val skuDetails = SkuDetailsParams.newBuilder()
                    .setSkusList(listOf(productId))
                    .setType(BillingClient.SkuType.INAPP)
                    .build()
            ensureConnected {
                billingClient.querySkuDetailsAsync(skuDetails) {
                    billingResult: BillingResult, skuDetails: MutableList<SkuDetails>? ->
                    if (billingResult.responseCode != BillingClient.BillingResponseCode.OK)
                        listener.donationFailed()
                    else {
                        if (skuDetails?.size != 1) {
                            Log.e(tag, "No SKU available for donation. Check you are passing correct productId and that it is valid on Google servers $skuDetails")
                            listener.donationFailed()
                        } else {
                            val params = BillingFlowParams.newBuilder()
                                    .setSkuDetails(skuDetails[0])
                                    .build()
                            ensureConnected {
                                billingClient.launchBillingFlow(context, params)
                            }
                        }
                    }
                }
            }
    }

    companion object {
        const val tag = "GoogleIABHelper"
    }
}

interface GoogleIABListener {
    fun donationFailed()
    fun donationSuccess(productId: String)
}
