package com.ktpocket.virtualapp.core

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import com.ktpocket.virtualapp.utils.VLog
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.zip.ZipFile

/**
 * 虚拟Application管理器
 * 负责创建和管理真正的虚拟Application实例
 * 参考VirtualApp的VClient.bindApplication实现
 */
class VirtualApplicationManager(private val context: Context) {
    
    companion object {
        private const val TAG = "VirtualApplicationManager"
    }
    
    private var virtualApplication: Application? = null
    private var virtualClassLoader: ClassLoader? = null
    private var virtualContext: VirtualContext? = null
    private var isApplicationCreated = false
    
    /**
     * 创建真正的虚拟Application
     * 参考VirtualApp的bindApplication流程
     */
    fun createVirtualApplication(
        packageName: String,
        apkPath: String,
        userId: Int = 0
    ): Application? {
        return try {
            VLog.logLaunchProcess(TAG, packageName, "开始创建虚拟Application")
            
            if (isApplicationCreated) {
                VLog.d(TAG, "虚拟Application已存在")
                return virtualApplication
            }
            
            // 1. 解析APK获取ApplicationInfo
            val applicationInfo = parseApplicationInfo(apkPath, packageName)
                ?: return null
            
            // 1.5 关键修复：处理Native Libraries
            setupNativeLibraries(packageName, apkPath, applicationInfo, userId)
            
            // 2. 创建虚拟ClassLoader
            virtualClassLoader = createVirtualClassLoader(apkPath, packageName, userId)
                ?: return null
            
            // 3. 创建虚拟Context
            virtualContext = createVirtualContext(applicationInfo, packageName, userId, apkPath)
                ?: return null
            
            // 4. 创建LoadedApk对象
            val loadedApk = createLoadedApk(applicationInfo, virtualClassLoader!!)
                ?: return null
            
            // 5. 使用系统方法创建真正的Application
            virtualApplication = createApplicationFromLoadedApk(loadedApk, applicationInfo)
                ?: return null
            
            // 6. 初始化Application
            initializeApplication(virtualApplication!!, packageName)
            
            isApplicationCreated = true
            VLog.logVirtualAppInfo(TAG, packageName, "虚拟Application创建成功")
            
            virtualApplication
        } catch (e: Exception) {
            VLog.e(TAG, "创建虚拟Application失败", e)
            null
        }
    }
    
    /**
     * 解析APK获取ApplicationInfo
     */
    private fun parseApplicationInfo(apkPath: String, packageName: String): ApplicationInfo? {
        return try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(
                apkPath,
                PackageManager.GET_META_DATA or PackageManager.GET_ACTIVITIES
            ) ?: return null
            
            val applicationInfo = packageInfo.applicationInfo ?: return null
            applicationInfo.sourceDir = apkPath
            applicationInfo.publicSourceDir = apkPath
            applicationInfo.packageName = packageName
            
            // 设置数据目录
            val virtualCore = VirtualCore.get()
            val dataDir = virtualCore.getVirtualAppDataDir(0, packageName)
            applicationInfo.dataDir = dataDir.absolutePath
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                applicationInfo.deviceProtectedDataDir = File(dataDir, "device_protected").absolutePath
            }
            
