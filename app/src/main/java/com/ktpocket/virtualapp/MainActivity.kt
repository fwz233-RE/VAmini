package com.ktpocket.virtualapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ktpocket.virtualapp.core.*
import com.ktpocket.virtualapp.ui.VirtualAppViewModel
import com.ktpocket.virtualapp.ui.theme.VAminiTheme

class MainActivity : ComponentActivity() {
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            // 权限已授予，初始化应用
        } else {
            // 权限被拒绝，显示提示
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // 初始化虚拟核心
        initializeVirtualCore()
        
        // 检查权限
        checkPermissions()
        
        setContent {
            VAminiTheme {
                VirtualAppScreen()
            }
        }
    }
    
    private fun initializeVirtualCore() {
        try {
            // 初始化虚拟核心
            com.ktpocket.virtualapp.core.VirtualCore.get().initialize(this)
            android.util.Log.d("MainActivity", "VirtualCore 初始化完成")
            
            // 初始化Hook框架
            com.ktpocket.virtualapp.core.HookManager.get().initialize()
            android.util.Log.d("MainActivity", "HookManager 初始化完成")
            
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "初始化失败", e)
        }
    }
    
    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirtualAppScreen(
    viewModel: VirtualAppViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    
    // 初始化ViewModel
    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }
    
    // 显示错误信息
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            // 这里可以显示Snackbar或Toast
        }
    }
    
    // 显示成功信息
    uiState.message?.let { message ->
        LaunchedEffect(message) {
            // 这里可以显示Snackbar或Toast
        }
    }
    
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Assets APK", "已安装应用")
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VirtualApp Mini") },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Tab选择器
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            
            // 内容区域
            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> AssetsApkList(
                        apks = uiState.assetsApks,
                        onInstallClick = viewModel::installApk,
                        isLoading = uiState.isLoading,
                        installingPackages = uiState.installingPackages
                    )
                    1 -> InstalledAppsList(
                        apps = uiState.installedApps,
                        onLaunchClick = viewModel::launchApp,
                        onUninstallClick = viewModel::uninstallApp,
                        isLoading = uiState.isLoading
                    )
                }
                
                // 加载指示器
                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                
                // 错误信息显示
                uiState.error?.let { error ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .align(Alignment.BottomCenter),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                
                // 成功信息显示
                uiState.message?.let { message ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .align(Alignment.BottomCenter),
                        colors = CardDefaults.cardColors(containerColor = Color.Green.copy(alpha = 0.1f))
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(16.dp),
                            color = Color.Green
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AssetsApkList(
    apks: List<ApkInfo>,
    onInstallClick: (ApkInfo) -> Unit,
    isLoading: Boolean,
    installingPackages: Set<String>
) {
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }
    
    if (apks.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("未找到APK文件")
        }
        return
    }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(apks) { apk ->
            ApkInfoCard(
                apkInfo = apk,
                onInstallClick = { onInstallClick(apk) },
                isInstalling = installingPackages.contains(apk.packageName)
            )
        }
    }
}

@Composable
fun InstalledAppsList(
    apps: List<VirtualAppInfo>,
    onLaunchClick: (VirtualAppInfo) -> Unit,
    onUninstallClick: (VirtualAppInfo) -> Unit,
    isLoading: Boolean
) {
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }
    
    if (apps.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("暂无已安装的虚拟应用")
        }
        return
    }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(apps) { app ->
            VirtualAppCard(
                appInfo = app,
                onLaunchClick = { onLaunchClick(app) },
                onUninstallClick = { onUninstallClick(app) }
            )
        }
    }
}

@Composable
fun ApkInfoCard(
    apkInfo: ApkInfo,
    onInstallClick: () -> Unit,
    isInstalling: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (!isInstalling) onInstallClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // APK信息
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = apkInfo.applicationLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = apkInfo.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "版本: ${apkInfo.versionName} | 大小: ${apkInfo.getFormattedSize()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // 安装按钮
            if (isInstalling) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(onClick = onInstallClick) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "安装",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun VirtualAppCard(
    appInfo: VirtualAppInfo,
    onLaunchClick: () -> Unit,
    onUninstallClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 应用信息
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = appInfo.applicationLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = appInfo.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
    Text(
                    text = "版本: ${appInfo.versionName} | 用户: ${appInfo.userId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // 操作按钮
            Row {
                IconButton(onClick = onLaunchClick) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "启动",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onUninstallClick) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "卸载",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}