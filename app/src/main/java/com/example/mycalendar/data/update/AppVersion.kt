package com.example.mycalendar.data.update

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Версия установленного APK — то, с чем сравнивается тег последнего релиза. */
@Singleton
class AppVersion @Inject constructor(
    @ApplicationContext context: Context,
) {
    val versionName: String
    val versionCode: Long

    init {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        versionName = info.versionName.orEmpty()
        versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }
}
