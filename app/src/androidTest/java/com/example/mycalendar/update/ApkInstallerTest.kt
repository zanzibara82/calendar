package com.example.mycalendar.update

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.data.update.ApkInstaller
import com.example.mycalendar.data.update.ApkSignatureMismatchException
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Проверка подписи загруженного APK на реальном устройстве.
 *
 * Копия установленного APK обязана проходить сверку: на Android 9
 * `getPackageArchiveInfo` с `GET_SIGNING_CERTIFICATES` отдаёт `signingInfo = null`,
 * и без отката на `GET_SIGNATURES` корректный файл отвергался как чужой.
 */
@RunWith(AndroidJUnit4::class)
class ApkInstallerTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val installer = ApkInstaller(context, OkHttpClient())

    @Test
    fun verifySignature_acceptsCopyOfInstalledApk() {
        val copy = File(context.cacheDir, "installed-copy.apk")
        try {
            File(context.applicationInfo.sourceDir).copyTo(copy, overwrite = true)
            installer.verifySignature(copy)
        } finally {
            copy.delete()
        }
    }

    @Test(expected = ApkSignatureMismatchException::class)
    fun verifySignature_rejectsFileWithoutSignature() {
        val broken = File(context.cacheDir, "not-an-apk.apk")
        try {
            broken.writeBytes(ByteArray(1024) { 'z'.code.toByte() })
            installer.verifySignature(broken)
        } finally {
            broken.delete()
        }
    }
}
