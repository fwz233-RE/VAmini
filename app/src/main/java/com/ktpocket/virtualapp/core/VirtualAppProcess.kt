package com.ktpocket.virtualapp.core

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.ktpocket.virtualapp.utils.VLog
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Method

/**
 * 虚拟应用进程管理器
 * 负责在独立进程中启动和运行虚拟应用
 */
class VirtualAppProcess(private val context: Context) {
    
    companion object {
        private const val TAG = "VirtualAppProcess"
    }
    
    private var virtualClassLoader: ClassLoader? = null
    private var virtualApplication: Any? = null
    private var virtualContext: Context? = null
    
    /**
     * 启动虚拟应用进程
     */
    fun startVirtualAppProcess(record: StubActivityRecord): Boolean {
        return try {
            VLog.logLaunchProcess(TAG, record.packageName ?: "unknown", "开始启动虚拟应用进程")
            
            // 1. 创建虚拟ClassLoader
            if (!createVirtualClassLoader(record)) {
                VLog.e(TAG, "创建虚拟ClassLoader失败")
                return false
            }
            
            // 2. 创建虚拟Application
            if (!createVirtualApplication(record)) {
                VLog.e(TAG, "创建虚拟Application失败")
                return false
            }
            
            // 3. 启动目标Activity
            if (!startTargetActivity(record)) {
                VLog.e(TAG, "启动目标Activity失败")
                return false
            }
            
            VLog.logLaunchProcess(TAG, record.packageName ?: "unknown", "虚拟应用进程启动成功")
            true
        } catch (e: Exception) {
            VLog.e(TAG, "启动虚拟应用进程失败", e)
            false
        }
    }
    
    /**
     * 创建虚拟ClassLoader
     */
    private fun createVirtualClassLoader(record: StubActivityRecord): Boolean {
        return try {
            val apkPath = record.apkPath ?: return false
            val packageName = record.packageName ?: return false
            
            VLog.logLaunchProcess(TAG, packageName, "创建虚拟ClassLoader")
            
            // 创建虚拟应用数据目录
            val virtualCore = VirtualCore.get()
            val dataDir = virtualCore.getVirtualAppDataDir(record.userId, packageName)
            val dexOutputDir = File(dataDir, "dex")
            dexOutputDir.mkdirs()
            
            // 创建DexClassLoader
            virtualClassLoader = DexClassLoader(
                apkPath,
                dexOutputDir.absolutePath,
                null,
                context.classLoader
            )
            
            VLog.logLaunchProcess(TAG, packageName, "虚拟ClassLoader创建成功")
            true
        } catch (e: Exception) {
            VLog.e(TAG, "创建虚拟ClassLoader失败", e)
            false
        }
    }
    
    /**
     * 创建虚拟Application
     */
    private fun createVirtualApplication(record: StubActivityRecord): Boolean {
        return try {
            val packageName = record.packageName ?: return false
            val apkPath = record.apkPath ?: return false
            
            VLog.logLaunchProcess(TAG, packageName, "创建虚拟Application")
            
            // 解析APK中的Application类
            val packageInfo = getPackageInfo(apkPath) ?: return false
            val applicationClassName = packageInfo.applicationInfo?.className
                ?: "android.app.Application"
            
            VLog.d(TAG, "Application类名: $applicationClassName")
            
            // 通过反射创建Application实例
            val appClass = virtualClassLoader?.loadClass(applicationClassName)
                ?: Class.forName(applicationClassName)
            
            virtualApplication = appClass.newInstance()
            
            // 调用Application的onCreate方法 (简化版本)
            try {
                val onCreateMethod = appClass.getMethod("onCreate")
                onCreateMethod.invoke(virtualApplication)
                VLog.logLaunchProcess(TAG, packageName, "虚拟Application onCreate完成")
            } catch (e: Exception) {
                VLog.w(TAG, "调用Application.onCreate失败: ${e.message}")
            }
            
            VLog.logLaunchProcess(TAG, packageName, "虚拟Application创建成功")
            true
        } catch (e: Exception) {
            VLog.e(TAG, "创建虚拟Application失败", e)
            false
        }
    }
    