            VLog.d(TAG, "解析ApplicationInfo成功: ${applicationInfo.className}")
            applicationInfo
        } catch (e: Exception) {
            VLog.e(TAG, "解析ApplicationInfo失败", e)
            null
        }
    }
    
    /**
     * 创建虚拟ClassLoader
     */
    private fun createVirtualClassLoader(
        apkPath: String,
        packageName: String,
        userId: Int
    ): ClassLoader? {
        return try {
            val virtualCore = VirtualCore.get()
            val dataDir = virtualCore.getVirtualAppDataDir(userId, packageName)
            val dexOutputDir = File(dataDir, "dex")
            dexOutputDir.mkdirs()
            
            // 关键修复：创建包含Native Library路径的DexClassLoader
            val nativeLibraryDir = File(dataDir, "lib").absolutePath
            VLog.d(TAG, "DexClassLoader nativeLibraryDir: $nativeLibraryDir")
            
            val appClassLoader = DexClassLoader(
                apkPath,
                dexOutputDir.absolutePath,
                nativeLibraryDir, // 包含虚拟应用的native库路径
                null // 完全独立，不使用父ClassLoader
            )
            
            // 创建ProxyClassLoader用于依赖库隔离
            ProxyClassLoader(
                parentClassLoader = context.classLoader,
                appClassLoader = appClassLoader
            )
        } catch (e: Exception) {
            VLog.e(TAG, "创建虚拟ClassLoader失败", e)
            null
        }
    }
    
    /**
     * 创建虚拟Context
     */
    private fun createVirtualContext(
        applicationInfo: ApplicationInfo,
        packageName: String,
        userId: Int,
        apkPath: String
    ): VirtualContext? {
        return try {
            if (virtualClassLoader == null) return null
            
            // 优先尝试创建虚拟Resources
            var virtualResources = createVirtualResources(apkPath, packageName)
            
            // 如果自定义Resources创建失败，尝试降级方案
            if (virtualResources == null) {
                VLog.w(TAG, "自定义Resources创建失败，尝试降级方案")
                virtualResources = createFallbackResources(packageName, apkPath)
            }
            
            if (virtualResources == null) {
                VLog.e(TAG, "所有Resources创建方案都失败")
                return null
            }
            
            VirtualContext(
                baseContext = context,
                virtualPackageName = packageName,
                virtualApplicationInfo = applicationInfo,
                virtualClassLoader = virtualClassLoader!!,
                virtualResources = virtualResources
            )
        } catch (e: Exception) {
            VLog.e(TAG, "创建虚拟Context失败", e)
            null
        }
    }
    
    /**
     * 降级方案：使用Android官方方法创建Resources
     */
    private fun createFallbackResources(packageName: String, apkPath: String): Resources? {
        return try {
            VLog.d(TAG, "尝试降级方案 - 使用createPackageContext")
            
            // 方案1：使用PackageManager.getResourcesForApplication
            try {
                val pm = context.packageManager
                val packageInfo = pm.getPackageArchiveInfo(apkPath, 0)
                val appInfo = packageInfo?.applicationInfo
                if (appInfo != null) {
                    appInfo.sourceDir = apkPath
                    appInfo.publicSourceDir = apkPath
                    
                    val resources = pm.getResourcesForApplication(appInfo)
                    VLog.d(TAG, "降级方案1成功 - PackageManager.getResourcesForApplication")
                    return resources
                }
            } catch (e: Exception) {
                VLog.e(TAG, "降级方案1失败", e)
            }
            
            // 方案2：使用宿主Resources作为最后的回退
            VLog.w(TAG, "使用宿主Resources作为最后回退")
            context.resources
        } catch (e: Exception) {
            VLog.e(TAG, "降级方案也失败", e)
            null
        }
    }
    
    /**
     * 创建虚拟应用的Resources
     * 参考VirtualApp的实现方式，修复Resources NotFoundException
     */
    private fun createVirtualResources(apkPath: String, packageName: String): Resources? {
        return try {
            VLog.d(TAG, "创建虚拟Resources - APK路径: $apkPath")
            
            // 检查APK文件是否存在
            val apkFile = File(apkPath)
            VLog.d(TAG, "检查APK文件 - 路径: $apkPath")
            VLog.d(TAG, "APK文件信息 - 存在: ${apkFile.exists()}, 大小: ${if (apkFile.exists()) apkFile.length() else 0}bytes")
            VLog.d(TAG, "APK文件父目录: ${apkFile.parentFile?.absolutePath}")
            
            if (!apkFile.exists()) {
                VLog.e(TAG, "APK文件不存在: $apkPath")
                
                // 尝试列出父目录的内容来调试
                try {
                    val parentDir = apkFile.parentFile
                    if (parentDir?.exists() == true) {
                        val files = parentDir.listFiles()
                        VLog.d(TAG, "父目录内容: ${files?.map { it.name }?.joinToString(", ") ?: "空"}")
                    } else {
                        VLog.e(TAG, "父目录也不存在: ${parentDir?.absolutePath}")
                    }
                } catch (e: Exception) {
                    VLog.e(TAG, "列出父目录失败", e)
                }
                
                return null
            }
            
            // 参考VirtualApp的方式创建AssetManager
            val assetManagerClass = AssetManager::class.java
            val assetManager = assetManagerClass.newInstance()
            
            // 调用addAssetPath添加虚拟应用的APK路径
            val addAssetPathMethod = assetManagerClass.getDeclaredMethod("addAssetPath", String::class.java)
            addAssetPathMethod.isAccessible = true
            val result = addAssetPathMethod.invoke(assetManager, apkPath) as Int
            
            VLog.d(TAG, "addAssetPath虚拟APK结果: $result (>0表示成功)")
            VLog.d(TAG, "AssetManager实例: $assetManager")
            
            if (result == 0) {
                VLog.e(TAG, "addAssetPath失败，返回值为0，APK路径: $apkPath")
                
                // 尝试测试Resources.getSystem()方法作为对比
                try {
                    val systemRes = Resources.getSystem()
                    VLog.d(TAG, "系统Resources测试: $systemRes")
                } catch (e: Exception) {
                    VLog.e(TAG, "系统Resources测试失败", e)
                }
                
                return null
            }
            
            // 尝试添加系统framework资源
            try {
                val frameworkPath = "/system/framework/framework-res.apk"
                val frameworkResult = addAssetPathMethod.invoke(assetManager, frameworkPath) as Int
                VLog.d(TAG, "addAssetPath framework结果: $frameworkResult")
            } catch (e: Exception) {
                VLog.w(TAG, "添加framework资源失败: ${e.message}")
            }
            
            // 同时添加宿主应用的资源路径，避免缺失系统资源
            try {
                val hostApkPath = context.applicationInfo.sourceDir
                val hostResult = addAssetPathMethod.invoke(assetManager, hostApkPath) as Int
                VLog.d(TAG, "addAssetPath宿主APK结果: $hostResult - 路径: $hostApkPath")
            } catch (e: Exception) {
                VLog.w(TAG, "添加宿主APK路径失败: ${e.message}")
            }
            
            // 关键修复：添加AppCompat库资源
            try {
                VLog.d(TAG, "尝试添加AppCompat库资源")
                val appCompatPaths = findAppCompatLibraryPaths()
                for (appCompatPath in appCompatPaths) {
                    try {
                        val appCompatResult = addAssetPathMethod.invoke(assetManager, appCompatPath) as Int
                        VLog.d(TAG, "addAssetPath AppCompat结果: $appCompatResult - 路径: $appCompatPath")
                        if (appCompatResult > 0) {
                            VLog.d(TAG, "成功添加AppCompat资源: $appCompatPath")
                        }
                    } catch (e: Exception) {
                        VLog.d(TAG, "添加AppCompat路径失败: $appCompatPath - ${e.message}")
                    }
                }
            } catch (e: Exception) {
                VLog.w(TAG, "查找AppCompat库失败: ${e.message}")
            }
            
            // 创建Resources实例
            val hostRes = context.resources
            val virtualResources = Resources(assetManager, hostRes.displayMetrics, hostRes.configuration)
            
            VLog.d(TAG, "虚拟Resources创建成功，包名: $packageName")
            VLog.d(TAG, "虚拟Resources实例: $virtualResources")
            
            // 尝试测试资源访问
            try {
                val identifier = virtualResources.getIdentifier("app_name", "string", packageName)
                VLog.d(TAG, "资源测试 - app_name标识符: $identifier")
                
                if (identifier != 0) {
                    val appName = virtualResources.getString(identifier)
                    VLog.d(TAG, "资源测试 - app_name值: $appName")
                }
            } catch (e: Exception) {
                VLog.w(TAG, "资源测试失败: ${e.message}")
            }
            
            // 尝试测试具体的资源ID #0x7f080059
            try {
                val resourceId = 0x7f080059
                val resourceName = virtualResources.getResourceName(resourceId)
                VLog.d(TAG, "问题资源ID测试 - 0x7f080059 -> $resourceName")
            } catch (e: Exception) {
                VLog.e(TAG, "问题资源ID测试失败 - 0x7f080059", e)
            }
            
            virtualResources
        } catch (e: Exception) {
            VLog.e(TAG, "创建虚拟Resources失败", e)
            null
        }
    }
    
    /**
     * 创建LoadedApk对象
     * 参考VirtualApp的AppBindData创建流程
     */
    private fun createLoadedApk(
        applicationInfo: ApplicationInfo,
        classLoader: ClassLoader
    ): Any? {
        return try {
            VLog.d(TAG, "创建LoadedApk对象")
            
            // 通过反射创建LoadedApk
            // 在真实的VirtualApp中，这里使用更复杂的LoadedApk创建逻辑
            // 我们使用简化版本，直接使用ApplicationInfo
            
            // 创建一个简化的LoadedApk数据结构
            LoadedApkData(
                applicationInfo = applicationInfo,
                classLoader = classLoader
            )
        } catch (e: Exception) {
            VLog.e(TAG, "创建LoadedApk失败", e)
            null
        }
    }
    
    /**
     * 使用系统方法创建真正的Application
     * 参考VirtualApp的LoadedApk.makeApplication调用
     */
    private fun createApplicationFromLoadedApk(
        loadedApk: Any,
        applicationInfo: ApplicationInfo
    ): Application? {
        return try {
            VLog.d(TAG, "从LoadedApk创建Application")
            
            // 获取Application类名
            val applicationClassName = applicationInfo.className
                ?: "android.app.Application"
            
            VLog.d(TAG, "Application类名: $applicationClassName")
            
            // 通过虚拟ClassLoader加载Application类
            val applicationClass = virtualClassLoader!!.loadClass(applicationClassName)
            
            // 创建Application实例
            val constructor = applicationClass.getConstructor()
            val app = constructor.newInstance() as Application
            
            // 设置Application的Context
            // 这里使用反射设置Application的基础Context
            setApplicationContext(app, virtualContext!!)
            
            VLog.d(TAG, "Application实例创建成功")
            app
        } catch (e: Exception) {
            VLog.e(TAG, "创建Application失败", e)
            
            // 降级方案：创建默认Application
            try {
                val defaultApp = Application()
                setApplicationContext(defaultApp, virtualContext!!)
                VLog.w(TAG, "使用默认Application")
                defaultApp
            } catch (e2: Exception) {
                VLog.e(TAG, "创建默认Application也失败", e2)
                null
            }
        }
    }
    
    /**
     * 设置Application的Context
     */
    private fun setApplicationContext(application: Application, context: Context) {
        try {
            // 通过反射设置Application的Context
            val attachMethod = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            attachMethod.isAccessible = true
            attachMethod.invoke(application, context)
            
            VLog.d(TAG, "Application Context设置成功")
        } catch (e: Exception) {
            VLog.e(TAG, "设置Application Context失败", e)
        }
    }
    
    /**
     * 初始化Application
     * 参考VirtualApp的callApplicationOnCreate
     */
    private fun initializeApplication(application: Application, packageName: String) {
        try {
            VLog.logLaunchProcess(TAG, packageName, "初始化Application")
            
            // 调用Application的onCreate方法
            application.onCreate()
            
            VLog.logVirtualAppInfo(TAG, packageName, "Application初始化完成")
        } catch (e: Exception) {
            VLog.e(TAG, "初始化Application失败", e)
        }
    }
    
    /**
     * 获取虚拟Application
     */
    fun getVirtualApplication(): Application? = virtualApplication
    
    /**
     * 获取虚拟Context
     */
    fun getVirtualContext(): Context? = virtualContext
    
    /**
     * 获取虚拟ClassLoader
     */
    fun getVirtualClassLoader(): ClassLoader? = virtualClassLoader
    
    /**
     * 检查虚拟Application是否已创建
     */
    fun isApplicationReady(): Boolean = isApplicationCreated && virtualApplication != null
    
    /**
     * 清理虚拟Application
     */
    fun cleanup() {
        try {
            virtualApplication = null
            virtualClassLoader = null
            virtualContext = null
            isApplicationCreated = false
            VLog.d(TAG, "虚拟Application清理完成")
        } catch (e: Exception) {
            VLog.e(TAG, "清理虚拟Application失败", e)
        }
    }
    
    /**
     * 查找AppCompat库的APK路径
     * 这对于解决Theme.AppCompat主题问题至关重要
     */
    private fun findAppCompatLibraryPaths(): List<String> {
        val appCompatPaths = mutableListOf<String>()
        
        try {
            VLog.d(TAG, "开始查找AppCompat库路径")
            
            // 方法1: 通过PackageManager查找已安装的支持库
            try {
                val pm = context.packageManager
                val packages = pm.getInstalledPackages(0)
                
                for (packageInfo in packages) {
                    val packageName = packageInfo.packageName
                    
                    // 查找包含AppCompat相关的包
                    if (packageName.contains("androidx.appcompat") || 
                        packageName.contains("android.support.v7") ||
                        packageName.contains("appcompat")) {
                        
                        try {
                            val appInfo = pm.getApplicationInfo(packageName, 0)
                            val apkPath = appInfo.sourceDir
                            if (apkPath != null && File(apkPath).exists()) {
                                appCompatPaths.add(apkPath)
                                VLog.d(TAG, "找到AppCompat相关包: $packageName - $apkPath")
                            }
                        } catch (e: Exception) {
                            VLog.d(TAG, "获取AppCompat包信息失败: $packageName - ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                VLog.w(TAG, "通过PackageManager查找AppCompat失败: ${e.message}")
            }
            
            // 方法2: 尝试常见的系统路径
            val commonPaths = listOf(
                "/system/framework/androidx.appcompat.jar",
                "/system/framework/appcompat-v7.jar", 
                "/system/framework/support-v7-appcompat.jar",
                "/system/priv-app/AppCompat/AppCompat.apk",
                "/system/app/AppCompat/AppCompat.apk"
            )
            
            for (path in commonPaths) {
                try {
                    val file = File(path)
                    if (file.exists()) {
                        appCompatPaths.add(path)
                        VLog.d(TAG, "找到系统AppCompat库: $path")
                    }
                } catch (e: Exception) {
                    VLog.d(TAG, "检查系统路径失败: $path - ${e.message}")
                }
            }
            
            // 方法3: 尝试从宿主应用的ClassLoader中查找
            try {
                val hostClassLoader = context.classLoader
                
                // 尝试查找AppCompat相关的类
                val appCompatClasses = listOf(
                    "androidx.appcompat.app.AppCompatActivity",
                    "androidx.appcompat.widget.AppCompatTextView",
                    "android.support.v7.app.AppCompatActivity"
                )
                
                for (className in appCompatClasses) {
                    try {
                        val clazz = hostClassLoader.loadClass(className)
                        VLog.d(TAG, "宿主应用包含AppCompat类: $className")
                        
                        // 如果宿主应用包含AppCompat，使用宿主APK路径
                        val hostApkPath = context.applicationInfo.sourceDir
                        if (!appCompatPaths.contains(hostApkPath)) {
                            appCompatPaths.add(hostApkPath)
                            VLog.d(TAG, "宿主应用包含AppCompat，已添加: $hostApkPath")
                        }
                        break
                    } catch (e: ClassNotFoundException) {
                        VLog.d(TAG, "宿主应用不包含: $className")
                    }
                }
            } catch (e: Exception) {
                VLog.w(TAG, "从宿主ClassLoader查找AppCompat失败: ${e.message}")
            }
            
            VLog.d(TAG, "AppCompat库查找完成，找到${appCompatPaths.size}个路径")
            
        } catch (e: Exception) {
            VLog.e(TAG, "查找AppCompat库路径失败", e)
        }
        
        return appCompatPaths
    }
    
    /**
     * 设置虚拟应用的Native Libraries
     * 参考VirtualApp的实现，确保native库能正确加载
     */
    private fun setupNativeLibraries(packageName: String, apkPath: String, applicationInfo: ApplicationInfo, userId: Int) {
        try {
            VLog.d(TAG, "开始设置Native Libraries - $packageName")
            
            // 1. 创建虚拟应用的lib目录
            val virtualLibDir = File(VirtualCore.get().getVirtualAppDataDir(userId, packageName), "lib")
            if (!virtualLibDir.exists()) {
                virtualLibDir.mkdirs()
                VLog.d(TAG, "创建虚拟lib目录: ${virtualLibDir.absolutePath}")
            }
            
            // 2. 设置ApplicationInfo的nativeLibraryDir
            applicationInfo.nativeLibraryDir = virtualLibDir.absolutePath
            VLog.d(TAG, "设置nativeLibraryDir: ${applicationInfo.nativeLibraryDir}")
            
            // 3. 尝试从APK提取native libraries
            try {
                extractNativeLibraries(apkPath, virtualLibDir)
            } catch (e: Exception) {
                VLog.w(TAG, "提取Native Libraries失败: ${e.message}")
                // 即使提取失败也继续，某些应用可能不需要native库
            }
            
            VLog.d(TAG, "Native Libraries设置完成 - $packageName")
            
        } catch (e: Exception) {
            VLog.e(TAG, "设置Native Libraries失败", e)
            // 不抛出异常，允许应用继续运行
        }
    }
    
    /**
     * 从APK中提取Native Libraries
     */
    private fun extractNativeLibraries(apkPath: String, libDir: File) {
        try {
            VLog.d(TAG, "开始从APK提取Native Libraries: $apkPath")
            
            // 使用ZipFile读取APK中的lib目录
            val apkFile = File(apkPath)
            if (!apkFile.exists()) {
                VLog.w(TAG, "APK文件不存在: $apkPath")
                return
            }
            
            ZipFile(apkFile).use { zipFile ->
                val entries = zipFile.entries()
                var extractedCount = 0
                
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val entryName = entry.name
                    
                    // 检查是否是native library (lib/abi/*.so)
                    if (entryName.startsWith("lib/") && entryName.endsWith(".so") && !entry.isDirectory) {
                        try {
                            // 提取架构和文件名 (例如: lib/arm64-v8a/libemucore.so)
                            val pathParts = entryName.split("/")
                            if (pathParts.size >= 3) {
                                val abi = pathParts[1] // arm64-v8a, armeabi-v7a等
                                val libName = pathParts[2] // libemucore.so
                                
                                // 检查是否是当前设备支持的ABI
                                if (isSupportedAbi(abi)) {
                                    val targetFile = File(libDir, libName)
                                    
                                    VLog.d(TAG, "提取Native Library: $entryName -> ${targetFile.absolutePath}")
                                    
                                    zipFile.getInputStream(entry).use { input ->
                                        targetFile.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    
                                    // 设置执行权限
                                    targetFile.setExecutable(true)
                                    extractedCount++
                                    
                                    VLog.d(TAG, "成功提取: $libName (${entry.size} bytes)")
                                }
                            }
                        } catch (e: Exception) {
                            VLog.w(TAG, "提取单个库失败: $entryName - ${e.message}")
                        }
                    }
                }
                
                VLog.d(TAG, "Native Libraries提取完成，共提取 $extractedCount 个文件")
            }
            
        } catch (e: Exception) {
            VLog.e(TAG, "提取Native Libraries过程失败", e)
            throw e
        }
    }
    
    /**
     * 检查ABI是否被当前设备支持
     */
    private fun isSupportedAbi(abi: String): Boolean {
        val supportedAbis = Build.SUPPORTED_ABIS
        return supportedAbis.contains(abi) || 
               (abi == "armeabi-v7a" && supportedAbis.contains("arm64-v8a")) || // arm64设备通常支持armeabi-v7a
               (abi == "armeabi" && (supportedAbis.contains("armeabi-v7a") || supportedAbis.contains("arm64-v8a")))
    }
    
    /**
     * 简化的LoadedApk数据结构
     */
    private data class LoadedApkData(
        val applicationInfo: ApplicationInfo,
        val classLoader: ClassLoader
    )
}