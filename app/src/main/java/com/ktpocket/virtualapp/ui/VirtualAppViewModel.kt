package com.ktpocket.virtualapp.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ktpocket.virtualapp.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 虚拟应用管理ViewModel
 */
class VirtualAppViewModel : ViewModel() {
    
    private val _uiState = MutableStateFlow(VirtualAppUiState())
    val uiState: StateFlow<VirtualAppUiState> = _uiState.asStateFlow()
    
    private lateinit var apkManager: ApkManager
    
    fun initialize(context: Context) {
        apkManager = ApkManager(context)
        // VirtualCore已在Application中初始化，这里直接加载数据
        loadData()
    }
    
    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            try {
                val assetsApks = apkManager.getAssetsApkList()
                val installedApps = VirtualCore.get().getInstalledApps()
                
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    assetsApks = assetsApks,
                    installedApps = installedApps,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "加载数据失败: ${e.message}"
                )
            }
        }
    }
    
    fun installApk(apkInfo: ApkInfo) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                installingPackages = _uiState.value.installingPackages + apkInfo.packageName
            )
            
            try {
                val result = apkManager.installApkFromAssets(apkInfo)
                
                if (result.isSuccess) {
                    val installedApps = VirtualCore.get().getInstalledApps()
                    _uiState.value = _uiState.value.copy(
                        installedApps = installedApps,
                        message = "安装成功: ${apkInfo.applicationLabel}"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = (result as InstallResult.Failure).error
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "安装失败: ${e.message}"
                )
            } finally {
                _uiState.value = _uiState.value.copy(
                    installingPackages = _uiState.value.installingPackages - apkInfo.packageName
                )
            }
        }
    }
    
    fun uninstallApp(virtualApp: VirtualAppInfo) {
        viewModelScope.launch {
            try {
                val success = VirtualCore.get().uninstallPackage(virtualApp.packageName)
                
                if (success) {
                    val installedApps = VirtualCore.get().getInstalledApps()
                    _uiState.value = _uiState.value.copy(
                        installedApps = installedApps,
                        message = "卸载成功: ${virtualApp.applicationLabel}"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "卸载失败"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "卸载失败: ${e.message}"
                )
            }
        }
    }
    
    fun launchApp(virtualApp: VirtualAppInfo) {
        viewModelScope.launch {
            try {
                val success = VirtualCore.get().launchApp(virtualApp.packageName)
                
                if (success) {
                    _uiState.value = _uiState.value.copy(
                        message = "启动应用: ${virtualApp.applicationLabel}"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "启动失败"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "启动失败: ${e.message}"
                )
            }
        }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
    
    fun refresh() {
        loadData()
    }
    
    override fun onCleared() {
        super.onCleared()
        if (::apkManager.isInitialized) {
            apkManager.cleanTempFiles()
        }
    }
}

/**
 * UI状态数据类
 */
data class VirtualAppUiState(
    val isLoading: Boolean = false,
    val assetsApks: List<ApkInfo> = emptyList(),
    val installedApps: List<VirtualAppInfo> = emptyList(),
    val installingPackages: Set<String> = emptySet(),
    val error: String? = null,
    val message: String? = null
) {
    fun isInstalling(packageName: String): Boolean {
        return installingPackages.contains(packageName)
    }
    
    fun isInstalled(packageName: String): Boolean {
        return installedApps.any { it.packageName == packageName }
    }
}