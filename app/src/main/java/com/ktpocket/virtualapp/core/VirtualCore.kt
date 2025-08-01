package com.ktpocket.virtualapp.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.ktpocket.virtualapp.utils.FileUtils
import com.ktpocket.virtualapp.utils.VLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * VirtualApp核心管理器
 */
class VirtualCore private constructor() {
    
    companion object {
        @Volatile
        private var INSTANCE: VirtualCore? = null
        
        fun get(): VirtualCore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VirtualCore().also { INSTANCE = it }
            }
        }
    }
    
    private lateinit var context: Context
    private val installedApps = ConcurrentHashMap<String, VirtualAppInfo>()
    private var isInitialized = false
    
    fun initialize(context: Context) {
        if (isInitialized) {
            VLog.d("VirtualCore", "VirtualCore已经初始化，跳过")
            return
        }
        
        VLog.i("VirtualCore", "开始初始化VirtualCore")
        this.context = context.applicationContext
        createVirtualDirectories()
        isInitialized = true
        VLog.i("VirtualCore", "VirtualCore初始化完成")
    }
    
    fun getContext(): Context = context
    
    private fun createVirtualDirectories() {
        val virtualRoot = File(context.filesDir, "virtual")
        val dataDir = File(virtualRoot, "data")
        val userDir = File(dataDir, "user")
        val appDir = File(dataDir, "app")
        
        virtualRoot.mkdirs()
        dataDir.mkdirs()
        userDir.mkdirs()
        appDir.mkdirs()
        File(userDir, "0").mkdirs()
    }
    
    fun getVirtualAppDataDir(userId: Int, packageName: String): File {
        val virtualRoot = File(context.filesDir, "virtual")
        return File(virtualRoot, "data/user/$userId/$packageName")
    }
    
    fun getVirtualAppInstallDir(packageName: String): File {
        val virtualRoot = File(context.filesDir, "virtual")
        return File(virtualRoot, "data/app/$packageName")
    }
    
    suspend fun installPackage(apkPath: String, packageName: String? = null): InstallResult {
        return withContext(Dispatchers.IO) {
            try {
                VLog.logInstallProgress("VirtualCore", packageName ?: "unknown", "开始安装")
                
                val apkFile = File(apkPath)
                if (!apkFile.exists()) {
                    VLog.e("VirtualCore", "APK文件不存在: $apkPath")
                    return@withContext InstallResult.failure("APK文件不存在: $apkPath")
                }
                
                val packageInfo = parsePackageInfo(apkFile)
                    ?: return@withContext InstallResult.failure("无法解析APK包信息")
                
                val pkgName = packageName ?: packageInfo.packageName
                
                val installDir = getVirtualAppInstallDir(pkgName)
                installDir.mkdirs()
                
                val dataDir = getVirtualAppDataDir(0, pkgName)
                dataDir.mkdirs()
                
                val targetApk = File(installDir, "base.apk")
                apkFile.copyTo(targetApk, overwrite = true)
                
                val virtualAppInfo = VirtualAppInfo(
                    packageName = pkgName,
                    applicationLabel = packageInfo.applicationInfo?.loadLabel(context.packageManager)?.toString() ?: pkgName,
                    versionName = packageInfo.versionName ?: "1.0",
                    versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode
                    },
                    apkPath = targetApk.absolutePath,
                    dataDir = dataDir.absolutePath,
                    isInstalled = true
                )
                
                installedApps[pkgName] = virtualAppInfo
                InstallResult.success(pkgName)
            } catch (e: Exception) {
                InstallResult.failure("安装失败: ${e.message}")
            }
        }
    }
    
    private fun parsePackageInfo(apkFile: File): PackageInfo? {
        return try {
            val pm = context.packageManager
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_ACTIVITIES)
        } catch (e: Exception) {
            null
        }
    }
    
    fun getInstalledApps(): List<VirtualAppInfo> {
        return installedApps.values.toList()
    }
    
    fun getAppInfo(packageName: String): VirtualAppInfo? {
        return installedApps[packageName]
    }
    
    suspend fun uninstallPackage(packageName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val installDir = getVirtualAppInstallDir(packageName)
                val dataDir = getVirtualAppDataDir(0, packageName)
                
                installDir.deleteRecursively()
                dataDir.deleteRecursively()
                
                installedApps.remove(packageName)
                true
            } catch (e: Exception) {
                false
            }
        }
    }
    
    fun launchApp(packageName: String, userId: Int = 0): Boolean {
        val appInfo = installedApps[packageName] ?: run {
            VLog.e("VirtualCore", "虚拟应用不存在: $packageName")
            return false
        }
        
        try {
            VLog.logLaunchProcess("VirtualCore", packageName, "准备启动虚拟应用")
            
            // 创建启动虚拟应用的Intent
            val originalIntent = createLaunchIntent(appInfo)
            
            // 创建StubActivityRecord
            val stubRecord = StubActivityRecord(
                intent = originalIntent,
                packageName = packageName,
                apkPath = appInfo.apkPath,
                userId = userId
            )
            
            // 创建Stub Intent启动VirtualLauncherActivity
            val stubIntent = Intent(context, VirtualLauncherActivity::class.java).apply {
                // 保存StubActivityRecord到Intent
                stubRecord.saveToIntent(this)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            
            context.startActivity(stubIntent)
            VLog.logVirtualAppInfo("VirtualCore", packageName, "Stub Activity启动成功")
            return true
        } catch (e: Exception) {
            VLog.e("VirtualCore", "启动虚拟应用失败: $packageName", e)
            return false
        }
    }
    
    /**
     * 创建启动Intent
     */
    private fun createLaunchIntent(appInfo: VirtualAppInfo): Intent {
        return try {
            // 解析APK获取启动Activity
            val packageInfo = parsePackageInfo(File(appInfo.apkPath))
            val launcherActivity = packageInfo?.activities?.find { activityInfo ->
                activityInfo.name.contains("MainActivity") ||
                activityInfo.name.contains("LauncherActivity") ||
                activityInfo.name.contains("SplashActivity") ||
                activityInfo.name.endsWith("Activity")
            }
            
            Intent().apply {
                if (launcherActivity != null) {
                    setClassName(appInfo.packageName, launcherActivity.name)
                    VLog.d("VirtualCore", "找到启动Activity: ${launcherActivity.name}")
                } else {
                    // 降级方案：使用包名
                    setPackage(appInfo.packageName)
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    VLog.w("VirtualCore", "未找到具体Activity，使用包名启动")
                }
                
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        } catch (e: Exception) {
            VLog.e("VirtualCore", "创建启动Intent失败", e)
            Intent().apply {
                setPackage(appInfo.packageName)
                action = Intent.ACTION_MAIN
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}