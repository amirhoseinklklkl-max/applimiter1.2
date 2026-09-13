package ir.amir.applimiter.ads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.adivery.sdk.Adivery
import com.adivery.sdk.AdiveryListener
import java.lang.ref.WeakReference

/**
 * لایه‌ی واسط با ادیوری.
 *
 * روش کار: نمایش «صف» می‌شود. لحظه‌ای که تبلیغ آماده شد، اگر صفحه هنوز باز باشد نشان داده می‌شود.
 * درخواست ناموفق هم چند بار با فاصله دوباره تلاش می‌کند.
 */
object AdsManager {

    private const val TAG = "AdiveryAds"
    private const val MAX_ATTEMPTS = 4
    private const val RETRY_DELAY_MS = 5_000L

    /** تا این مدت بعد از ورود، اگر تبلیغ برسد نشان بده. بعد از آن دیگر مزاحم کاربر نشو. */
    private const val PENDING_WINDOW_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())

    private var activityRef: WeakReference<Activity>? = null

    @Volatile private var interstitialLoaded = false
    @Volatile private var requestInFlight = false
    @Volatile private var attempts = 0
    @Volatile private var lastShownAt = 0L
    @Volatile private var showing = false
    @Volatile private var pendingShowUntil = 0L
    @Volatile private var listenerRegistered = false

    /** برای دیدن وضعیت در حالت دیباگ، تا اشکال‌یابی راحت باشد. */
    var status by mutableStateOf("آماده‌سازی…")
        private set

    val interstitialEnabled: Boolean
        get() = AdsConfig.isConfigured(AdsConfig.INTERSTITIAL_PLACEMENT)

    val bannerEnabled: Boolean
        get() = AdsConfig.isConfigured(AdsConfig.BANNER_PLACEMENT)

    val isAdReady: Boolean
        get() = interstitialLoaded

    // ---------------------------------------------------------------- lifecycle

    fun onActivityResumed(activity: Activity) {
        activityRef = WeakReference(activity)

        if (!listenerRegistered) {
            listenerRegistered = true
            registerInterstitialListener()
        }
        preloadInterstitial()
    }

    fun onActivityPaused(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    private fun currentActivity(): Activity? {
        val activity = activityRef?.get() ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        return activity
    }

    // ---------------------------------------------------------------- request

    private fun registerInterstitialListener() {
        if (!interstitialEnabled) return
        runCatching {
            Adivery.addPlacementListener(
                AdsConfig.INTERSTITIAL_PLACEMENT,
                object : AdiveryListener() {
                    override fun onInterstitialAdLoaded(placementId: String) {
                        requestInFlight = false
                        attempts = 0
                        interstitialLoaded = true
                        updateStatus("تبلیغ آماده است")
                        // اگر نمایش در صف بود، همین حالا نشان بده
                        main.post { showIfPending() }
                    }

                    override fun onInterstitialAdShown(placementId: String) {
                        updateStatus("تبلیغ نمایش داده شد")
                    }

                    override fun onInterstitialAdClicked(placementId: String) = Unit

                    override fun onInterstitialAdClosed(placementId: String) {
                        showing = false
                        interstitialLoaded = false
                        updateStatus("تبلیغ بسته شد")
                        attempts = 0
                        preloadInterstitial()
                    }

                    override fun onError(placementId: String, reason: String) {
                        requestInFlight = false
                        showing = false
                        updateStatus("دریافت/نمایش نشد: $reason")
                        Log.d(TAG, "interstitial error: $reason")
                        if (attempts < MAX_ATTEMPTS) {
                            main.postDelayed({ preloadInterstitial() }, RETRY_DELAY_MS)
                        }
                    }
                }
            )
        }.onFailure { updateStatus("خطا در ثبت listener: ${it.message}") }
    }

    /** تبلیغ آنی را از قبل می‌گیرد. اگر شکست خورد، چند بار با فاصله دوباره تلاش می‌کند. */
    fun preloadInterstitial(force: Boolean = false) {
        if (!interstitialEnabled) {
            updateStatus("جایگاه تبلیغ آنی تنظیم نشده")
            return
        }
        if (force) {
            attempts = 0
            interstitialLoaded = false
        }
        if (interstitialLoaded || requestInFlight) return
        if (attempts >= MAX_ATTEMPTS) {
            updateStatus("تبلیغی موجود نبود (${attempts} تلاش)")
            return
        }

        val activity = currentActivity() ?: return

        requestInFlight = true
        attempts++
        updateStatus("در حال دریافت تبلیغ… (تلاش $attempts)")

        runCatching {
            Adivery.prepareInterstitialAd(activity, AdsConfig.INTERSTITIAL_PLACEMENT)
        }.onFailure {
            requestInFlight = false
            updateStatus("خطای درخواست: ${it.message}")
            Log.w(TAG, "interstitial request error: ${it.message}")
        }
    }

    // ---------------------------------------------------------------- show

    /**
     * در ورود کاربر صدا زده می‌شود.
     * اگر تبلیغ آماده باشد فوراً نشان می‌دهد، وگرنه نمایش را در صف می‌گذارد تا برسد.
     */
    fun requestShowOnEntry(bypassInterval: Boolean = false) {
        if (!interstitialEnabled || showing) return

        val now = System.currentTimeMillis()
        if (!bypassInterval && now - lastShownAt < AdsConfig.INTERSTITIAL_MIN_INTERVAL_MS) {
            updateStatus("فاصله‌ی بین دو تبلیغ رعایت می‌شود")
            return
        }

        pendingShowUntil = now + PENDING_WINDOW_MS
        if (interstitialLoaded) {
            main.post { showIfPending() }
        } else {
            preloadInterstitial()
        }
    }

    private fun showIfPending() {
        if (showing) return
        if (System.currentTimeMillis() > pendingShowUntil) return
        if (!interstitialLoaded || !Adivery.isLoaded(AdsConfig.INTERSTITIAL_PLACEMENT)) return

        showing = true
        lastShownAt = System.currentTimeMillis()
        pendingShowUntil = 0
        updateStatus("در حال نمایش تبلیغ")

        runCatching {
            Adivery.showAd(AdsConfig.INTERSTITIAL_PLACEMENT)
        }.onFailure {
            showing = false
            updateStatus("خطای نمایش: ${it.message}")
            Log.w(TAG, "interstitial show error: ${it.message}")
        }
    }

    private fun updateStatus(text: String) {
        main.post { status = text }
        Log.d(TAG, text)
    }
}
