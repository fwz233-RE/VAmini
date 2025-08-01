package com.ktpocket.virtualapp.utils

import android.util.Log

/**
 * VirtualApp日志工具类
 */
object VLog {
    
    private const val TAG_PREFIX = "VirtualApp"
    private var isDebugMode = true
    
    /**
     * 设置调试模式
     */
    fun setDebugMode(debug: Boolean) {
        isDebugMode = debug
    }
    
    /**
     * Debug日志
     */
    fun d(tag: String, message: String) {
        if (isDebugMode) {
            Log.d("$TAG_PREFIX-$tag", message)
        }
    }
    
    /**
     * Info日志
     */
    fun i(tag: String, message: String) {
        if (isDebugMode) {
            Log.i("$TAG_PREFIX-$tag", message)
        }
    }
    
    /**
     * Warning日志
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w("$TAG_PREFIX-$tag", message, throwable)
        } else {
            Log.w("$TAG_PREFIX-$tag", message)
        }
    }
    
    /**
     * Error日志
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e("$TAG_PREFIX-$tag", message, throwable)
        } else {
            Log.e("$TAG_PREFIX-$tag", message)
        }
    }
    
    /**
     * 打印虚拟应用信息
     */
    fun logVirtualAppInfo(tag: String, packageName: String, action: String) {
        i(tag, "虚拟应用操作 - 包名: $packageName, 动作: $action")
    }
    
    /**
     * 打印安装进度
     */
    fun logInstallProgress(tag: String, packageName: String, step: String) {
        d(tag, "安装进度 - $packageName: $step")
    }
    
    /**
     * 打印启动流程
     */
    fun logLaunchProcess(tag: String, packageName: String, step: String) {
        d(tag, "启动流程 - $packageName: $step")
    }
}