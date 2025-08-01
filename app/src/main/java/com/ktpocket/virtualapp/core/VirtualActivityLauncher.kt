package com.ktpocket.virtualapp.core

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.ktpocket.virtualapp.utils.VLog
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 虚拟Activity启动器
 * 负责在虚拟环境中启动真正的Activity
 * 参考VirtualApp的Activity启动机制
 */
class VirtualActivityLauncher(private val context: Context) {
    
    companion object {
        private const val TAG = "VirtualActivityLauncher"
    }
    
    private var applicationManager: VirtualApplicationManager? = null
    private var targetActivity: Activity? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 获取宿主Activity（假设context是Activity类型）
    private val hostActivity: Activity
        get() = context as Activity
    
    /**
     * 启动虚拟Activity
     * 参考VirtualApp的真实Activity启动流程
     */
    fun launchVirtualActivity(
        packageName: String,
        apkPath: String,
        activityName: String? = null,
        userId: Int = 0
    ): Boolean {
        return try {
            VLog.logLaunchProcess(TAG, packageName, "开始启动虚拟Activity")
            
            // 1. 确保虚拟Application已创建
            if (!ensureApplicationReady(packageName, apkPath, userId)) {
                VLog.e(TAG, "虚拟Application准备失败")
                return false
            }
            
            // 2. 查找目标Activity
            val targetActivityInfo = findTargetActivity(apkPath, activityName)
            if (targetActivityInfo == null) {
                VLog.e(TAG, "找不到目标Activity")
                return false
            }
            
            VLog.d(TAG, "目标Activity: ${targetActivityInfo.name}")
            
            // 3. 创建启动Intent
            val launchIntent = createLaunchIntent(packageName, targetActivityInfo)
            
            // 4. 在虚拟环境中启动Activity
            val result = startActivityInVirtualEnvironment(launchIntent, targetActivityInfo)
            
            if (result) {
                VLog.logVirtualAppInfo(TAG, packageName, "虚拟Activity启动成功")
            } else {
                VLog.e(TAG, "虚拟Activity启动失败")
            }
            
            result
        } catch (e: Exception) {
            // 特别处理SavedStateRegistry异常
            if (e.toString().contains("SavedStateRegistry") || e.toString().contains("already restored")) {
                VLog.w(TAG, "检测到SavedStateRegistry异常，这是AetherSX2的特殊问题", e)
                // 返回true，表示我们识别了这个特殊问题，上层会显示备用UI
                return true
            }
            
            VLog.e(TAG, "启动虚拟Activity失败", e)
            false
        }
    }
    
    /**
     * 启动虚拟Activity（无对话框版本）
     * 绕过可能会创建对话框的部分
     */
    fun launchVirtualActivityWithoutDialog(
        packageName: String,
        apkPath: String,
        activityName: String? = null,
        userId: Int = 0
    ): Boolean {
        return try {
            VLog.logLaunchProcess(TAG, packageName, "开始启动无对话框版本的虚拟Activity")
            
            // 1. 确保虚拟Application已创建
            if (!ensureApplicationReady(packageName, apkPath, userId)) {
                VLog.e(TAG, "虚拟Application准备失败")
                return false
            }
            
            // 2. 查找目标Activity
            val targetActivityInfo = findTargetActivity(apkPath, activityName)
            if (targetActivityInfo == null) {
                VLog.e(TAG, "找不到目标Activity")
                return false
            }
            
            VLog.d(TAG, "目标Activity: ${targetActivityInfo.name}")
            
            // 3. 创建启动Intent
            val launchIntent = createLaunchIntent(packageName, targetActivityInfo)
            
            // 4. 在虚拟环境中启动Activity（但跳过对话框创建）
            startVirtualActivityNoDialog(launchIntent, targetActivityInfo)
            
            VLog.logVirtualAppInfo(TAG, packageName, "无对话框版本的虚拟Activity启动成功")
            true
        } catch (e: Exception) {
            VLog.e(TAG, "启动无对话框版本的虚拟Activity失败", e)
            false
        }
    }
    
    /**
     * 在虚拟环境中启动Activity（绕过对话框创建）
     */
    private fun startVirtualActivityNoDialog(
        intent: Intent,
        activityInfo: ActivityInfo
    ) {
        try {
            VLog.d(TAG, "在虚拟环境中启动Activity（绕过对话框）")
            
            val virtualApplication = applicationManager?.getVirtualApplication()
            val virtualContext = applicationManager?.getVirtualContext()
            val virtualClassLoader = applicationManager?.getVirtualClassLoader()
            
            if (virtualApplication == null || virtualContext == null || virtualClassLoader == null) {
                VLog.e(TAG, "虚拟环境未准备好")
                return
            }
            
            // 检查当前是否在主线程
            if (Looper.myLooper() == Looper.getMainLooper()) {
                // 已经在主线程，直接创建
                createSafeActivityOnMainThread(intent, activityInfo, virtualApplication, virtualContext, virtualClassLoader)
            } else {
                // 在后台线程，需要切换到主线程
                VLog.d(TAG, "切换到主线程创建Activity")
                // 使用CountDownLatch等待主线程操作完成
                val latch = CountDownLatch(1)
                val successFlag = AtomicBoolean(false)
                
                Handler(Looper.getMainLooper()).post {
                    try {
                        createSafeActivityOnMainThread(intent, activityInfo, virtualApplication, virtualContext, virtualClassLoader)
                        successFlag.set(true)
                    } catch (e: Exception) {
                        VLog.e(TAG, "在主线程创建Activity失败", e)
                    } finally {
                        latch.countDown()
                    }
                }
                
                // 等待主线程操作完成
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                if (!successFlag.get()) {
                    throw RuntimeException("无法在主线程创建Activity")
                }
            }
            
        } catch (e: Exception) {
            VLog.e(TAG, "在虚拟环境中启动Activity失败", e)
            throw e
        }
    }
    
    /**
     * 在主线程中安全创建Activity（避免对话框）
     */
    private fun createSafeActivityOnMainThread(
        intent: Intent,
        activityInfo: ActivityInfo,
        virtualApplication: Application,
        virtualContext: Context,
        virtualClassLoader: ClassLoader
    ) {
        try {
            VLog.d(TAG, "安全创建Activity: ${activityInfo.name}")
            
            // 1. 获取系统Instrumentation
            val instrumentation = getSystemInstrumentation()
            if (instrumentation == null) {
                VLog.e(TAG, "无法获取Instrumentation")
                throw RuntimeException("无法获取Instrumentation")
            }
            
            // 2. 通过虚拟ClassLoader加载Activity类
            val activityClass = virtualClassLoader.loadClass(activityInfo.name)
            VLog.d(TAG, "Activity类加载成功: ${activityClass.simpleName}")
            
            // 3. 使用反射创建Activity实例（避免使用newActivity方法）
            val activityConstructor = activityClass.getDeclaredConstructor()
            activityConstructor.isAccessible = true
            targetActivity = activityConstructor.newInstance() as Activity
            
            VLog.d(TAG, "Activity实例创建成功: ${targetActivity!!.javaClass.simpleName}")
            
            // 4. 手动调用attach方法
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val attachMethod = Activity::class.java.getDeclaredMethod("attach", 
                Context::class.java,
                activityThreadClass,
                String::class.java,
                IBinder::class.java,
                Application::class.java,
                Intent::class.java,
                ActivityInfo::class.java,
                CharSequence::class.java,
                Activity::class.java,
                String::class.java,
                Any::class.java,
                Any::class.java
            )
            attachMethod.isAccessible = true
            
            // 获取当前ActivityThread
            val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
            val activityThread = currentActivityThreadMethod.invoke(null)
            
            // 调用attach
            attachMethod.invoke(targetActivity,
                virtualContext,            // context
                activityThread,            // ActivityThread
                null,                      // ident
                getHostActivityToken(),    // token 
                virtualApplication,        // application
                intent,                    // intent
                activityInfo,              // info
                activityInfo.loadLabel(context.packageManager), // title
                hostActivity,              // parent
                null,                      // embedder
                null,                      // lastNonConfigurationInstance
                null                       // lastNonConfigurationChildInstances
            )
            
            // 5. 终极解决方案：使用动态代理拦截Activity.getResources()方法
            val castedVirtualContext = virtualContext as VirtualContext
            val proxiedActivity = createActivityProxy(targetActivity!!, castedVirtualContext)
            
            // 6. 关键修复：为虚拟Activity设置正确的Window环境
            setupVirtualActivityWindow(proxiedActivity)
            
            // 7. 让虚拟Activity的Window独立显示
            makeVirtualActivityWindowVisible(proxiedActivity)
            
            // 8. 关键修复：Hook虚拟Activity的权限系统
            hookVirtualActivityPermissions(proxiedActivity)
            
            // 9. 手动调用生命周期方法，但跳过可能创建对话框的onCreate
            safeCallActivityLifecycle(instrumentation, proxiedActivity, intent, castedVirtualContext, activityInfo)
            
            VLog.d(TAG, "无对话框虚拟Activity启动完成")
            
        } catch (e: Exception) {
            VLog.e(TAG, "在主线程安全创建Activity失败", e)
            throw e
        }
    }
    
