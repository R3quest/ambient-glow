package app.lumement.dashboard

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
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
import app.lumement.BuildConfig
import app.lumement.GlowLog
import app.lumement.Premium
import app.lumement.PremiumState

/** Premium's one-time product in Play Console. */
private const val PRODUCT_ID = "premium"

/** How long Play has to answer before the dashboard says it can't sell premium right now. */
private const val PLAY_PATIENCE_MS = 10_000L

/**
 * Where asking Google Play for premium stands: still [ASKING] (connecting, or the price on its
 * way), [SELLING] at a price, or [CLOSED]: Play can't sell it now (no Play Store, not signed in,
 * no answer in [PLAY_PATIENCE_MS]). Asked again on the next resume.
 */
internal enum class PlayShop { ASKING, SELLING, CLOSED }

/** What the Premium page offers in place of its buy button. */
internal enum class PremiumOffer { OWNED, BUY, ASKING, UNAVAILABLE }

/**
 * The Premium page's offer: the buy button once Play has a [price] (and there is an activity to
 * show Play's sheet over, [canBuy]); while Play is still being asked, a quiet wait, not the
 * apology that it can't sell, which is for when it has said so or not answered.
 */
internal fun premiumOffer(state: PremiumState, price: String?, shop: PlayShop, canBuy: Boolean): PremiumOffer = when {
    state == PremiumState.Owned -> PremiumOffer.OWNED
    price != null && canBuy -> PremiumOffer.BUY
    shop == PlayShop.ASKING && canBuy -> PremiumOffer.ASKING
    else -> PremiumOffer.UNAVAILABLE
}

/**
 * Premium as the dashboard shows it: where it stands ([Premium]), its price, and buying it. Only
 * the dashboard talks to Google Play, while it is up: the Play Store app does the networking, so
 * Lumement itself never goes online. What Play says is stored, so the listener and the glow
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

    /** Where asking Play stands; [price] is set once it is [PlayShop.SELLING]. */
    var shop by mutableStateOf(PlayShop.ASKING)
        private set

    private var product: ProductDetails? = null

    // A connection that never answers would leave the page waiting for good: after a while it says
    // Play can't sell premium now. Cancelled on any answer, and when the dashboard goes.
    private val patience = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { if (shop == PlayShop.ASKING) shop = PlayShop.CLOSED }

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
        patience.removeCallbacks(giveUp)
        client?.endConnection()
    }

    /** Back on the dashboard: the days left are counted again, and Play asked what is owned. */
    fun resumed() {
        version++
        val client = client ?: return
        when (client.connectionState) {
            BillingClient.ConnectionState.CONNECTED -> {
                // Up, but its price never came (Play said no before): asked again.
                if (product == null && shop == PlayShop.CLOSED) {
                    ask()
                    queryProduct()
                }
                restore()
            }
            BillingClient.ConnectionState.DISCONNECTED -> {
                // Without a price yet, asked again: the page waits rather than says it can't.
                if (product == null) ask()
                client.startConnection(connection)
            }
            else -> Unit // connecting: it restores when it is up
        }
    }

    private fun ask() {
        shop = PlayShop.ASKING
        patience.removeCallbacks(giveUp)
        patience.postDelayed(giveUp, PLAY_PATIENCE_MS)
    }

    /** Play has answered about selling premium: at [price], or (null) not now. */
    private fun answered(price: String?) {
        patience.removeCallbacks(giveUp)
        this.price = price
        shop = if (price != null) PlayShop.SELLING else PlayShop.CLOSED
    }

    // Dropped (the Play Store updated, say): connected again on the next resume.
    private val connection = object : BillingClientStateListener {
        override fun onBillingSetupFinished(result: BillingResult) {
            GlowLog.d { "billing setup ${result.responseCode} ${result.debugMessage}" }
            if (result.responseCode != BillingResponseCode.OK) {
                if (product == null) answered(null)
                return
            }
            if (product == null) queryProduct()
            restore()
        }

        override fun onBillingServiceDisconnected() {
            if (product == null) answered(null)
        }
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
            if (result.responseCode != BillingResponseCode.OK) {
                answered(null)
                return@queryProductDetailsAsync
            }
            product = details.productDetailsList.firstOrNull()
            answered(product?.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice)
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
