package com.ktpocket.virtualapp.core

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.ktpocket.virtualapp.utils.VLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.io.IOUtils
import java.io.File
import java.io.FileOutputStream

/**
 * APK资源管理器
 */
class ApkManager(private val context: Context) {
    
    suspend fun getAssetsApkList(): List<ApkInfo> {
        return withContext(Dispatchers.IO) {
            try {
                VLog.d("ApkManager", "开始扫描assets中的APK文件")
                val apkFiles = context.assets.list("apk") ?: arrayOf()
                val apkList = apkFiles.filter { it.endsWith(".apk") }.map { fileName ->
                    parseApkFromAssets(fileName)
                }.filterNotNull()
                VLog.i("ApkManager", "扫描完成，找到${apkList.size}个APK文件")
                apkList
            } catch (e: Exception) {
                VLog.e("ApkManager", "扫描APK文件失败", e)
                emptyList()
            }
        }
    }
    
    private suspend fun parseApkFromAssets(fileName: String): ApkInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val tempApk = copyApkFromAssets(fileName) ?: return@withContext null
                val packageInfo = parsePackageInfo(tempApk) ?: return@withContext null
                
                val applicationInfo = packageInfo.applicationInfo
                applicationInfo?.sourceDir = tempApk.absolutePath
                applicationInfo?.publicSourceDir = tempApk.absolutePath
                
                val pm = context.packageManager
                
                ApkInfo(
                    fileName = fileName,
                    packageName = packageInfo.packageName,
                    applicationLabel = applicationInfo?.loadLabel(pm)?.toString() 
                        ?: packageInfo.packageName,
                    versionName = packageInfo.versionName ?: "1.0",
                    versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode
                    },
                    icon = applicationInfo?.loadIcon(pm),
                    tempApkPath = tempApk.absolutePath,
                    size = getApkSizeFromAssets(fileName)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
    
    private fun copyApkFromAssets(fileName: String): File? {
        return try {
            val tempDir = File(context.cacheDir, "temp_apk")
            tempDir.mkdirs()
            
            val tempApk = File(tempDir, fileName)
            
            context.assets.open("apk/$fileName").use { input ->
                FileOutputStream(tempApk).use { output ->
                    IOUtils.copy(input, output)
                }
            }
            
            tempApk
        } catch (e: Exception) {
            null
        }
    }
    
    private fun parsePackageInfo(apkFile: File): android.content.pm.PackageInfo? {
        return try {
            val pm = context.packageManager
            pm.getPackageArchiveInfo(
                apkFile.absolutePath, 
                PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES
            )
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getApkSizeFromAssets(fileName: String): Long {
        return try {
            context.assets.openFd("apk/$fileName").use { fd ->
                fd.length
            }
        } catch (e: Exception) {
            0L
        }
    }
    
    suspend fun installApkFromAssets(apkInfo: ApkInfo): InstallResult {
        return withContext(Dispatchers.IO) {
            try {
                val virtualCore = VirtualCore.get()
                
                val tempApk = File(apkInfo.tempApkPath)
                if (!tempApk.exists()) {
                    return@withContext InstallResult.failure("临时APK文件不存在")
                }
                
                val result = virtualCore.installPackage(tempApk.absolutePath, apkInfo.packageName)
                
                tempApk.delete()
                
                result
            } catch (e: Exception) {
                InstallResult.failure("安装失败: ${e.message}")
            }
        }
    }
    
    fun cleanTempFiles() {
        try {
            val tempDir = File(context.cacheDir, "temp_apk")
            if (tempDir.exists()) {
                tempDir.deleteRecursively()
            }
        } catch (e: Exception) {
            // 忽略错误
        }
    }
}