    /**
     * 启动目标Activity
     */
    private fun startTargetActivity(record: StubActivityRecord): Boolean {
        return try {
            val packageName = record.packageName ?: return false
            val originalIntent = record.intent
            
            VLog.logLaunchProcess(TAG, packageName, "启动目标Activity")
            
            // 查找启动Activity
            val launcherActivity = findLauncherActivity(record)
            if (launcherActivity == null) {
                VLog.e(TAG, "找不到启动Activity")
                return false
            }
            
            VLog.d(TAG, "目标Activity: ${launcherActivity.name}")
            
            // 创建启动Intent
            val targetIntent = Intent().apply {
                setClassName(packageName, launcherActivity.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                
                // 复制原始Intent的数据
                originalIntent?.let { original ->
                    action = original.action
                    data = original.data
                    // 复制categories
                    original.categories?.forEach { category ->
                        addCategory(category)
                    }
                    if (original.extras != null) {
                        putExtras(original.extras!!)
                    }
                }
            }
            
            // 启动Activity (简化版本 - 直接调用系统启动)
            try {
                context.startActivity(targetIntent)
                VLog.logLaunchProcess(TAG, packageName, "目标Activity启动成功")
                return true
            } catch (e: Exception) {
                VLog.e(TAG, "启动Activity失败", e)
                
                // 降级方案：尝试启动包管理器中的主Activity
                return startFallbackActivity(record)
            }
        } catch (e: Exception) {
            VLog.e(TAG, "启动目标Activity失败", e)
            false
        }
    }
    
    /**
     * 查找启动Activity
     */
    private fun findLauncherActivity(record: StubActivityRecord): ActivityInfo? {
        return try {
            val apkPath = record.apkPath ?: return null
            val packageInfo = getPackageInfo(apkPath) ?: return null
            
            // 查找MAIN/LAUNCHER Activity
            packageInfo.activities?.find { activityInfo ->
                activityInfo.name.contains("MainActivity") ||
                activityInfo.name.contains("LauncherActivity") ||
                activityInfo.name.contains("SplashActivity") ||
                activityInfo.name.endsWith("Activity")
            } ?: packageInfo.activities?.firstOrNull()
        } catch (e: Exception) {
            VLog.e(TAG, "查找启动Activity失败", e)
            null
        }
    }
    
    /**
     * 降级启动方案
     */
    private fun startFallbackActivity(record: StubActivityRecord): Boolean {
        return try {
            val packageName = record.packageName ?: return false
            VLog.logLaunchProcess(TAG, packageName, "尝试降级启动方案")
            
            // 尝试通过PackageManager启动
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                VLog.logLaunchProcess(TAG, packageName, "降级启动成功")
                return true
            }
            
            VLog.w(TAG, "降级启动失败")
            false
        } catch (e: Exception) {
            VLog.e(TAG, "降级启动失败", e)
            false
        }
    }
    
    /**
     * 获取APK包信息
     */
    private fun getPackageInfo(apkPath: String): PackageInfo? {
        return try {
            val pm = context.packageManager
            pm.getPackageArchiveInfo(
                apkPath,
                PackageManager.GET_ACTIVITIES or 
                PackageManager.GET_SERVICES or 
                PackageManager.GET_RECEIVERS or 
                PackageManager.GET_PROVIDERS
            )
        } catch (e: Exception) {
            VLog.e(TAG, "获取包信息失败", e)
            null
        }
    }
    
    /**
     * 清理虚拟进程
     */
    fun cleanup() {
        try {
            virtualApplication = null
            virtualClassLoader = null
            virtualContext = null
            VLog.d(TAG, "虚拟进程清理完成")
        } catch (e: Exception) {
            VLog.e(TAG, "清理虚拟进程失败", e)
        }
    }
}