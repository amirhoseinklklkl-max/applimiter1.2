package ir.amir.applimiter

import android.app.Application
import ir.amir.applimiter.ads.AdsConfig
import com.adivery.sdk.Adivery

/**
 * کلاس Application برنامه.
 * SDK ادیوری باید فقط یک بار و در همین جا مقداردهی اولیه شود.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Adivery.configure(this, AdsConfig.APP_ID)
    }
}
