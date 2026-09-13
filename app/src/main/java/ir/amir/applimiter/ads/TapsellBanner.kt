package ir.amir.applimiter.ads

import android.app.Activity
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ir.tapsell.mediation.Tapsell
import ir.tapsell.mediation.ad.AdStateListener
import ir.tapsell.mediation.ad.request.BannerSize
import ir.tapsell.mediation.ad.request.RequestResultListener
import ir.tapsell.mediation.ad.views.banner.BannerContainer
import kotlinx.coroutines.delay

private const val TAG = "TapsellBanner"
private const val MAX_ATTEMPTS = 4
private const val RETRY_DELAY_MS = 6_000L

/**
 * بنر استاندارد تپسل.
 *
 * باگ نسخه‌ی قبل: فقط یک بار در لحظه‌ی ساخت صفحه درخواست می‌فرستاد. اگر SDK هنوز بالا نیامده بود
 * یا اولین درخواست خطا می‌داد، دیگر هیچ‌وقت تلاش نمی‌کرد و بنر همیشه خالی می‌ماند.
 * حالا تا چند بار با فاصله تلاش می‌کند و ارتفاع ثابت دارد تا جای بنر حفظ شود.
 */
@Composable
fun TapsellBanner(modifier: Modifier = Modifier) {
    if (!AdsManager.bannerEnabled) return
    val activity = LocalContext.current as? Activity ?: return

    val container = remember { BannerContainer(activity) }
    var adId by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        if (loaded || attempt >= MAX_ATTEMPTS) return@LaunchedEffect

        // کمی صبر تا SDK با ContentProvider بالا بیاید
        delay(if (attempt == 0) 1_200L else RETRY_DELAY_MS)

        runCatching {
            Tapsell.requestBannerAd(
                AdsConfig.BANNER_ZONE,
                BannerSize.BANNER_320_50,
                activity,
                object : RequestResultListener {
                    override fun onSuccess(id: String) {
                        adId = id
                        loaded = true
                        Log.d(TAG, "banner loaded")
                        runCatching {
                            Tapsell.showBannerAd(
                                id,
                                container,
                                activity,
                                object : AdStateListener.Banner {
                                    override fun onAdImpression() {
                                        Log.d(TAG, "banner impression")
                                    }

                                    override fun onAdClicked() = Unit

                                    override fun onAdFailed(message: String) {
                                        Log.d(TAG, "banner show failed: $message")
                                        loaded = false
                                        attempt += 1
                                    }
                                }
                            )
                        }.onFailure { Log.w(TAG, "banner show error: ${it.message}") }
                    }

                    override fun onFailure(message: String) {
                        Log.d(TAG, "banner request failed: $message")
                        attempt += 1
                    }
                }
            )
        }.onFailure {
            Log.w(TAG, "banner request error: ${it.message}")
            attempt += 1
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            adId?.let { runCatching { Tapsell.destroyBannerAd(it) } }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { container }
        )
    }
}
