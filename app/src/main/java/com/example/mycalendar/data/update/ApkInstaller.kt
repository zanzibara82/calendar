package com.example.mycalendar.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.mycalendar.domain.model.UpdateInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Загруженный APK подписан не тем же ключом, что установленное приложение. */
class ApkSignatureMismatchException : Exception()

/**
 * Загрузка релизного APK и передача его системному установщику.
 *
 * Подпись загруженного файла сверяется с установленным приложением до запуска
 * установщика: релиз лежит в публичном репозитории, и подменённый ассет должен
 * упереться в проверку, а не в «Обновление не установлено» от системы.
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {

    /** Выдано ли приложению право устанавливать пакеты (Android 8+ — вручную в настройках). */
    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Экран системных настроек, где включается установка из этого источника. */
    fun installSettingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Скачивает APK в кэш приложения и проверяет подпись.
     *
     * @throws ApkSignatureMismatchException если файл подписан другим ключом.
     * @throws java.io.IOException при сетевой ошибке или ошибке записи.
     */
    suspend fun download(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val target = File(dir, APK_NAME)
        val request = Request.Builder().url(info.apkUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val temp = File(dir, "$APK_NAME.tmp")
            response.body.byteStream().use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
        verifySignature(target)
        target
    }

    /** Интент системного установщика для скачанного файла. */
    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun verifySignature(apk: File) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val downloaded = manager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw ApkSignatureMismatchException()
        val installed = manager.getPackageInfo(context.packageName, flags)
        if (certificates(downloaded) != certificates(installed)) {
            throw ApkSignatureMismatchException()
        }
    }

    private fun certificates(info: PackageInfo): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
                ?.map { sha256(it.toByteArray()) }
                ?.toSet()
                .orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures
                ?.map { sha256(it.toByteArray()) }
                ?.toSet()
                .orEmpty()
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private companion object {
        const val CACHE_DIR = "updates"
        const val APK_NAME = "update.apk"
        const val AUTHORITY_SUFFIX = ".fileprovider"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
