package com.ktpocket.virtualapp.core

import android.graphics.drawable.Drawable

/**
 * 虚拟应用信息
 */
data class VirtualAppInfo(
    val packageName: String,
    val applicationLabel: String,
    val versionName: String,
    val versionCode: Int,
    val apkPath: String,
    val dataDir: String,
    val isInstalled: Boolean = true,
    val userId: Int = 0
)

/**
 * 虚拟环境
 */
data class VirtualEnvironment(
    val userId: Int,
    val dataDir: String,
    val cacheDir: String
)

/**
 * 安装结果
 */
sealed class InstallResult {
    data class Success(val packageName: String) : InstallResult()
    data class Failure(val error: String) : InstallResult()
    
    companion object {
        fun success(packageName: String) = Success(packageName)
        fun failure(error: String) = Failure(error)
    }
    
    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
}

/**
 * APK信息数据类
 */
data class ApkInfo(
    val fileName: String,
    val packageName: String,
    val applicationLabel: String,
    val versionName: String,
    val versionCode: Int,
    val icon: Drawable?,
    val tempApkPath: String,
    val size: Long
) {
    /**
     * 格式化文件大小
     */
    fun getFormattedSize(): String {
        return when {
            size < 1024 -> "${size}B"
            size < 1024 * 1024 -> "${size / 1024}KB"
            else -> "${size / (1024 * 1024)}MB"
        }
    }
}

/**
 * 虚拟ResolveInfo
 */
data class VirtualResolveInfo(
    val packageName: String,
    val activityName: String,
    val label: String,
    val virtualApp: VirtualAppInfo
)