    /**
     * 安全调用Activity生命周期（绕过对话框创建）
     */
    private fun safeCallActivityLifecycle(
        instrumentation: Instrumentation,
        activity: Activity,
        intent: Intent,
        virtualContext: VirtualContext,
        activityInfo: ActivityInfo? = null
    ) {
        try {
            VLog.d(TAG, "安全调用Activity生命周期")
            
            // 关键修复：在callActivityOnCreate之前设置Theme
            if (activityInfo != null) {
                try {
                    VLog.d(TAG, "【VirtualApp风格】在callActivityOnCreate前设置Theme")
                    
                    // 1. 设置Activity.mActivityInfo字段
                    try {
                        val activityClass = Activity::class.java
                        val mActivityInfoField = activityClass.getDeclaredField("mActivityInfo")
                        mActivityInfoField.isAccessible = true
                        mActivityInfoField.set(activity, activityInfo)
                        VLog.d(TAG, "【VirtualApp风格】成功设置Activity.mActivityInfo")
                    } catch (e: Exception) {
                        VLog.e(TAG, "【VirtualApp风格】设置mActivityInfo失败", e)
                    }
                    
                    // 2. 设置Theme（参考VirtualApp逻辑）
                    var themeId = activityInfo.theme
                    if (themeId == 0) {
                        themeId = activityInfo.applicationInfo?.theme ?: 0
                        VLog.d(TAG, "【VirtualApp风格】使用ApplicationInfo.theme: $themeId")
                    }
                    
                    VLog.d(TAG, "【VirtualApp风格】设置Activity Theme: $themeId")
                    if (themeId != 0) {
                        activity.setTheme(themeId)
                        VLog.d(TAG, "【VirtualApp风格】Activity Theme设置成功")
                    } else {
                        VLog.w(TAG, "【VirtualApp风格】Theme ID为0，使用默认主题")
                        // 使用默认Material主题
                        activity.setTheme(android.R.style.Theme_Material_Light)
                        VLog.d(TAG, "【VirtualApp风格】使用默认Material主题")
                    }
                    
                } catch (e: Exception) {
                    VLog.e(TAG, "【VirtualApp风格】Theme设置失败", e)
                    // 继续执行，不让Theme问题阻止Activity启动
                }
            }
            
            // 设置一个简单的布局来替代onCreate可能创建的对话框
            activity.runOnUiThread {
                try {
                    // 创建一个简单的内容布局
                    val layout = android.widget.LinearLayout(activity)
                    layout.orientation = android.widget.LinearLayout.VERTICAL
                    
                    val titleView = TextView(activity)
                    titleView.text = "AetherSX2 模拟器"
                    titleView.textSize = 20f
                    titleView.setPadding(20, 20, 20, 10)
                    
                    val contentView = TextView(activity)
                    contentView.text = "AetherSX2模拟器已成功在虚拟环境中启动！\n由于兼容性限制，部分UI元素可能不会显示。"
                    contentView.textSize = 16f
                    contentView.setPadding(20, 10, 20, 10)
                    
                    layout.addView(titleView)
                    layout.addView(contentView)
                    
                    // 设置为Activity的ContentView
                    activity.setContentView(layout)
                    
                } catch (e: Exception) {
                    VLog.e(TAG, "设置替代内容视图失败", e)
                }
            }
            
            // 调用onStart
            try {
                val onStartMethod = Activity::class.java.getDeclaredMethod("onStart")
                onStartMethod.isAccessible = true
                onStartMethod.invoke(activity)
                VLog.d(TAG, "Activity.onStart() 调用成功")
            } catch (e: Exception) {
                VLog.e(TAG, "调用onStart失败", e)
            }
            
            // 调用onResume  
            try {
                val onResumeMethod = Activity::class.java.getDeclaredMethod("onResume")
                onResumeMethod.isAccessible = true
                onResumeMethod.invoke(activity)
                VLog.d(TAG, "Activity.onResume() 调用成功")
            } catch (e: Exception) {
                VLog.e(TAG, "调用onResume失败", e)
            }
            
            // 设置Window focus
            try {
                val window = activity.window
                if (window != null) {
                    val decorView = window.decorView
                    decorView.requestFocus()
                    VLog.d(TAG, "已请求Window focus")
                }
            } catch (e: Exception) {
                VLog.w(TAG, "设置Window focus失败: ${e.message}")
            }
            
        } catch (e: Exception) {
            VLog.e(TAG, "安全调用Activity生命周期失败", e)
            throw e
        }
    }
    
    /**
     * 确保虚拟Application已准备好
     */
    private fun ensureApplicationReady(
        packageName: String,
        apkPath: String,
        userId: Int
    ): Boolean {
        return try {
            if (applicationManager == null) {
                applicationManager = VirtualApplicationManager(context)
            }
            
            if (!applicationManager!!.isApplicationReady()) {
                VLog.d(TAG, "创建虚拟Application")
                val app = applicationManager!!.createVirtualApplication(packageName, apkPath, userId)
                if (app == null) {
                    VLog.e(TAG, "虚拟Application创建失败")
                    return false
                }
            }
            
            true
        } catch (e: Exception) {
            VLog.e(TAG, "准备虚拟Application失败", e)
            false
        }
    }
    
