package ir.amir.applimiter.ads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ir.tapsell.mediation.Tapsell
import ir.tapsell.mediation.ad.AdStateListener
import ir.tapsell.mediation.ad.request.RequestResultListener
import ir.tapsell.mediation.ad.show.AdShowCompletionState
import java.lang.ref.WeakReference

/**
 * لایه‌ی واسط با تپسل مدیشن.
 *
 * باگی که در نسخه‌ی قبل بود: تبلیغ ۹۰۰ میلی‌ثانیه بعد از ورود «نمایش» داده می‌شد، در حالی که
 * درخواست هنوز از سرور برنگشته بود. پس همیشه بی‌صدا رد می‌شد و دیگر هیچ‌وقت دوباره تلاش نمی‌کرد.
 *
 * حالا: نمایش «صف» می‌شود. لحظه‌ای که تبلیغ آماده شد، اگر صفحه هنوز باز باشد نشان داده می‌شود.
 * درخواست ناموفق هم چند بار با فاصله دوباره تلاش می‌کند.
 */
object AdsManager {

    private const val TAG = "TapsellAds"
    private const val MAX_ATTEMPTS = 4
    private const val RETRY_DELAY_MS = 5_000L

    /** تا این مدت بعد از ورود، اگر تبلیغ برسد نشان بده. بعد از آن دیگر مزاحم کاربر نشو. */
    private const val PENDING_WINDOW_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())

    private var activityRef: WeakReference<Activity>? = null

    @Volatile private var interstitialAdId: String? = null
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
        get() = AdsConfig.isConfigured(AdsConfig.INTERSTITIAL_ZONE)

    val bannerEnabled: Boolean
        get() = AdsConfig.isConfigured(AdsConfig.BANNER_ZONE)

    val isAdReady: Boolean
        get() = interstitialAdId != null

    // ---------------------------------------------------------------- lifecycle

    fun onActivityResumed(activity: Activity) {
        activityRef = WeakReference(activity)
        applyStoredConsent(activity)

        if (!listenerRegistered) {
            listenerRegistered = true
            runCatching {
                Tapsell.setInitializationListener {
                    setStatus("SDK آماده شد")
                    attempts = 0
                    preloadInterstitial()
                }
            }.onFailure { setStatus("خطا در ثبت listener: ${it.message}") }
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

    // ---------------------------------------------------------------- consent

    fun applyStoredConsent(activity: Activity) {
        if (!ConsentStore.hasBeenAsked(activity)) return
        setUserConsent(activity, ConsentStore.isGranted(activity))
    }

    /** رضایت GDPR کاربر. Activity لازم است، وگرنه ادموب و Wortise ارور می‌دهند. */
    fun setUserConsent(activity: Activity, granted: Boolean) {
        runCatching { Tapsell.setUserConsent(activity, granted) }
            .onFailure { Log.w(TAG, "consent failed: ${it.message}") }
    }

    // ---------------------------------------------------------------- request

    /** تبلیغ آنی را از قبل می‌گیرد. اگر شکست خورد، چند بار با فاصله دوباره تلاش می‌کند. */
    fun preloadInterstitial(force: Boolean = false) {
        if (!interstitialEnabled) {
            setStatus("زون تبلیغ آنی تنظیم نشده")
            return
        }
        if (force) {
            attempts = 0
            interstitialAdId = null
        }
        if (interstitialAdId != null || requestInFlight) return
        if (attempts >= MAX_ATTEMPTS) {
            setStatus("تبلیغی موجود نبود (${attempts} تلاش)")
            return
        }

        requestInFlight = true
        attempts++
        setStatus("در حال دریافت تبلیغ… (تلاش $attempts)")

        // Activity را پاس می‌دهیم؛ بعضی شبکه‌ها (اپلوین) بدون آن تبلیغ نمی‌دهند
        val activity = currentActivity()

        val listener = object : RequestResultListener {
            override fun onSuccess(adId: String) {
                requestInFlight = false
                attempts = 0
                interstitialAdId = adId
                setStatus("تبلیغ آماده است")
                // اگر نمایش در صف بود، همین حالا نشان بده
                main.post { showIfPending() }
            }

            override fun onFailure(message: String) {
                requestInFlight = false
                setStatus("دریافت نشد: $message")
                Log.d(TAG, "interstitial request failed: $message")
                if (attempts < MAX_ATTEMPTS) {
                    main.postDelayed({ preloadInterstitial() }, RETRY_DELAY_MS)
                }
            }
        }

        runCatching {
            if (activity != null) {
                Tapsell.requestInterstitialAd(AdsConfig.INTERSTITIAL_ZONE, activity, listener)
            } else {
                Tapsell.requestInterstitialAd(AdsConfig.INTERSTITIAL_ZONE, listener)
            }
        }.onFailure {
            requestInFlight = false
            setStatus("خطای درخواست: ${it.message}")
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
            setStatus("فاصله‌ی بین دو تبلیغ رعایت می‌شود")
            return
        }

        pendingShowUntil = now + PENDING_WINDOW_MS
        if (interstitialAdId != null) {
            main.post { showIfPending() }
        } else {
            preloadInterstitial()
        }
    }

    private fun showIfPending() {
        if (showing) return
        if (System.currentTimeMillis() > pendingShowUntil) return

        val adId = interstitialAdId ?: return
        val activity = currentActivity() ?: return

        interstitialAdId = null
        pendingShowUntil = 0
        showing = true
        lastShownAt = System.currentTimeMillis()
        setStatus("در حال نمایش تبلیغ")

        runCatching {
            Tapsell.showInterstitialAd(
                adId,
                activity,
                object : AdStateListener.Interstitial {
                    override fun onAdImpression() {
                        setStatus("تبلیغ نمایش داده شد")
                    }

                    override fun onAdClicked() = Unit

                    override fun onAdClosed(completionState: AdShowCompletionState) {
                        showing = false
                        setStatus("تبلیغ بسته شد")
                        attempts = 0
                        preloadInterstitial()
                    }

                    override fun onAdFailed(message: String) {
                        showing = false
                        lastShownAt = 0L
                        setStatus("نمایش نشد: $message")
                        Log.d(TAG, "interstitial show failed: $message")
                        attempts = 0
                        preloadInterstitial()
                    }
                }
            )
        }.onFailure {
            showing = false
            lastShownAt = 0L
            setStatus("خطای نمایش: ${it.message}")
            Log.w(TAG, "interstitial show error: ${it.message}")
        }
    }

    private fun setStatus(text: String) {
        main.post { status = text }
        Log.d(TAG, text)
    }
}
