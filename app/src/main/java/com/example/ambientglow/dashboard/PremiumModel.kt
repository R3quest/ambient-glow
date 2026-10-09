package com.example.ambientglow.dashboard

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.example.ambientglow.BuildConfig
import com.example.ambientglow.GlowLog
import com.example.ambientglow.Premium
import com.example.ambientglow.PremiumState

/** Premium's one-time product in Play Console. */
private const val PRODUCT_ID = "premium"

/**
 * Premium as the dashboard shows it: where it stands ([Premium]), its price, and buying it. Only
 * the dashboard talks to Google Play, while it is up: the Play Store app does the networking, so
 * Ambient Glow itself never goes online. What Play says is stored, so the listener and the glow
 * screen read it on each message without asking. A build with premium unlocked
 * ([BuildConfig.PREMIUM]) never connects. Main thread only, as Play's callbacks are.
 */
@Stable
internal class PremiumModel(
    context: Context,
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) : PurchasesUpdatedListener {
    // Bumped by every write to the file (the listener starting the trial) and on resume, so the
    // days left are counted again.
    private var version by mutableIntStateOf(0)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }

    val state: PremiumState
        get() {
            version
            return Premium.state(prefs, clock())
        }

    /** When the trial started, or 0 ([Premium.trialStart]). */
    val trialStart: Long
        get() {
            version
            return Premium.trialStart(prefs)
        }

    /** The trial is over and the dashboard hasn't said so yet. */
    val overUnseen: Boolean get() = state == PremiumState.Over && !Premium.overSeen(prefs)

    /** Play's price for premium, local currency included; null until Play says, or if it can't. */
    var price by mutableStateOf<String?>(null)
        private set

    private var product: ProductDetails? = null

    private val client: BillingClient? = if (BuildConfig.PREMIUM) {
        null
    } else {
        BillingClient.newBuilder(context.applicationContext)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
    }

    fun attach() = prefs.registerOnSharedPreferenceChangeListener(listener)

    fun detach() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        client?.endConnection()
    }

    /** Back on the dashboard: the days left are counted again, and Play asked what is owned. */
    fun resumed() {
        version++
        val client = client ?: return
        when (client.connectionState) {
            BillingClient.ConnectionState.CONNECTED -> restore()
            BillingClient.ConnectionState.DISCONNECTED -> client.startConnection(connection)
            else -> Unit // connecting: it restores when it is up
        }
    }

    // Dropped (the Play Store updated, say): connected again on the next resume.
    private val connection = object : BillingClientStateListener {
        override fun onBillingSetupFinished(result: BillingResult) {
            GlowLog.d { "billing setup ${result.responseCode} ${result.debugMessage}" }
            if (result.responseCode != BillingResponseCode.OK) return
            if (product == null) queryProduct()
            restore()
        }

        override fun onBillingServiceDisconnected() = Unit
    }

    /** Opens Play's purchase sheet over [activity]; nothing to do until Play has a price. */
    fun unlock(activity: Activity) {
        val client = client ?: return
        val product = product ?: return
        val item = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product)
        product.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken?.let(item::setOfferToken)
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(item.build())).build()
        client.launchBillingFlow(activity, params)
    }

    fun markOverSeen() {
        Premium.markOverSeen(prefs)
        version++
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        GlowLog.d { "billing purchase ${result.responseCode} ${purchases?.size}" }
        if (result.responseCode == BillingResponseCode.OK) purchases?.let { own(it, complete = false) }
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        client?.queryProductDetailsAsync(params) { result, details ->
            GlowLog.d { "billing product ${result.responseCode} found=${details.productDetailsList.size}" }
            if (result.responseCode != BillingResponseCode.OK) return@queryProductDetailsAsync
            product = details.productDetailsList.firstOrNull()
            price = product?.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice
        }
    }

    /** What Play says is owned now: a refund takes premium back. */
    private fun restore() {
        val params = QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
        client?.queryPurchasesAsync(params) { result, purchases ->
            GlowLog.d { "billing owned ${result.responseCode} ${purchases.size}" }
            if (result.responseCode == BillingResponseCode.OK) own(purchases, complete = true)
        }
    }

    /**
     * Premium is owned once Play has its payment; [complete]: [purchases] is everything owned, so
     * premium missing from it is no longer owned. Each purchase is acknowledged, or Play refunds
     * it after three days.
     */
    private fun own(purchases: List<Purchase>, complete: Boolean) {
        val paid = purchases.filter { PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (paid.isNotEmpty() || complete) Premium.setOwned(prefs, paid.isNotEmpty())
        for (purchase in paid) {
            if (purchase.isAcknowledged) continue
            val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
            client?.acknowledgePurchase(params) { result -> GlowLog.d { "billing acknowledged ${result.responseCode}" } }
        }
    }
}

/** The dashboard's [PremiumModel], connected to Play while the dashboard is up. */
@Composable
internal fun rememberPremiumModel(): PremiumModel {
    val context = LocalContext.current
    val model = remember(context) { PremiumModel(context, Premium.prefs(context)) }
    DisposableEffect(model) {
        model.attach()
        onDispose { model.detach() }
    }
    LifecycleResumeEffect(model) {
        model.resumed()
        onPauseOrDispose { }
    }
    return model
}
