package com.ktpocket.virtualapp.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * 简化版Hook管理器
 */
class HookManager private constructor() {
    
    companion object {
        @Volatile
        private var INSTANCE: HookManager? = null
        
        fun get(): HookManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: HookManager().also { INSTANCE = it }
            }
        }
    }
    
    private val packageRedirects = ConcurrentHashMap<String, String>()
    private var isHookEnabled = false
    
    fun initialize() {
        if (isHookEnabled) return
        isHookEnabled = true
    }
    
    fun addPackageRedirect(originalPackage: String, virtualPackage: String) {
        packageRedirects[originalPackage] = virtualPackage
    }
    
    fun removePackageRedirect(originalPackage: String) {
        packageRedirects.remove(originalPackage)
    }
    
    fun hookQueryIntentActivities(context: Context, intent: Intent, flags: Int): List<ResolveInfo> {
        if (!isHookEnabled) {
            return emptyList()
        }
        
        val packageName = intent.`package`
        if (packageName != null && VirtualCore.get().getAppInfo(packageName) != null) {
            return createVirtualResolveInfo(context, intent, packageName)
        }
        
        when (intent.action) {
            Intent.ACTION_MAIN -> {
                if (intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
                    return getVirtualLauncherActivities(context)
                }
            }
        }
        
        return emptyList()
    }
    
    private fun createVirtualResolveInfo(context: Context, intent: Intent, packageName: String): List<ResolveInfo> {
        val virtualApp = VirtualCore.get().getAppInfo(packageName) ?: return emptyList()
        
        try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(
                virtualApp.apkPath,
                PackageManager.GET_ACTIVITIES
            ) ?: return emptyList()
            
            val activities = packageInfo.activities ?: return emptyList()
            
            return activities.filter { activityInfo ->
                intent.component?.className == activityInfo.name ||
                activityInfo.name.contains("MainActivity")
            }.map { activityInfo ->
                createResolveInfo(activityInfo.packageName, activityInfo.name)
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }
    
    private fun getVirtualLauncherActivities(context: Context): List<ResolveInfo> {
        val virtualApps = VirtualCore.get().getInstalledApps()
        return virtualApps.mapNotNull { appInfo ->
            try {
                val pm = context.packageManager
                val packageInfo = pm.getPackageArchiveInfo(
                    appInfo.apkPath,
                    PackageManager.GET_ACTIVITIES
                ) ?: return@mapNotNull null
                
                val launcherActivity = packageInfo.activities?.find { activityInfo ->
                    activityInfo.name.contains("MainActivity") ||
                    activityInfo.name.contains("LauncherActivity") ||
                    activityInfo.name.endsWith("Activity")
                }
                
                launcherActivity?.let {
                    createResolveInfo(appInfo.packageName, it.name, appInfo.applicationLabel)
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    
    private fun createResolveInfo(packageName: String, activityName: String, label: String? = null): ResolveInfo {
        val resolveInfo = ResolveInfo()
        
        resolveInfo.activityInfo = android.content.pm.ActivityInfo().apply {
            this.packageName = packageName
            this.name = activityName
            this.exported = true
        }
        
        resolveInfo.labelRes = 0
        resolveInfo.nonLocalizedLabel = label ?: packageName
        
        return resolveInfo
    }
    
    fun hookStartActivity(context: Context, intent: Intent): Intent? {
        if (!isHookEnabled) return intent
        
        val packageName = intent.component?.packageName
            ?: intent.`package`
            ?: return intent
        
        val virtualApp = VirtualCore.get().getAppInfo(packageName)
        if (virtualApp != null) {
            return createVirtualLaunchIntent(context, virtualApp, intent)
        }
        
        return intent
    }
    
    private fun createVirtualLaunchIntent(context: Context, virtualApp: VirtualAppInfo, originalIntent: Intent): Intent {
        val virtualIntent = Intent(context, VirtualLauncherActivity::class.java)
        virtualIntent.putExtra("virtual_package_name", virtualApp.packageName)
        virtualIntent.putExtra("virtual_apk_path", virtualApp.apkPath)
        virtualIntent.putExtra("original_intent", originalIntent)
        virtualIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        
        return virtualIntent
    }
    
    fun disable() {
        isHookEnabled = false
        packageRedirects.clear()
    }
}