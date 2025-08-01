package com.ktpocket.virtualapp.core

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import com.ktpocket.virtualapp.utils.VLog
import java.io.File

/**
 * 虚拟Context包装器
 * 为虚拟应用提供独立的Context环境
 */
class VirtualContext(
    baseContext: Context,
    private val virtualPackageName: String,
    private val virtualApplicationInfo: ApplicationInfo,
    private val virtualClassLoader: ClassLoader,
    private val virtualResources: Resources
) : ContextWrapper(baseContext) {
    
    companion object {
        private const val TAG = "VirtualContext"
    }
    
    override fun getPackageName(): String {
        return virtualPackageName
    }
    
    override fun getApplicationInfo(): ApplicationInfo {
        return virtualApplicationInfo
    }
    
    override fun getClassLoader(): ClassLoader {
        return virtualClassLoader
    }
    
    override fun getResources(): Resources {
        return virtualResources
    }
    
    override fun getPackageManager(): PackageManager {
        // 简化版本：直接返回宿主的PackageManager
        // 在真实VirtualApp中，这里会返回完全虚拟化的PackageManager
        return super.getPackageManager()
    }
    
    override fun getApplicationContext(): Context {
        // 返回虚拟应用的Context
        return this
    }
    
    override fun getDataDir(): File {
        // 返回虚拟应用的数据目录
        val virtualCore = VirtualCore.get()
        return virtualCore.getVirtualAppDataDir(0, virtualPackageName)
    }
    
    override fun getCacheDir(): File {
        return File(dataDir, "cache").apply {
            mkdirs()
        }
    }
    
    override fun getCodeCacheDir(): File {
        return File(dataDir, "code_cache").apply {
            mkdirs()
        }
    }
    
    override fun getFilesDir(): File {
        return File(dataDir, "files").apply {
            mkdirs()
        }
    }
    
    override fun getDatabasePath(name: String): File {
        val dbDir = File(dataDir, "databases")
        dbDir.mkdirs()
        return File(dbDir, name)
    }
    
    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
        // 虚拟应用的SharedPreferences
        val prefsDir = File(dataDir, "shared_prefs")
        prefsDir.mkdirs()
        
        VLog.d(TAG, "虚拟SharedPreferences: $name for $virtualPackageName")
        
        // 使用宿主的SharedPreferences但存储到虚拟目录
        return super.getSharedPreferences("${virtualPackageName}_$name", mode)
    }

}