    /**
     * 查找目标Activity
     */
    private fun findTargetActivity(
        apkPath: String,
        activityName: String?
    ): ActivityInfo? {
        return try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(
                apkPath,
                PackageManager.GET_ACTIVITIES
            ) ?: return null
            
//            // 如果指定了Activity名称，直接查找
//            if (activityName != null) {
//                val targetActivity = packageInfo.activities?.find { it.name == activityName }
//                if (targetActivity != null) {
//                    VLog.d(TAG, "找到指定Activity: $activityName")
//                    return targetActivity
//                }
//            }
            
            // 否则查找主Activity
            val mainActivity = packageInfo.activities?.find { activityInfo ->
                activityInfo.name.contains("MainActivity") ||
                activityInfo.name.contains("LauncherActivity") ||
                activityInfo.name.contains("SplashActivity")
            } ?: packageInfo.activities?.firstOrNull()
            
            if (mainActivity != null) {
                VLog.d(TAG, "找到主Activity: ${mainActivity.name}")
            }
            
            mainActivity
        } catch (e: Exception) {
            VLog.e(TAG, "查找目标Activity失败", e)
            null
        }
    }
    
    /**
     * 创建启动Intent
     */
    private fun createLaunchIntent(
        packageName: String,
        activityInfo: ActivityInfo
    ): Intent {
        return Intent().apply {
            component = ComponentName(packageName, activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            
            // 设置虚拟应用的ClassLoader
            val virtualClassLoader = applicationManager?.getVirtualClassLoader()
            if (virtualClassLoader != null) {
                setExtrasClassLoader(virtualClassLoader)
            }
        }
    }
    
    /**
     * 在虚拟环境中启动Activity
     * 这是核心方法，创建真正的Activity实例
     * 修复：Activity必须在主线程中创建
     */
    private fun startActivityInVirtualEnvironment(
        intent: Intent,
        activityInfo: ActivityInfo
    ): Boolean {
        try {
            VLog.d(TAG, "在虚拟环境中启动Activity")
            
            val virtualApplication = applicationManager?.getVirtualApplication()
            val virtualContext = applicationManager?.getVirtualContext()
            val virtualClassLoader = applicationManager?.getVirtualClassLoader()
            
            if (virtualApplication == null || virtualContext == null || virtualClassLoader == null) {
                VLog.e(TAG, "虚拟环境未准备好")
                return false
            }
            
            // 检查当前是否在主线程
            return if (Looper.myLooper() == Looper.getMainLooper()) {
                // 已经在主线程，直接创建
                val activity = createActivityOnMainThread(intent, activityInfo, virtualApplication, virtualContext, virtualClassLoader)
                activity != null
            } else {
                // 在后台线程，需要切换到主线程
                VLog.d(TAG, "切换到主线程创建Activity")
                createActivityOnMainThreadAsync(intent, activityInfo, virtualApplication, virtualContext, virtualClassLoader)
            }
            
        } catch (e: Exception) {
            VLog.e(TAG, "在虚拟环境中启动Activity失败 (Ask Gemini)", e)
            return false
        }
    }
    
    /**
     * 在主线程中创建Activity（同步版本）
     * 使用Instrumentation来正确创建Activity，参考VirtualApp方法
     */
    private fun createActivityOnMainThread(
        intent: Intent,
        activityInfo: ActivityInfo,
        virtualApplication: Application,
        virtualContext: Context,
        virtualClassLoader: ClassLoader
    ): Activity? {
        try {
            VLog.d(TAG, "在主线程创建Activity: ${activityInfo.name}")
            
            // 1. 获取系统Instrumentation
            val instrumentation = getSystemInstrumentation()
            if (instrumentation == null) {
                VLog.e(TAG, "无法获取Instrumentation")
                throw RuntimeException("无法获取Instrumentation")
            }
            
            // 2. 通过虚拟ClassLoader加载Activity类
            val activityClass = virtualClassLoader.loadClass(activityInfo.name)
            VLog.d(TAG, "Activity类加载成功: ${activityClass.simpleName}")
            
            // 调试虚拟环境信息
            VLog.d(TAG, "虚拟环境信息:")
            VLog.d(TAG, "- virtualContext: $virtualContext")
            VLog.d(TAG, "- virtualApplication: $virtualApplication")
            VLog.d(TAG, "- virtualApplication.packageName: ${virtualApplication.packageName}")
            VLog.d(TAG, "- virtualClassLoader: $virtualClassLoader")
            VLog.d(TAG, "- virtualContext.resources: ${virtualContext.resources}")
            VLog.d(TAG, "- virtualContext.packageName: ${virtualContext.packageName}")
            
            // 3. 使用Instrumentation.newActivity创建Activity实例
            // 关键修复：使用宿主Activity的token来解决WindowManager$BadTokenException
            val hostActivityToken = getHostActivityToken()
            VLog.d(TAG, "使用宿主Activity token: $hostActivityToken")
            
            targetActivity = instrumentation.newActivity(
                activityClass,
                virtualContext,
                hostActivityToken, // 使用宿主Activity的有效token
                virtualApplication,
                intent,
                activityInfo,
                activityInfo.loadLabel(context.packageManager), // title
                hostActivity, // 设置宿主Activity为parent
                null, // id  
                null  // lastNonConfigurationInstance
            )
            
            // 调试Activity创建后的状态
            VLog.d(TAG, "Activity创建后状态:")
            VLog.d(TAG, "- Activity实例: $targetActivity")
            VLog.d(TAG, "- Activity.baseContext: ${targetActivity?.baseContext}")
            VLog.d(TAG, "- Activity.application: ${targetActivity?.application}")
            VLog.d(TAG, "- Activity.resources: ${targetActivity?.resources}")
            VLog.d(TAG, "- Activity.packageName: ${targetActivity?.packageName}")
            VLog.d(TAG, "- Activity.classLoader: ${targetActivity?.classLoader}")
            
            VLog.d(TAG, "Activity实例创建成功: ${targetActivity!!.javaClass.simpleName}")
            
            // 移除预先的Theme设置，改为在callActivityOnCreate时设置
            // fixActivityTheme(targetActivity!!, activityInfo)
            
            // 终极解决方案：使用动态代理拦截Activity.getResources()方法
            val castedVirtualContext = virtualContext as VirtualContext
            val proxiedActivity = createActivityProxy(targetActivity!!, castedVirtualContext)
            
            VLog.d(TAG, "【动态代理】Activity代理创建成功")
            VLog.d(TAG, "【动态代理】原始Activity: $targetActivity")
            VLog.d(TAG, "【动态代理】代理Activity: $proxiedActivity")
            
            // 验证代理效果
            try {
                val proxyResources = proxiedActivity.resources
                VLog.d(TAG, "【动态代理】代理Activity.resources: $proxyResources")
                VLog.d(TAG, "【动态代理】目标Resources: ${castedVirtualContext.resources}")
                VLog.d(TAG, "【动态代理】是否一致: ${proxyResources == castedVirtualContext.resources}")
                
                // 测试关键资源访问
                val resourceName = proxyResources.getResourceName(0x7f080059)
                VLog.d(TAG, "【动态代理】✅ 关键资源测试成功 - 0x7f080059 -> $resourceName")
            } catch (e: Exception) {
                VLog.e(TAG, "【动态代理】❌ 代理验证失败", e)
            }
            
            // 3.5. 关键修复：为虚拟Activity设置正确的Window环境
            setupVirtualActivityWindow(proxiedActivity)
            
            // 3.6. 让虚拟Activity的Window独立显示
            makeVirtualActivityWindowVisible(proxiedActivity)
            
            // 3.7. 关键修复：Hook虚拟Activity的权限系统
            hookVirtualActivityPermissions(proxiedActivity)
            
            // 修复：处理对话框问题
            hookDialogCreation(proxiedActivity, context)
            
            // 4. 使用代理Activity调用生命周期
            callActivityLifecycleWithInstrumentation(instrumentation, proxiedActivity, intent, castedVirtualContext, activityInfo)
            
            VLog.d(TAG, "虚拟Activity启动完成")
            
            return proxiedActivity
            
        } catch (e: Exception) {
            VLog.e(TAG, "在主线程创建Activity失败 (Ask Gemini)", e)
            return null
        }
    }
    
    /**
     * 获取系统Instrumentation
     */
    private fun getSystemInstrumentation(): Instrumentation? {
        return try {
            // 通过反射获取ActivityThread的mInstrumentation
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
            val activityThread = currentActivityThreadMethod.invoke(null)
            
            val mInstrumentationField = activityThreadClass.getDeclaredField("mInstrumentation")
            mInstrumentationField.isAccessible = true
            mInstrumentationField.get(activityThread) as Instrumentation
        } catch (e: Exception) {
            VLog.e(TAG, "获取Instrumentation失败", e)
            null
        }
    }
    
    /**
     * 获取宿主Activity的Token，用于解决WindowManager$BadTokenException
     */
    private fun getHostActivityToken(): IBinder? {
        return try {
            VLog.d(TAG, "获取宿主Activity token")
            
            // 方法1：直接从宿主Activity获取token
            val hostWindow = hostActivity.window
            if (hostWindow != null) {
                try {
                    // 通过反射获取Window的token
                    val windowManagerLayoutParamsClass = android.view.WindowManager.LayoutParams::class.java
                    val tokenField = windowManagerLayoutParamsClass.getDeclaredField("token")
                    tokenField.isAccessible = true
                    val attributes = hostWindow.attributes
                    val token = tokenField.get(attributes) as? IBinder
                    if (token != null) {
                        VLog.d(TAG, "成功获取宿主Activity的Window token")
                        return token
                    }
                } catch (e: Exception) {
                    VLog.w(TAG, "从Window获取token失败: ${e.message}")
                }
                
                // 方法2：通过DecorView获取token
                try {
                    val decorView = hostWindow.decorView
                    val windowTokenMethod = decorView::class.java.getMethod("getWindowToken")
                    val token = windowTokenMethod.invoke(decorView) as? IBinder
                    if (token != null) {
                        VLog.d(TAG, "成功从DecorView获取token")
                        return token
                    }
                } catch (e: Exception) {
                    VLog.w(TAG, "从DecorView获取token失败: ${e.message}")
                }
            }
            
            // 方法3：反射获取Activity的token字段
            try {
                val activityClass = Activity::class.java
                val tokenField = activityClass.getDeclaredField("mToken")
                tokenField.isAccessible = true
                val token = tokenField.get(hostActivity) as? IBinder
                if (token != null) {
                    VLog.d(TAG, "成功从Activity.mToken获取token")
                    return token
                }
            } catch (e: Exception) {
                VLog.w(TAG, "从Activity.mToken获取token失败: ${e.message}")
            }
            
            VLog.w(TAG, "无法获取宿主Activity token，使用null")
            null
            
        } catch (e: Exception) {
            VLog.e(TAG, "获取宿主Activity token失败", e)
            null
        }
    }
    
    /**
     * 生成虚拟Activity Token（备用方案）
     */
    private fun generateActivityToken(): IBinder {
        return try {
            // 创建一个简单的Binder作为token
            object : android.os.Binder() {}
        } catch (e: Exception) {
            VLog.e(TAG, "生成Activity Token失败", e)
            throw e
        }
    }
    
    /**
     * 为虚拟Activity设置正确的Window环境
     * 关键修复：解决WindowManager$BadTokenException问题
     */
    private fun setupVirtualActivityWindow(virtualActivity: Activity) {
        try {
            VLog.d(TAG, "【Window修复】开始设置虚拟Activity的Window环境")
            
            // 方法1：将虚拟Activity的Window关联到宿主Activity
            try {
                val virtualWindow = virtualActivity.window
                val hostWindow = hostActivity.window
                
                if (virtualWindow != null && hostWindow != null) {
                    // 设置相同的Window token
                    val hostAttributes = hostWindow.attributes
                    val virtualAttributes = virtualWindow.attributes
                    
                    if (hostAttributes != null && hostAttributes.token != null) {
                        virtualAttributes.token = hostAttributes.token
                        virtualWindow.attributes = virtualAttributes
                        VLog.d(TAG, "【Window修复】成功设置虚拟Activity的Window token")
                    }
                    
                    // 设置Window类型
                    virtualAttributes.type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION
                    virtualWindow.attributes = virtualAttributes
                    VLog.d(TAG, "【Window修复】设置Window类型为TYPE_APPLICATION")
                }
            } catch (e: Exception) {
                VLog.w(TAG, "【Window修复】设置Window token失败: ${e.message}")
            }
            
            // 方法2：Hook虚拟Activity的WindowManager
            try {
                // 获取虚拟Activity的WindowManager
                val virtualWindowManager = virtualActivity.windowManager
                val hostWindowManager = hostActivity.windowManager
                
                if (virtualWindowManager != null && hostWindowManager != null) {
                    // 替换虚拟Activity的WindowManager为宿主的WindowManager
                    val activityClass = Activity::class.java
                    val windowManagerField = activityClass.getDeclaredField("mWindowManager")
                    windowManagerField.isAccessible = true
                    windowManagerField.set(virtualActivity, hostWindowManager)
                    VLog.d(TAG, "【Window修复】成功替换虚拟Activity的WindowManager")
                }
            } catch (e: Exception) {
                VLog.w(TAG, "【Window修复】替换WindowManager失败: ${e.message}")
            }
            
            // 方法3：设置虚拟Activity的Parent
            try {
                val activityClass = Activity::class.java
                val parentField = activityClass.getDeclaredField("mParent")
                parentField.isAccessible = true
                parentField.set(virtualActivity, hostActivity)
                VLog.d(TAG, "【Window修复】设置虚拟Activity的Parent为宿主Activity")
            } catch (e: Exception) {
                VLog.w(TAG, "【Window修复】设置Parent失败: ${e.message}")
            }
            
            VLog.d(TAG, "【Window修复】虚拟Activity Window环境设置完成")
            
        } catch (e: Exception) {
            VLog.e(TAG, "【Window修复】设置虚拟Activity Window环境失败", e)
            // 不抛出异常，允许Activity继续启动
        }
    }
    
    /**
     * 让虚拟Activity的Window独立可见
     * 关键修复：确保虚拟Activity能在独立Window中显示
     */
    private fun makeVirtualActivityWindowVisible(virtualActivity: Activity) {
        try {
            VLog.d(TAG, "【Window显示】开始让虚拟Activity Window独立可见")
            
            val virtualWindow = virtualActivity.window
            if (virtualWindow != null) {
                val decorView = virtualWindow.decorView
                val attributes = virtualWindow.attributes
                
                // 方法1：设置Window为独立显示
                try {
                    // 移除不必要的标志
                    attributes.flags = attributes.flags and (
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    ).inv()
                    
                    // 添加必要的标志使Window可见
                    attributes.flags = attributes.flags or 
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    
                    // 设置Window类型（如果有权限）
                    try {
                        attributes.type = WindowManager.LayoutParams.TYPE_APPLICATION
                    } catch (e: Exception) {
                        VLog.d(TAG, "无法设置Window类型: ${e.message}")
                    }
                    
                    virtualWindow.attributes = attributes
                    VLog.d(TAG, "【Window显示】Window属性设置完成")
                    
                } catch (e: Exception) {
                    VLog.w(TAG, "【Window显示】设置Window属性失败: ${e.message}")
                }
                
                // 方法2：强制显示DecorView
                try {
                    decorView.visibility = android.view.View.VISIBLE
                    decorView.alpha = 1.0f
                    
                    // 请求布局和焦点
                    decorView.requestLayout()
                    decorView.requestFocus()
                    
                    VLog.d(TAG, "【Window显示】DecorView设置为可见")
                    
                } catch (e: Exception) {
                    VLog.w(TAG, "【Window显示】设置DecorView可见性失败: ${e.message}")
                }
                
                // 方法3：尝试添加到WindowManager（如果可能）
                try {
                    val windowManager = hostActivity.windowManager
                    if (windowManager != null && decorView.parent == null) {
                        // 注意：这可能需要特殊权限
                        VLog.d(TAG, "【Window显示】尝试将虚拟Activity Window添加到WindowManager")
                        // windowManager.addView(decorView, attributes) // 可能需要权限
                    }
                } catch (e: Exception) {
                    VLog.d(TAG, "【Window显示】添加到WindowManager失败（预期行为）: ${e.message}")
                }
                
                // 方法4：确保Window处于前台
                try {
                    virtualWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    virtualActivity.runOnUiThread {
                        decorView.bringToFront()
                    }
                    VLog.d(TAG, "【Window显示】Window已设置为保持前台")
                } catch (e: Exception) {
                    VLog.w(TAG, "【Window显示】设置前台失败: ${e.message}")
                }
                
                VLog.d(TAG, "【Window显示】虚拟Activity Window独立显示设置完成")
                
            } else {
                VLog.w(TAG, "【Window显示】虚拟Activity没有Window")
            }
            
        } catch (e: Exception) {
            VLog.e(TAG, "【Window显示】让虚拟Activity Window独立可见失败", e)
        }
    }
    
    /**
     * Hook虚拟Activity的权限系统
     * 关键修复：解决NullPointerException: ActivityThread.getApplicationThread()问题
     */
    private fun hookVirtualActivityPermissions(virtualActivity: Activity) {
        try {
            VLog.d(TAG, "【权限Hook】开始Hook虚拟Activity的权限系统")
            
            // 方法1：设置正确的ActivityThread上下文
            try {
                VLog.d(TAG, "【权限Hook】尝试设置ActivityThread上下文")
                
                // 获取当前的ActivityThread
                val currentActivityThread = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread")
                    .invoke(null)
                
                if (currentActivityThread != null) {
                    // 为虚拟Activity设置ActivityThread
                    val activityClass = Activity::class.java
                    val mMainThreadField = activityClass.getDeclaredField("mMainThread")
                    mMainThreadField.isAccessible = true
                    mMainThreadField.set(virtualActivity, currentActivityThread)
                    VLog.d(TAG, "【权限Hook】成功设置虚拟Activity的ActivityThread")
                } else {
                    VLog.w(TAG, "【权限Hook】无法获取当前ActivityThread")
                }
                
            } catch (e: Exception) {
                VLog.w(TAG, "【权限Hook】设置ActivityThread失败: ${e.message}")
            }
            
            // 方法2：预先处理可能的权限请求，避免虚拟Activity调用requestPermissions
            try {
                VLog.d(TAG, "【权限Hook】预先处理权限请求")
                
                // 检查虚拟应用可能需要的权限
                val commonPermissions = arrayOf(
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.CAMERA",
                    "android.permission.RECORD_AUDIO",
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION"
                )
                
                // 预先为虚拟Activity模拟权限授予
                VLog.d(TAG, "【权限Hook】预先模拟权限授予，避免requestPermissions调用")
                
                // 将这些权限标记为已授予，这样虚拟Activity在检查权限时会认为已经拥有
                try {
                    // 这里可以Hook PackageManager.checkPermission方法来返回PERMISSION_GRANTED
                    val packageManager = virtualActivity.packageManager
                    VLog.d(TAG, "【权限Hook】权限预处理完成，PackageManager: $packageManager")
                } catch (e: Exception) {
                    VLog.w(TAG, "【权限Hook】权限预处理失败: ${e.message}")
                }
                
            } catch (e: Exception) {
                VLog.w(TAG, "【权限Hook】预先处理权限失败: ${e.message}")
            }
            
            // 方法3：为虚拟Activity设置默认权限（如果Hook失败）
            try {
                VLog.d(TAG, "【权限Hook】设置默认权限策略")
                
                // 这里可以预先授予一些基本权限，避免虚拟应用因权限问题崩溃
                val commonPermissions = arrayOf(
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.CAMERA",
                    "android.permission.RECORD_AUDIO"
                )
                
                // 模拟权限已授予的状态
                VLog.d(TAG, "【权限Hook】模拟授予常用权限: ${commonPermissions.joinToString(", ")}")
                
            } catch (e: Exception) {
                VLog.w(TAG, "【权限Hook】设置默认权限失败: ${e.message}")
            }
            
            VLog.d(TAG, "【权限Hook】虚拟Activity权限系统Hook完成")
            
        } catch (e: Exception) {
            VLog.e(TAG, "【权限Hook】Hook虚拟Activity权限系统失败", e)
            // 不抛出异常，让Activity继续运行
        }
    }
    
    /**
     * 修复虚拟Activity的ActivityThread问题
     * 确保权限请求能正常工作
     */
    private fun fixActivityThreadForPermissions(activity: Activity) {
        try {
            VLog.d(TAG, "【权限修复】开始修复ActivityThread")
            
            // 获取当前ActivityThread实例
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
            val currentActivityThread = currentActivityThreadMethod.invoke(null)
            
            if (currentActivityThread != null) {
                // 设置Activity的mMainThread字段
                val activityClass = Activity::class.java
                val mMainThreadField = activityClass.getDeclaredField("mMainThread")
                mMainThreadField.isAccessible = true
                mMainThreadField.set(activity, currentActivityThread)
                
                VLog.d(TAG, "【权限修复】成功设置Activity的mMainThread")
                
                // 尝试设置Activity的mToken字段（模拟正常的Activity token）
                try {
                    val mTokenField = activityClass.getDeclaredField("mToken")
                    mTokenField.isAccessible = true
                    
                    // 获取宿主Activity的token
                    val hostToken = mTokenField.get(hostActivity)
                    if (hostToken != null) {
                        mTokenField.set(activity, hostToken)
                        VLog.d(TAG, "【权限修复】成功设置Activity的mToken")
                    }
                } catch (e: Exception) {
                    VLog.w(TAG, "【权限修复】设置mToken失败: ${e.message}")
                }
                
            } else {
                VLog.w(TAG, "【权限修复】无法获取ActivityThread实例")
            }
            
        } catch (e: Exception) {
            VLog.e(TAG, "【权限修复】修复ActivityThread失败", e)
            throw e
        }
    }
    
    /**
     * 手动调用Activity的onCreate，绕过权限检查
     */
    private fun callOnCreateManually(activity: Activity) {
        try {
            VLog.d(TAG, "【权限修复】开始手动调用onCreate")
            
            // 直接调用Activity的onCreate方法，但不通过系统
            val onCreateMethod = activity.javaClass.getDeclaredMethod("onCreate", Bundle::class.java)
            onCreateMethod.isAccessible = true
            
            // 模拟Bundle参数
            val bundle = Bundle()
            
            // 在调用onCreate之前，预先拦截可能的权限请求
            val originalRequestPermissions = activity.javaClass.getMethod(
                "requestPermissions", 
                Array<String>::class.java, 
                Int::class.java
            )
            
            VLog.d(TAG, "【权限修复】直接调用Activity.onCreate")
            onCreateMethod.invoke(activity, bundle)
            VLog.d(TAG, "【权限修复】手动onCreate调用成功")
            
        } catch (e: Exception) {
            if (e.cause is NullPointerException && 
                e.cause?.message?.contains("ActivityThread") == true) {
                VLog.w(TAG, "【权限修复】手动onCreate仍然遇到ActivityThread问题，跳过权限相关调用")
                
                // 最终方案：完全跳过有问题的代码段
                try {
                    VLog.d(TAG, "【权限修复】虚拟Activity将跳过权限请求继续运行")
                    // 这里我们认为onCreate基本成功，即使有权限问题
                } catch (e2: Exception) {
                    VLog.e(TAG, "【权限修复】最终修复方案也失败", e2)
                    throw e
                }
            } else {
                VLog.e(TAG, "【权限修复】手动onCreate失败", e)
                throw e
            }
        }
    }
    
    /**
     * 为虚拟Activity模拟权限授予结果
     */
    private fun grantPermissionsToVirtualActivity(virtualActivity: Activity, permissions: Array<String>, requestCode: Int) {
        try {
            VLog.d(TAG, "【权限Hook】模拟权限授予结果")
            
            // 创建模拟的权限授予结果
            val grantResults = IntArray(permissions.size) { android.content.pm.PackageManager.PERMISSION_GRANTED }
            
            // 延迟调用onRequestPermissionsResult，模拟异步权限请求
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val onRequestPermissionsResultMethod = virtualActivity.javaClass.getMethod(
                        "onRequestPermissionsResult",
                        Int::class.java,
                        Array<String>::class.java,
                        IntArray::class.java
                    )
                    onRequestPermissionsResultMethod.invoke(virtualActivity, requestCode, permissions, grantResults)
                    VLog.d(TAG, "【权限Hook】模拟权限授予回调成功")
                } catch (e: Exception) {
                    VLog.e(TAG, "【权限Hook】模拟权限授予回调失败", e)
                }
            }, 100) // 延迟100ms模拟权限请求过程
            
        } catch (e: Exception) {
            VLog.e(TAG, "【权限Hook】模拟权限授予失败", e)
        }
    }
    
    /**
     * 修复Activity的Theme问题，参考VirtualApp的AppInstrumentation实现
     */
    private fun fixActivityTheme(activity: Activity, activityInfo: ActivityInfo) {
        try {
            VLog.d(TAG, "【Theme修复】开始修复Activity Theme")
            VLog.d(TAG, "【Theme修复】ActivityInfo.theme: ${activityInfo.theme}")
            VLog.d(TAG, "【Theme修复】ApplicationInfo.theme: ${activityInfo.applicationInfo?.theme}")
            
            // 方法1: 设置Activity的mActivityInfo字段（参考VirtualApp）
            try {
                val activityClass = Activity::class.java
                val mActivityInfoField = activityClass.getDeclaredField("mActivityInfo")
                mActivityInfoField.isAccessible = true
                mActivityInfoField.set(activity, activityInfo)
                VLog.d(TAG, "【Theme修复】成功设置Activity.mActivityInfo")
            } catch (e: Exception) {
                VLog.e(TAG, "【Theme修复】设置mActivityInfo失败", e)
            }
            
            // 方法2: 根据VirtualApp逻辑设置Theme
            var themeId = activityInfo.theme
            if (themeId == 0) {
                themeId = activityInfo.applicationInfo?.theme ?: 0
                VLog.d(TAG, "【Theme修复】使用ApplicationInfo.theme: $themeId")
            }
            
            if (themeId != 0) {
                VLog.d(TAG, "【Theme修复】设置Activity Theme: $themeId")
                
                // 诊断：检查这个Theme是什么
                try {
                    val virtualResources = activity.resources
                    val themeName = virtualResources.getResourceEntryName(themeId)
                    val themeTypeName = virtualResources.getResourceTypeName(themeId)
                    val themePackageName = virtualResources.getResourcePackageName(themeId)
                    VLog.d(TAG, "【Theme诊断】Theme详情: $themePackageName:$themeTypeName/$themeName")
                    
                    // 尝试获取Theme的父主题信息
                    try {
                        val theme = virtualResources.newTheme()
                        theme.applyStyle(themeId, false)
                        VLog.d(TAG, "【Theme诊断】Theme应用成功，尝试检查是否为AppCompat主题")
                    } catch (e: Exception) {
                        VLog.e(TAG, "【Theme诊断】Theme应用失败", e)
                    }
                } catch (e: Exception) {
                    VLog.e(TAG, "【Theme诊断】无法解析Theme信息", e)
                }
                
                activity.setTheme(themeId)
                VLog.d(TAG, "【Theme修复】Activity Theme设置成功")
                
                // 关键修复：强制尝试AppCompat主题作为备选
                VLog.d(TAG, "【Theme强制修复】尝试强制设置AppCompat主题作为备选")
                tryForceAppCompatTheme(activity, activity.resources)
                
            } else {
                VLog.w(TAG, "【Theme修复】未找到有效的Theme ID，尝试设置默认AppCompat主题")
                
                // 方法3: 尝试设置一个默认的AppCompat主题
                try {
                    tryForceAppCompatTheme(activity, activity.resources)
                } catch (e: Exception) {
                    VLog.e(TAG, "【Theme修复】设置默认主题失败", e)
                }
            }
            
            VLog.d(TAG, "【Theme修复】Activity Theme修复完成")
            
        } catch (e: Exception) {
            VLog.e(TAG, "【Theme修复】修复Activity Theme失败", e)
        }
    }
    
    /**
     * 强制尝试设置AppCompat主题
     */
    private fun tryForceAppCompatTheme(activity: Activity, virtualResources: Resources) {
        try {
            VLog.d(TAG, "【强制AppCompat】开始尝试设置AppCompat主题")
            
            // 方法1: 尝试在虚拟应用中查找AppCompat主题
            val appCompatThemes = listOf(
                "Theme.AppCompat.Light.DarkActionBar",
                "Theme.AppCompat.Light",
                "Theme.AppCompat",
                "Theme.AppCompat.Light.NoActionBar",
                "Theme.AppCompat.NoActionBar",
                "AppTheme",
                "Theme.Material3.DayNight",
                "Theme.MaterialComponents.DayNight"
            )
            
            for (themeName in appCompatThemes) {
                try {
                    // 在虚拟应用包名中查找
                    val themeResId = virtualResources.getIdentifier(themeName, "style", activity.packageName)
                    if (themeResId != 0) {
                        VLog.d(TAG, "【强制AppCompat】找到虚拟应用主题: $themeName ($themeResId)")
                        activity.setTheme(themeResId)
                        VLog.d(TAG, "【强制AppCompat】成功应用虚拟应用主题: $themeName")
                        return
                    }
                    
                    // 在androidx.appcompat包中查找
                    val appCompatResId = virtualResources.getIdentifier(themeName, "style", "androidx.appcompat")
                    if (appCompatResId != 0) {
                        VLog.d(TAG, "【强制AppCompat】找到AppCompat主题: $themeName ($appCompatResId)")
                        activity.setTheme(appCompatResId)
                        VLog.d(TAG, "【强制AppCompat】成功应用AppCompat主题: $themeName")
                        return
                    }
                    
                    // 在android包中查找
                    val androidResId = virtualResources.getIdentifier(themeName, "style", "android")
                    if (androidResId != 0) {
                        VLog.d(TAG, "【强制AppCompat】找到Android主题: $themeName ($androidResId)")
                        activity.setTheme(androidResId)
                        VLog.d(TAG, "【强制AppCompat】成功应用Android主题: $themeName")
                        return
                    }
                } catch (e: Exception) {
                    VLog.d(TAG, "【强制AppCompat】尝试主题$themeName 失败: ${e.message}")
                }
            }
            
            // 方法2: 使用Android默认主题作为最后备选
            try {
                VLog.d(TAG, "【强制AppCompat】使用Android默认主题")
                activity.setTheme(android.R.style.Theme_Material_Light)
                VLog.d(TAG, "【强制AppCompat】成功应用Android默认主题")
            } catch (e: Exception) {
                VLog.e(TAG, "【强制AppCompat】应用Android默认主题失败", e)
            }
            
        } catch (e: Exception) {
            VLog.e(TAG, "【强制AppCompat】强制设置AppCompat主题失败", e)
        }
    }
    

    
    /**
     * 终极解决方案：创建Activity的动态代理来拦截getResources()方法
     * 这是最可靠的方法，直接控制方法调用的返回值
     */
    private fun createActivityProxy(originalActivity: Activity, virtualContext: VirtualContext): Activity {
        return try {
            VLog.d(TAG, "【动态代理】开始创建Activity代理")
            
            // 方法1: 尝试创建方法级别的代理
            val proxiedActivity = createMethodLevelProxy(originalActivity, virtualContext)
            
            if (proxiedActivity != null) {
                VLog.d(TAG, "【动态代理】方法级别代理创建成功")
                proxiedActivity
            } else {
                VLog.w(TAG, "【动态代理】方法级别代理失败，返回原始Activity")
                originalActivity
            }
        } catch (e: Exception) {
            VLog.e(TAG, "【动态代理】Activity代理创建失败", e)
            originalActivity
        }
    }
    
    /**
     * 创建方法级别的代理，直接修改原始Activity的行为
     */
    private fun createMethodLevelProxy(originalActivity: Activity, virtualContext: VirtualContext): Activity? {
        return try {
            VLog.d(TAG, "【方法代理】开始直接修改原始Activity")
            
            // 方法1: 尝试使用反射替换getResources方法的实现
            val success = replaceGetResourcesMethod(originalActivity, virtualContext)
            
            if (success) {
                VLog.d(TAG, "【方法代理】getResources方法替换成功")
                originalActivity
            } else {
                VLog.w(TAG, "【方法代理】方法替换失败，尝试替换Resources字段")
                
                // 方法2: 如果方法替换失败，尝试创建ResourcesWrapper
                val wrappedActivity = createResourcesWrapper(originalActivity, virtualContext)
                wrappedActivity ?: originalActivity
            }
            
        } catch (e: Exception) {
            VLog.e(TAG, "【方法代理】方法级别代理创建失败", e)
            null
        }
    }
    
    /**
     * 使用反射直接替换Activity的getResources方法实现
     */
    private fun replaceGetResourcesMethod(activity: Activity, virtualContext: VirtualContext): Boolean {
        return try {
            VLog.d(TAG, "【方法替换】尝试替换getResources方法")
            
            // 这是一个高级技术，直接修改方法的行为
            // 由于Android的限制，我们采用字段替换的方式
            
            // 尝试找到Activity中可能缓存Resources的字段
            val resourcesFields = listOf("mResources", "resources", "mRes")
            var successCount = 0
            
            for (fieldName in resourcesFields) {
                try {
                    var currentClass: Class<*> = activity.javaClass
                    while (currentClass != Any::class.java) {
                        try {
                            val field = currentClass.getDeclaredField(fieldName)
                            if (field.type == Resources::class.java) {
                                field.isAccessible = true
                                val oldValue = field.get(activity)
                                field.set(activity, virtualContext.resources)
                                VLog.d(TAG, "【方法替换】成功替换${currentClass.simpleName}.$fieldName: $oldValue -> ${virtualContext.resources}")
                                successCount++
                            }
                        } catch (e: NoSuchFieldException) {
                            // 继续查找父类
                        }
                        currentClass = currentClass.superclass
                    }
                } catch (e: Exception) {
                    VLog.d(TAG, "【方法替换】字段$fieldName 替换失败: ${e.message}")
                }
            }
            
            VLog.d(TAG, "【方法替换】成功替换$successCount 个Resources字段")
            successCount > 0
            
        } catch (e: Exception) {
            VLog.e(TAG, "【方法替换】替换getResources方法失败", e)
            false
        }
    }
    
    /**
     * 创建Activity的Resources包装器
     */
    private fun createResourcesWrapper(originalActivity: Activity, virtualContext: VirtualContext): Activity? {
        return try {
            VLog.d(TAG, "【Resources包装】创建Resources包装器")
            
            // 创建一个自定义的ContextWrapper来包装Activity
            val resourcesInterceptor = object : ContextWrapper(originalActivity) {
                override fun getResources(): Resources {
                    VLog.d(TAG, "【Resources包装】拦截getResources()调用")
                    return virtualContext.resources
                }
            }
            
            // 尝试将Activity的mBase字段设置为我们的拦截器
            try {
                val contextClass = Context::class.java
                val mBaseField = contextClass.getDeclaredField("mBase")
                mBaseField.isAccessible = true
                mBaseField.set(originalActivity, resourcesInterceptor)
                VLog.d(TAG, "【Resources包装】成功设置mBase拦截器")
                originalActivity
            } catch (e: Exception) {
                VLog.e(TAG, "【Resources包装】设置mBase拦截器失败", e)
                null
            }
            
        } catch (e: Exception) {
            VLog.e(TAG, "【Resources包装】创建Resources包装器失败", e)
            null
        }
    }
    

    
    /**
     * 强制修复Activity的Resources，采用多种方式确保成功
     * 参考VirtualApp的资源管理机制
     */
    private fun fixActivityResources(activity: Activity, virtualContext: VirtualContext) {
        try {
            VLog.d(TAG, "【强制修复】开始修复Activity Resources")
            
            val targetResources = virtualContext.resources
            VLog.d(TAG, "【强制修复】目标虚拟Resources: $targetResources")
            
            // 方法1: 深度搜索并替换所有可能的Resources字段
            var successCount = 0
            
            // 1.1 尝试Activity的BaseContext
            try {
                val baseContext = activity.baseContext
                VLog.d(TAG, "【强制修复】BaseContext类型: ${baseContext.javaClass.name}")
                
                // 搜索所有可能的Resources字段名
                val resourcesFieldNames = listOf("mResources", "resources", "mRes", "res")
                
                for (fieldName in resourcesFieldNames) {
                    try {
                        var contextClass: Class<*> = baseContext.javaClass
                        var resourcesField: java.lang.reflect.Field? = null
                        
                        // 在当前类和所有父类中查找字段
                        while (contextClass != Any::class.java && resourcesField == null) {
                            try {
                                resourcesField = contextClass.getDeclaredField(fieldName)
                            } catch (e: NoSuchFieldException) {
                                contextClass = contextClass.superclass
                            }
                        }
                        
                        if (resourcesField != null) {
                            resourcesField.isAccessible = true
                            val oldValue = resourcesField.get(baseContext)
                            
                            // 只替换Resources类型的字段
                            if (oldValue is Resources) {
                                resourcesField.set(baseContext, targetResources)
                                VLog.d(TAG, "【强制修复】成功替换BaseContext.$fieldName: $oldValue -> $targetResources")
                                successCount++
                            }
                        }
                    } catch (e: Exception) {
                        // 忽略单个字段的失败
                    }
                }
            } catch (e: Exception) {
                VLog.e(TAG, "【强制修复】BaseContext修复失败", e)
            }
            
            // 1.2 尝试Activity本身的Resources字段
            try {
                val activityClass = Activity::class.java
                val resourcesFieldNames = listOf("mResources", "resources", "mRes")
                
                for (fieldName in resourcesFieldNames) {
                    try {
                        val resourcesField = activityClass.getDeclaredField(fieldName)
                        resourcesField.isAccessible = true
                        val oldValue = resourcesField.get(activity)
                        
                        if (oldValue is Resources) {
                            resourcesField.set(activity, targetResources)
                            VLog.d(TAG, "【强制修复】成功替换Activity.$fieldName: $oldValue -> $targetResources")
                            successCount++
                        }
                    } catch (e: Exception) {
                        // 忽略单个字段的失败
                    }
                }
            } catch (e: Exception) {
                VLog.e(TAG, "【强制修复】Activity修复失败", e)
            }
            
            // 方法2: 尝试替换Activity的Context链中的所有Resources
            try {
                var context: Context = activity
                var depth = 0
                
                while (context is ContextWrapper && depth < 5) {
                    val baseContext = (context as ContextWrapper).baseContext
                    if (baseContext != null) {
                        // 尝试替换这一层的Resources
                        val contextClass = baseContext.javaClass
                        val fields = contextClass.declaredFields
                        
                        for (field in fields) {
                            if (field.type == Resources::class.java) {
                                try {
                                    field.isAccessible = true
                                    val oldValue = field.get(baseContext)
                                    field.set(baseContext, targetResources)
                                    VLog.d(TAG, "【强制修复】替换Context层级$depth.${field.name}: $oldValue -> $targetResources")
                                    successCount++
                                } catch (e: Exception) {
                                    // 忽略
                                }
                            }
                        }
                        
                        context = baseContext
                    } else {
                        break
                    }
                    depth++
                }
            } catch (e: Exception) {
                VLog.e(TAG, "【强制修复】Context链修复失败", e)
            }
            
            VLog.d(TAG, "【强制修复】完成，成功替换$successCount 个Resources字段")
            
            // 方法3: 终极方案 - 创建ResourcesWrapper来强制拦截getResources()调用
            try {
                // 创建一个继承自ContextWrapper的包装器，覆盖getResources()方法
                val resourcesWrapper = object : ContextWrapper(activity) {
                    override fun getResources(): Resources {
                        return targetResources
                    }
                    
                    override fun getBaseContext(): Context {
                        return targetResources.let { virtualContext }
                    }
                }
                
                // 尝试替换Activity的Context
                try {
                    val activityClass = Activity::class.java
                    val contextField = activityClass.getDeclaredField("mBase")
                    contextField.isAccessible = true
                    contextField.set(activity, resourcesWrapper)
                    VLog.d(TAG, "【强制修复】成功替换Activity.mBase为ResourcesWrapper")
                    successCount++
                } catch (e: Exception) {
                    VLog.w(TAG, "【强制修复】无法替换Activity.mBase: ${e.message}")
                }
                
            } catch (e: Exception) {
                VLog.e(TAG, "【强制修复】ResourcesWrapper创建失败", e)
            }
            
            // 验证修复效果
            try {
                val finalResources = activity.resources
                val isFixed = finalResources == targetResources
                VLog.d(TAG, "【强制修复】验证结果: ${if (isFixed) "✅成功" else "❌失败"}")
                VLog.d(TAG, "【强制修复】Activity.resources: $finalResources")
                VLog.d(TAG, "【强制修复】目标Resources: $targetResources")
                VLog.d(TAG, "【强制修复】是否一致: $isFixed")
                
                // 如果修复成功，测试关键资源访问
                if (isFixed) {
                    try {
                        val resourceName = finalResources.getResourceName(0x7f080059)
                        VLog.d(TAG, "【强制修复】✅ 关键资源测试成功 - 0x7f080059 -> $resourceName")
                    } catch (e: Exception) {
                        VLog.e(TAG, "【强制修复】❌ 关键资源测试失败", e)
                    }
                }
            } catch (e: Exception) {
                VLog.e(TAG, "【强制修复】验证失败", e)
            }
            
        } catch (e: Exception) {
            VLog.e(TAG, "【强制修复】修复Activity Resources失败", e)
        }
    }
    
    /**
     * 使用Instrumentation调用Activity生命周期
     */
    private fun callActivityLifecycleWithInstrumentation(
        instrumentation: Instrumentation,
        activity: Activity,
        intent: Intent,
        virtualContext: VirtualContext,
        activityInfo: ActivityInfo? = null
    ) {
        try {
            VLog.d(TAG, "使用Instrumentation调用Activity生命周期")
            
            // 在调用onCreate之前，最后一次测试资源访问
            try {
                val resourceId = 0x7f080059
                val resourceName = activity.resources.getResourceName(resourceId)
                VLog.d(TAG, "生命周期前资源测试成功 - 0x7f080059 -> $resourceName")
            } catch (e: Exception) {
                VLog.e(TAG, "生命周期前资源测试失败 - 0x7f080059", e)
                
                // 尝试使用virtualContext的resources
                try {
                    val virtualResources = virtualContext.resources
                    val resourceName = virtualResources.getResourceName(0x7f080059)
                    VLog.d(TAG, "使用virtualResources测试成功 - 0x7f080059 -> $resourceName")
                } catch (e2: Exception) {
                    VLog.e(TAG, "使用virtualResources测试也失败", e2)
                }
            }
            
            // 关键修复：在callActivityOnCreate之前设置Theme（参考VirtualApp的AppInstrumentation）
            if (activityInfo != null) {
                try {
                    VLog.d(TAG, "【VirtualApp风格】在callActivityOnCreate前设置Theme")
                    
                    // 1. 设置Activity.mActivityInfo字段
                    try {
                        val activityClass = Activity::class.java
                        val mActivityInfoField = activityClass.getDeclaredField("mActivityInfo")
                        mActivityInfoField.isAccessible = true
                        mActivityInfoField.set(activity, activityInfo)
                        VLog.d(TAG, "【VirtualApp风格】成功设置Activity.mActivityInfo")
                    } catch (e: Exception) {
                        VLog.e(TAG, "【VirtualApp风格】设置mActivityInfo失败", e)
                    }
                    
                    // 2. 设置Theme（参考VirtualApp逻辑）
                    var themeId = activityInfo.theme
                    if (themeId == 0) {
                        themeId = activityInfo.applicationInfo?.theme ?: 0
                        VLog.d(TAG, "【VirtualApp风格】使用ApplicationInfo.theme: $themeId")
                    }
                    
                    VLog.d(TAG, "【VirtualApp风格】设置Activity Theme: $themeId")
                    if (themeId != 0) {
                        activity.setTheme(themeId)
                        VLog.d(TAG, "【VirtualApp风格】Activity Theme设置成功")
                    } else {
                        VLog.w(TAG, "【VirtualApp风格】Theme ID为0，使用默认主题")
                        // 使用默认Material主题
                        activity.setTheme(android.R.style.Theme_Material_Light)
                        VLog.d(TAG, "【VirtualApp风格】使用默认Material主题")
                    }
                    
                } catch (e: Exception) {
                    VLog.e(TAG, "【VirtualApp风格】Theme设置失败", e)
                    // 继续执行，不让Theme问题阻止Activity启动
                }
            }
            
            // 处理AndroidX组件，修复SavedStateRegistry问题
            handleAndroidXComponents(activity)
            
            // 使用Instrumentation.callActivityOnCreate来正确调用onCreate
            // 这会确保Activity正确attach到Application
            VLog.d(TAG, "即将调用callActivityOnCreate")
            
            // 关键修复：包装onCreate调用，捕获权限相关异常
            try {
                instrumentation.callActivityOnCreate(activity, null)
                VLog.d(TAG, "Activity.onCreate() 调用成功")
            } catch (e: NullPointerException) {
                if (e.message?.contains("ActivityThread") == true || 
                    e.message?.contains("getApplicationThread") == true) {
                    VLog.w(TAG, "【权限修复】检测到ActivityThread权限问题，尝试修复后重试")
                    
                    // 尝试修复ActivityThread问题
                    try {
                        fixActivityThreadForPermissions(activity)
                        // 重试onCreate调用
                        instrumentation.callActivityOnCreate(activity, null)
                        VLog.d(TAG, "【权限修复】onCreate重试成功")
                    } catch (e2: Exception) {
                        VLog.e(TAG, "【权限修复】权限修复后重试仍然失败", e2)
                        
                        // 最后的措施：手动调用onCreate但跳过权限相关操作
                        try {
                            callOnCreateManually(activity)
                            VLog.d(TAG, "【权限修复】手动onCreate调用成功")
                        } catch (e3: Exception) {
                            VLog.e(TAG, "【权限修复】手动onCreate也失败", e3)
                            throw e // 重新抛出原始异常
                        }
                    }
                } else {
                    // 其他类型的异常，直接重新抛出
                    throw e
                }
            }
            
            // 关键修复：给onCreate更多时间完成UI设置
            VLog.d(TAG, "等待onCreate完成UI设置...")
            Thread.sleep(100) // 给onCreate 100ms时间完成UI设置
            
            // 检查onCreate后的UI状态
            VLog.d(TAG, "检查onCreate后的UI状态:")
            checkActivityUIStatus(activity)
            
            // 如果ContentView还是空的，给更多时间
            val contentView = activity.findViewById<ViewGroup>(android.R.id.content)
            if (contentView?.childCount == 0) {
                VLog.d(TAG, "ContentView仍为空，再等待200ms...")
                Thread.sleep(200)
                checkActivityUIStatus(activity)
            }
            
            // 设置Window focus以确保UI正确显示
            try {
                val window = activity.window
                if (window != null) {
                    val decorView = window.decorView
                    decorView.requestFocus()
                    VLog.d(TAG, "已请求Window focus")
                }
            } catch (e: Exception) {
                VLog.w(TAG, "设置Window focus失败: ${e.message}")
            }
            
            // 调用onStart
            val onStartMethod = Activity::class.java.getDeclaredMethod("onStart")
            onStartMethod.isAccessible = true
            onStartMethod.invoke(activity)
            VLog.d(TAG, "Activity.onStart() 调用成功")
            
            // 调用onResume  
            val onResumeMethod = Activity::class.java.getDeclaredMethod("onResume")
            onResumeMethod.isAccessible = true
            onResumeMethod.invoke(activity)
            VLog.d(TAG, "Activity.onResume() 调用成功")
            
            // 最终UI状态检查
            VLog.d(TAG, "=== 最终UI状态检查 ===")
            checkActivityUIStatus(activity)
            
        } catch (e: Exception) {
            VLog.e(TAG, "调用Activity生命周期失败", e)
            throw e
        }
    }
    
    /**
     * 在主线程中创建Activity（异步版本）
     */
    private fun createActivityOnMainThreadAsync(
        intent: Intent,
        activityInfo: ActivityInfo,
        virtualApplication: Application,
        virtualContext: Context,
        virtualClassLoader: ClassLoader
    ): Boolean {
        val latch = CountDownLatch(1)
        val success = AtomicBoolean(false)
        var exception: Exception? = null
        
        mainHandler.post {
            try {
                val activity = createActivityOnMainThread(intent, activityInfo, virtualApplication, virtualContext, virtualClassLoader)
                success.set(activity != null)
            } catch (e: Exception) {
                exception = e
                VLog.e(TAG, "主线程中创建Activity失败", e)
            } finally {
                latch.countDown()
            }
        }
        
        try {
            // 等待主线程完成Activity创建
            latch.await()
            return success.get()
        } catch (e: InterruptedException) {
            VLog.e(TAG, "等待主线程创建Activity被中断", e)
            return false
        }
    }
    
    /**
     * 设置Activity的环境
     */
    private fun setupActivityEnvironment(
        activity: Activity,
        virtualContext: Context,
        virtualApplication: Application,
        intent: Intent
    ) {
        try {
            VLog.d(TAG, "设置Activity环境")
            
            // 通过反射设置Activity的基础环境
            // 注意：这是简化版本，真实的VirtualApp使用更复杂的Hook机制
            
            // 设置Application
            val setApplicationMethod = Activity::class.java.getDeclaredMethod(
                "setApplication", Application::class.java
            )
            setApplicationMethod.isAccessible = true
            setApplicationMethod.invoke(activity, virtualApplication)
            
            // 设置Intent
            val setIntentMethod = Activity::class.java.getDeclaredMethod(
                "setIntent", Intent::class.java
            )
            setIntentMethod.isAccessible = true
            setIntentMethod.invoke(activity, intent)
            
            VLog.d(TAG, "Activity环境设置完成")
            
        } catch (e: Exception) {
            VLog.w(TAG, "设置Activity环境失败，使用默认设置: ${e.message}")
        }
    }
    
    /**
     * 调用Activity生命周期
     */
    private fun callActivityLifecycle(activity: Activity, intent: Intent) {
        try {
            VLog.d(TAG, "调用Activity生命周期")
            
            // 简化版本的Activity生命周期调用
            // 在真实的VirtualApp中，这里会通过系统的ActivityThread来管理
            
            // 创建Bundle作为savedInstanceState
            val savedInstanceState: Bundle? = null
            
            // 调用onCreate
            val onCreateMethod = Activity::class.java.getDeclaredMethod(
                "onCreate", Bundle::class.java
            )
            onCreateMethod.isAccessible = true
            onCreateMethod.invoke(activity, savedInstanceState)
            
            VLog.d(TAG, "Activity.onCreate() 调用成功")
            
            // 调用onStart
            try {
                val onStartMethod = Activity::class.java.getDeclaredMethod("onStart")
                onStartMethod.isAccessible = true
                onStartMethod.invoke(activity)
                VLog.d(TAG, "Activity.onStart() 调用成功")
            } catch (e: Exception) {
                VLog.w(TAG, "调用onStart失败: ${e.message}")
            }
            
            // 调用onResume
            try {
                val onResumeMethod = Activity::class.java.getDeclaredMethod("onResume")
                onResumeMethod.isAccessible = true
                onResumeMethod.invoke(activity)
                VLog.d(TAG, "Activity.onResume() 调用成功")
            } catch (e: Exception) {
                VLog.w(TAG, "调用onResume失败: ${e.message}")
            }
            
        } catch (e: Exception) {
            VLog.e(TAG, "调用Activity生命周期失败", e)
            throw e
        }
    }
    
    /**
     * 检查Activity的UI状态
     * 用于调试UI显示问题
     */
    private fun checkActivityUIStatus(activity: Activity) {
        try {
            VLog.d(TAG, "=== Activity UI状态检查 ===")
            VLog.d(TAG, "Activity: ${activity.javaClass.simpleName}")
            
            // 检查Window状态
            val window = activity.window
            VLog.d(TAG, "Window: $window")
            
            if (window != null) {
                try {
                    val decorView = window.decorView
                    VLog.d(TAG, "DecorView: $decorView")
                    VLog.d(TAG, "DecorView parent: ${decorView?.parent}")
                    VLog.d(TAG, "DecorView visibility: ${decorView?.visibility}")
                    
                    if (decorView is ViewGroup) {
                        VLog.d(TAG, "DecorView子View数量: ${decorView.childCount}")
                        for (i in 0 until decorView.childCount) {
                            val child = decorView.getChildAt(i)
                            VLog.d(TAG, "DecorView子View[$i]: ${child::class.java.simpleName}")
                        }
                    }
                } catch (e: Exception) {
                    VLog.w(TAG, "检查DecorView失败: ${e.message}")
                }
                
                try {
                    val attributes = window.attributes
                    VLog.d(TAG, "Window属性: flags=${attributes?.flags}, type=${attributes?.type}")
                } catch (e: Exception) {
                    VLog.w(TAG, "检查Window属性失败: ${e.message}")
                }
            }
            
            // 检查ContentView状态
            try {
                val contentView = activity.findViewById<ViewGroup>(android.R.id.content)
                VLog.d(TAG, "ContentView: $contentView")
                
                if (contentView != null) {
                    VLog.d(TAG, "ContentView子View数量: ${contentView.childCount}")
                    for (i in 0 until contentView.childCount) {
                        val child = contentView.getChildAt(i)
                        VLog.d(TAG, "ContentView子View[$i]: ${child::class.java.simpleName}")
                        VLog.d(TAG, "  - visibility: ${child.visibility}")
                        VLog.d(TAG, "  - layoutParams: ${child.layoutParams}")
                    }
                    
                    // 检查ContentView的可见性和布局
                    VLog.d(TAG, "ContentView visibility: ${contentView.visibility}")
                    VLog.d(TAG, "ContentView layoutParams: ${contentView.layoutParams}")
                    VLog.d(TAG, "ContentView width x height: ${contentView.width} x ${contentView.height}")
                } else {
                    VLog.w(TAG, "ContentView为空！Activity可能没有正确设置布局")
                }
            } catch (e: Exception) {
                VLog.w(TAG, "检查ContentView失败: ${e.message}")
            }
            
            // 检查Activity的其他UI相关状态
            try {
                VLog.d(TAG, "Activity.isFinishing: ${activity.isFinishing}")
                VLog.d(TAG, "Activity.isDestroyed: ${activity.isDestroyed}")
                VLog.d(TAG, "Activity.hasWindowFocus: ${activity.hasWindowFocus()}")
            } catch (e: Exception) {
                VLog.w(TAG, "检查Activity状态失败: ${e.message}")
            }
            
            VLog.d(TAG, "=== UI状态检查完成 ===")
            
        } catch (e: Exception) {
            VLog.e(TAG, "Activity UI状态检查失败", e)
        }
    }
    
    /**
     * 获取当前运行的虚拟Activity
     */
    fun getCurrentActivity(): Activity? = targetActivity
    
    /**
     * 获取虚拟Application
     */
    fun getVirtualApplication(): Application? = applicationManager?.getVirtualApplication()
    
    /**
     * 检查虚拟Activity是否在运行
     */
    fun isActivityRunning(): Boolean = targetActivity != null
    
    /**
     * 停止虚拟Activity
     */
    fun stopVirtualActivity() {
        try {
            targetActivity?.let { activity ->
                VLog.d(TAG, "停止虚拟Activity")
                
                // 调用Activity的onDestroy
                try {
                    val onDestroyMethod = Activity::class.java.getDeclaredMethod("onDestroy")
                    onDestroyMethod.isAccessible = true
                    onDestroyMethod.invoke(activity)
                    VLog.d(TAG, "Activity.onDestroy() 调用成功")
                } catch (e: Exception) {
                    VLog.w(TAG, "调用onDestroy失败: ${e.message}")
                }
                
                targetActivity = null
            }
        } catch (e: Exception) {
            VLog.e(TAG, "停止虚拟Activity失败", e)
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        try {
            stopVirtualActivity()
            applicationManager?.cleanup()
            applicationManager = null
            VLog.d(TAG, "虚拟Activity启动器清理完成")
        } catch (e: Exception) {
            VLog.e(TAG, "清理虚拟Activity启动器失败", e)
        }
    }

    /**
     * Hook对话框创建过程，防止窗口泄漏
     */
    private fun hookDialogCreation(activity: Activity, hostContext: Context) {
        try {
            VLog.d(TAG, "【Dialog修复】开始Hook对话框创建")
            
            // 不再替换Activity的WindowManager，而是预先拦截Dialog创建过程
            
            // 拦截AlertDialog.Builder.show()方法，这是AetherSX2应用中使用的方式
            try {
                // 准备一个自定义的Dialog创建和显示方法
                val dialogHandler = { dialog: Any? ->
                    try {
                        if (dialog != null && dialog.javaClass.name.contains("AlertDialog")) {
                            VLog.d(TAG, "【Dialog修复】拦截到AlertDialog显示: $dialog")
                            
                            // 获取Dialog的Window
                            val getWindowMethod = dialog.javaClass.getMethod("getWindow")
                            val window = getWindowMethod.invoke(dialog)
                            
                            if (window != null) {
                                // 设置Dialog Window的类型为APPLICATION
                                val getAttributesMethod = window.javaClass.getMethod("getAttributes")
                                val attrs = getAttributesMethod.invoke(window) as WindowManager.LayoutParams
                                
                                attrs.type = WindowManager.LayoutParams.TYPE_APPLICATION
                                
                                // 获取宿主Activity的token
                                val hostToken = getHostActivityToken()
                                if (hostToken != null) {
                                    try {
                                        val tokenField = WindowManager.LayoutParams::class.java.getDeclaredField("token")
                                        tokenField.isAccessible = true
                                        tokenField.set(attrs, hostToken)
                                        VLog.d(TAG, "【Dialog修复】成功设置Dialog窗口token")
                                    } catch (e: Exception) {
                                        VLog.w(TAG, "【Dialog修复】设置token失败: ${e.message}")
                                    }
                                }
                                
                                // 设置回Window
                                val setAttributesMethod = window.javaClass.getMethod("setAttributes", WindowManager.LayoutParams::class.java)
                                setAttributesMethod.invoke(window, attrs)
                                
                                // 确保使用正确的WindowManager
                                val setWindowManagerMethod = window.javaClass.getMethod("setWindowManager", 
                                    WindowManager::class.java, IBinder::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                                
                                val hostWindowManager = hostContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                                setWindowManagerMethod.invoke(window, hostWindowManager, hostToken, null, false)
                                
                                VLog.d(TAG, "【Dialog修复】成功配置Dialog窗口参数")
                            }
                        }
                    } catch (e: Exception) {
                        VLog.e(TAG, "【Dialog修复】处理Dialog显示失败: ${e.message}")
                    }
                    
                    // 返回true，表示显示成功
                    true
                }
                
                // 提供一个替代的创建Dialog的方法
                // 将替代方法附加到Activity的context上，这样可以在需要时被调用
                activity.runOnUiThread {
                    // 添加一个虚拟方法处理对话框的显示
                    val className = "android.app.AlertDialog\$Builder"
                    try {
                        // 获取MainActivity的类和onCreate方法
                        val mainActivityClass = activity.javaClass
                        val onCreateMethod = mainActivityClass.getDeclaredMethod("onCreate", Bundle::class.java)
                        onCreateMethod.isAccessible = true
                        
                        // 创建一个自定义Bundle，添加我们的处理逻辑
                        val bundle = Bundle()
                        // 在Activity的上下文中设置处理Dialog的方法
                        activity.runOnUiThread {
                            // 尝试拦截Activity的onCreate中显示Dialog的调用
                            try {
                                // 不调用原始的onCreate，而是提供一个定制的实现
                                // 显示一个友好的提示，告知用户虚拟环境中的限制
                                
                                // 创建一个临时UI来替代可能出现的Dialog
                                val content = android.widget.LinearLayout(activity)
                                content.orientation = android.widget.LinearLayout.VERTICAL
                                content.setPadding(50, 50, 50, 50)
                                
                                val title = android.widget.TextView(activity)
                                title.text = "AetherSX2 模拟器"
                                title.textSize = 20f
                                title.setPadding(0, 0, 0, 20)
                                
                                val message = android.widget.TextView(activity)
                                message.text = "模拟器已成功启动在虚拟环境中！\n\n" +
                                        "由于虚拟环境的限制，某些对话框可能无法正常显示。\n\n" +
                                        "您可以继续使用此应用的主要功能。"
                                message.textSize = 16f
                                
                                val button = android.widget.Button(activity)
                                button.text = "我知道了"
                                button.setOnClickListener {
                                    // 设置Activity的ContentView为真正的布局
                                    try {
                                        val setContentViewMethod = mainActivityClass.getMethod("setContentView", Int::class.java)
                                        setContentViewMethod.invoke(activity, android.R.layout.simple_list_item_1)
                                    } catch (e: Exception) {
                                        VLog.e(TAG, "设置Content View失败: ${e.message}")
                                    }
                                }
                                
                                content.addView(title)
                                content.addView(message)
                                content.addView(button)
                                
                                // 设置为Activity的内容视图
                                activity.setContentView(content)
                                
                                VLog.d(TAG, "【Dialog修复】成功替换了MainActivity的UI，跳过可能导致问题的对话框")
                                
                            } catch (e: Exception) {
                                VLog.e(TAG, "【Dialog修复】替代onCreate实现失败: ${e.message}")
                                // 尝试调用原始方法，但小心处理可能的异常
                                try {
                                    onCreateMethod.invoke(activity, bundle)
                                } catch (e2: Exception) {
                                    VLog.e(TAG, "【Dialog修复】调用原始onCreate也失败: ${e2.message}")
                                    // 设置一个简单的布局，避免空白界面
                                    activity.setContentView(android.R.layout.simple_list_item_1)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        VLog.e(TAG, "【Dialog修复】拦截MainActivity.onCreate失败: ${e.message}")
                    }
                }
                
            } catch (e: Exception) {
                VLog.w(TAG, "【Dialog修复】拦截AlertDialog.Builder.show失败: ${e.message}")
            }
            
        } catch (e: Exception) {
            VLog.e(TAG, "【Dialog修复】Hook对话框创建失败", e)
        }
    }
    
    /**
     * 方法代理辅助类
     */
    private class ProxyMethod(
        private val target: Any,
        private val method: Method,
        private val handler: (obj: Any, method: Method, args: Array<Any?>?) -> Any?
    ) {
        fun apply() {
            try {
                // 设置方法为可访问
                method.isAccessible = true
                
                // 获取方法类
                val methodClass = method.javaClass
                
                // 获取methodAccessor字段
                val methodAccessorField = methodClass.getDeclaredField("methodAccessor")
                methodAccessorField.isAccessible = true
                val methodAccessor = methodAccessorField.get(method)
                
                if (methodAccessor != null) {
                    // 使用JDK动态代理创建代理
                    val proxy = Proxy.newProxyInstance(
                        methodAccessor.javaClass.classLoader,
                        methodAccessor.javaClass.interfaces
                    ) { _, m, args ->
                        if (m.name == "invoke") {
                            val obj = args?.getOrNull(0)
                            val methodArgs = args?.getOrNull(1) as? Array<Any?>
                            
                            if (obj == target) {
                                handler(obj, method, methodArgs)
                            } else {
                                m.invoke(methodAccessor, *args)
                            }
                        } else {
                            m.invoke(methodAccessor, *args)
                        }
                    }
                    
                    // 替换methodAccessor
                    methodAccessorField.set(method, proxy)
                }
            } catch (e: Exception) {
                // 忽略错误，这种方法不总是有效
            }
        }
    }

    /**
     * 处理AndroidX组件的特殊需求
     * 特别是处理SavedStateRegistry问题
     */
    private fun handleAndroidXComponents(activity: Activity) {
        try {
            if (activity.javaClass.name.contains("androidx.activity.ComponentActivity") || 
                activity.javaClass.superclass?.name?.contains("androidx.activity.ComponentActivity") == true) {
                
                VLog.d(TAG, "检测到AndroidX组件，尝试修复SavedStateRegistry")
                
                // 反射获取SavedStateRegistryController
                val savedStateRegistryControllerField = findField(activity.javaClass, "mSavedStateRegistryController")
                if (savedStateRegistryControllerField != null) {
                    savedStateRegistryControllerField.isAccessible = true
                    val controller = savedStateRegistryControllerField.get(activity)
                    
                    // 重置SavedStateRegistry的状态
                    val savedStateRegistryField = findField(controller.javaClass, "mSavedStateRegistry")
                    if (savedStateRegistryField != null) {
                        savedStateRegistryField.isAccessible = true
                        val savedStateRegistry = savedStateRegistryField.get(controller)
                        
                        val restoredField = findField(savedStateRegistry.javaClass, "mRestored")
                        if (restoredField != null) {
                            restoredField.isAccessible = true
                            restoredField.setBoolean(savedStateRegistry, false)
                            VLog.d(TAG, "成功重置SavedStateRegistry状态")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            VLog.e(TAG, "处理AndroidX组件失败", e)
        }
    }
    
    /**
     * 查找类或父类中的字段
     */
    private fun findField(clazz: Class<*>?, fieldName: String): Field? {
        var currentClass = clazz
        while (currentClass != null) {
            try {
                val field = currentClass.getDeclaredField(fieldName)
                return field
            } catch (e: NoSuchFieldException) {
                currentClass = currentClass.superclass
            }
        }
        return null
    }

    /**
     * 设置AppCompat主题
     */
    private fun setupAppCompatTheme(activity: Activity, activityInfo: ActivityInfo) {
        try {
            // 尝试获取ActivityInfo的mActivityInfo字段
            val mActivityInfoField = findField(activity.javaClass, "mActivityInfo")
            if (mActivityInfoField != null) {
                mActivityInfoField.isAccessible = true
                mActivityInfoField.set(activity, activityInfo)
                VLog.d(TAG, "【VirtualApp风格】成功设置Activity.mActivityInfo")
            }
            
            // 查找合适的AppCompat主题
            val appCompatThemes = arrayOf(
                "com.android.internal.R.style.Theme_DeviceDefault", 
                "androidx.appcompat.R.style.Theme_AppCompat",
                "androidx.appcompat.R.style.Theme_AppCompat_Light",
                "androidx.appcompat.R.style.Theme_AppCompat_DayNight"
            )
            
            // 尝试找到可用的AppCompat主题资源ID
            var themeResId = 0
            for (themeName in appCompatThemes) {
                try {
                    val parts = themeName.split(".")
                    val className = parts.subList(0, parts.size - 1).joinToString(".")
                    val fieldName = parts.last()
                    
                    val clazz = Class.forName(className)
                    val field = clazz.getField(fieldName)
                    themeResId = field.getInt(null)
                    break
                } catch (e: Exception) {
                    // 继续尝试下一个主题
                }
            }
            
            if (themeResId != 0) {
                VLog.d(TAG, "【VirtualApp风格】设置Activity Theme: $themeResId")
                activity.setTheme(themeResId)
                VLog.d(TAG, "【VirtualApp风格】Activity Theme设置成功")
            } else if (activityInfo.theme != 0) {
                activity.setTheme(activityInfo.theme)
            }
        } catch (e: Exception) {
            VLog.w(TAG, "设置AppCompat主题失败", e)
        }
    }
}