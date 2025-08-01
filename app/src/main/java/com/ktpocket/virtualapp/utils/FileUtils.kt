package com.ktpocket.virtualapp.utils

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * 文件操作工具类
 */
object FileUtils {
    
    /**
     * 计算文件MD5
     */
    fun getFileMD5(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        
        return try {
            val digest = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var length: Int
                while (fis.read(buffer).also { length = it } != -1) {
                    digest.update(buffer, 0, length)
                }
            }
            
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            VLog.e("FileUtils", "计算MD5失败", e)
            null
        }
    }
    
    /**
     * 复制文件
     */
    fun copyFile(source: File, target: File): Boolean {
        return try {
            target.parentFile?.mkdirs()
            
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: IOException) {
            VLog.e("FileUtils", "文件复制失败: ${source.absolutePath} -> ${target.absolutePath}", e)
            false
        }
    }
    
    /**
     * 删除目录及其所有内容
     */
    fun deleteDirectory(directory: File): Boolean {
        return try {
            if (directory.exists()) {
                directory.deleteRecursively()
            } else {
                true
            }
        } catch (e: Exception) {
            VLog.e("FileUtils", "删除目录失败: ${directory.absolutePath}", e)
            false
        }
    }
    
    /**
     * 创建目录
     */
    fun createDirectory(directory: File): Boolean {
        return try {
            if (!directory.exists()) {
                directory.mkdirs()
            } else {
                true
            }
        } catch (e: Exception) {
            VLog.e("FileUtils", "创建目录失败: ${directory.absolutePath}", e)
            false
        }
    }
    
    /**
     * 获取目录大小
     */
    fun getDirectorySize(directory: File): Long {
        return try {
            if (!directory.exists() || !directory.isDirectory) {
                0L
            } else {
                directory.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            }
        } catch (e: Exception) {
            VLog.e("FileUtils", "计算目录大小失败: ${directory.absolutePath}", e)
            0L
        }
    }
    
    /**
     * 格式化文件大小
     */
    fun formatFileSize(sizeBytes: Long): String {
        return when {
            sizeBytes < 1024 -> "${sizeBytes}B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024}KB"
            sizeBytes < 1024 * 1024 * 1024 -> "${sizeBytes / (1024 * 1024)}MB"
            else -> "${sizeBytes / (1024 * 1024 * 1024)}GB"
        }
    }
    
    /**
     * 检查文件是否为APK
     */
    fun isApkFile(file: File): Boolean {
        return file.exists() && file.isFile && file.extension.lowercase() == "apk"
    }
    
    /**
     * 获取安全的文件名（移除特殊字符）
     */
    fun getSafeFileName(fileName: String): String {
        return fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}