package com.ktpocket.virtualapp.core

import com.ktpocket.virtualapp.utils.VLog

/**
 * 代理ClassLoader - 用于隔离虚拟应用和宿主应用的依赖库
 * 虚拟应用优先使用自己的ClassLoader，避免版本冲突
 */
class ProxyClassLoader(
    private val parentClassLoader: ClassLoader,
    private val appClassLoader: ClassLoader
) : ClassLoader(parentClassLoader) {
    
    companion object {
        private const val TAG = "ProxyClassLoader"
    }
    
    @Throws(ClassNotFoundException::class)
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        var clazz: Class<*>? = null
        
        try {
            // 首先尝试从虚拟应用的ClassLoader加载
            clazz = appClassLoader.loadClass(name)
            VLog.d(TAG, "从虚拟应用ClassLoader加载类: $name")
        } catch (e: ClassNotFoundException) {
            VLog.d(TAG, "虚拟应用ClassLoader找不到类: $name, 尝试宿主ClassLoader")
        }
        
        if (clazz == null) {
            try {
                // 回退到宿主应用的ClassLoader
                clazz = super.loadClass(name, resolve)
                VLog.d(TAG, "从宿主ClassLoader加载类: $name")
            } catch (e: ClassNotFoundException) {
                VLog.e(TAG, "无法加载类: $name")
                throw e
            }
        }
        
        return clazz
    }
    
    override fun findResource(name: String): java.net.URL? {
        // 首先尝试从虚拟应用查找资源
        var resource = appClassLoader.getResource(name)
        if (resource == null) {
            // 回退到宿主应用
            resource = super.findResource(name)
        }
        return resource
    }
    
    override fun findResources(name: String): java.util.Enumeration<java.net.URL>? {
        // 合并两个ClassLoader的资源
        val appResources = try {
            appClassLoader.getResources(name)
        } catch (e: Exception) {
            null
        }
        
        val parentResources = try {
            super.findResources(name)
        } catch (e: Exception) {
            null
        }
        
        return when {
            appResources != null && parentResources != null -> {
                CombinedEnumeration(appResources, parentResources)
            }
            appResources != null -> appResources
            parentResources != null -> parentResources
            else -> null
        }
    }
    
    /**
     * 合并两个Enumeration
     */
    private class CombinedEnumeration<T>(
        private val first: java.util.Enumeration<T>,
        private val second: java.util.Enumeration<T>
    ) : java.util.Enumeration<T> {
        
        override fun hasMoreElements(): Boolean {
            return first.hasMoreElements() || second.hasMoreElements()
        }
        
        override fun nextElement(): T {
            return if (first.hasMoreElements()) {
                first.nextElement()
            } else {
                second.nextElement()
            }
        }
    }
}