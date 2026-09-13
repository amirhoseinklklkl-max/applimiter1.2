package ir.amir.applimiter.ads

import ir.amir.applimiter.BuildConfig

/**
 * تنظیمات تبلیغات ادیوری.
 * App ID و جایگاه‌ها (Placement) از gradle.properties خوانده می‌شوند تا داخل کد hardcode نشوند.
 */
object AdsConfig {

    /** کلید اپلیکیشن در پنل ادیوری؛ در Application.onCreate به Adivery.configure داده می‌شود. */
    val APP_ID: String = BuildConfig.ADIVERY_APP_ID.trim()

    /** جایگاه تبلیغ تمام‌صفحه (Interstitial) که بعد از ورود کاربر به برنامه نمایش داده می‌شود. */
    val INTERSTITIAL_PLACEMENT: String = BuildConfig.ADIVERY_PLACEMENT_INTERSTITIAL.trim()

    /** جایگاه بنر پایین لیست برنامه‌ها. خالی باشد، بنری نمایش داده نمی‌شود. */
    val BANNER_PLACEMENT: String = BuildConfig.ADIVERY_PLACEMENT_BANNER.trim()

    /** حداقل فاصله‌ی بین دو تبلیغ آنی؛ جلوی اذیت شدن کاربر را می‌گیرد. */
    const val INTERSTITIAL_MIN_INTERVAL_MS = 3 * 60_000L

    private val placeholders = setOf(
        "",
        "YOUR_ADIVERY_APP_ID",
        "YOUR_INTERSTITIAL_PLACEMENT_ID",
        "YOUR_BANNER_PLACEMENT_ID"
    )

    fun isConfigured(value: String): Boolean = value !in placeholders
}
