package com.ktpocket.virtualapp.core

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ktpocket.virtualapp.ui.theme.VAminiTheme
import com.ktpocket.virtualapp.utils.VLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 虚拟应用启动器Activity - 真正的Stub Activity
 * 负责接收启动请求并在虚拟环境中启动应用
 */
class VirtualLauncherActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "VirtualLauncherActivity"
    }
    
    private var stubRecord: StubActivityRecord? = null
    private var virtualProcess: VirtualAppProcess? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        VLog.d(TAG, "VirtualLauncherActivity启动")
        
        // 解析启动参数
        parseStubIntent()
        
        setContent {
            VAminiTheme {
                VirtualLaunchScreen(
                    stubRecord = stubRecord,
                    onLaunchComplete = { success, message ->
                        handleLaunchResult(success, message)
                    }
                )
            }
        }
    }
    
    private fun parseStubIntent() {
        try {
            val intent = intent
            VLog.d(TAG, "解析Stub Intent")
            
            // 尝试从Intent中恢复StubActivityRecord
            stubRecord = if (intent.hasExtra("_VA_|_intent_") || 
                           intent.hasExtra("_VA_|_package_name_")) {
                // 新格式：从StubActivityRecord恢复
                StubActivityRecord(intent)
            } else {
                // 兼容旧格式
                val packageName = intent.getStringExtra("virtual_package_name")
                val apkPath = intent.getStringExtra("virtual_apk_path")
                val userId = intent.getIntExtra("virtual_user_id", 0)
                val originalIntent = intent.getParcelableExtra<Intent>("original_intent")
                
                if (packageName != null && apkPath != null) {
                    StubActivityRecord(originalIntent, packageName, apkPath, userId)
                } else {
                    null
                }
            }
            
            if (stubRecord != null) {
                VLog.logLaunchProcess(TAG, stubRecord!!.packageName ?: "unknown", "成功解析Stub Intent")
            } else {
                VLog.e(TAG, "无法解析Stub Intent")
            }
            
        } catch (e: Exception) {
            VLog.e(TAG, "解析Stub Intent失败", e)
            stubRecord = null
        }
    }
    
    private fun handleLaunchResult(success: Boolean, message: String) {
        if (success) {
            Toast.makeText(this, "虚拟应用启动成功", Toast.LENGTH_SHORT).show()
            VLog.logVirtualAppInfo(TAG, stubRecord?.packageName ?: "unknown", "启动成功")
        } else {
            Toast.makeText(this, "启动失败: $message", Toast.LENGTH_LONG).show()
            VLog.e(TAG, "虚拟应用启动失败: $message")
        }
        
        // 延迟关闭启动器
        lifecycleScope.launch {
            delay(1000)
            finish()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            virtualProcess?.cleanup()
            VLog.d(TAG, "VirtualLauncherActivity销毁")
        } catch (e: Exception) {
            VLog.e(TAG, "销毁时清理失败", e)
        }
    }
}

/**
 * 虚拟应用启动界面
 */
@Composable
fun VirtualLaunchScreen(
    stubRecord: StubActivityRecord?,
    onLaunchComplete: (Boolean, String) -> Unit
) {
    var isLaunching by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf(0f) }
    var currentStep by remember { mutableStateOf("初始化...") }
    
    val scope = rememberCoroutineScope()
    
    // 启动虚拟应用
    LaunchedEffect(stubRecord) {
        if (stubRecord == null) {
            onLaunchComplete(false, "启动参数无效")
            return@LaunchedEffect
        }
        
        scope.launch {
            try {
                val steps = listOf(
                    "检查虚拟应用信息..." to 0.1f,
                    "创建虚拟进程..." to 0.3f,
                    "加载APK文件..." to 0.5f,
                    "初始化虚拟环境..." to 0.7f,
                    "启动应用..." to 0.9f,
                    "完成" to 1.0f
                )
                
                for ((step, targetProgress) in steps) {
                    currentStep = step
                    
                    // 模拟每个步骤的执行时间
                    val duration = 300L + (targetProgress * 200).toLong()
                    val startProgress = progress
                    val progressStep = (targetProgress - startProgress) / (duration / 50)
                    
                    for (i in 0..(duration / 50)) {
                        progress = startProgress + (progressStep * i).coerceAtMost(targetProgress - startProgress)
                        delay(50)
                    }
                    progress = targetProgress
                    
                    // 在特定步骤执行真实操作
                    when (step) {
                        "启动应用..." -> {
                            val success = launchVirtualApp(stubRecord)
                            if (!success) {
                                onLaunchComplete(false, "启动虚拟应用失败")
                                return@launch
                            }
                        }
                    }
                }
                
                isLaunching = false
                onLaunchComplete(true, "启动成功")
                
            } catch (e: Exception) {
                VLog.e("VirtualLaunchScreen", "启动过程失败", e)
                isLaunching = false
                onLaunchComplete(false, "启动异常: ${e.message}")
            }
        }
    }
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 应用信息
            Card(
                modifier = Modifier.size(80.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (stubRecord?.packageName?.take(2)?.uppercase() ?: "VA"),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = stubRecord?.packageName ?: "虚拟应用",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            if (isLaunching) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = currentStep,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "启动完成",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 启动虚拟应用的核心逻辑
 */
private suspend fun launchVirtualApp(stubRecord: StubActivityRecord): Boolean {
    return try {
        VLog.logLaunchProcess("VirtualLaunchScreen", stubRecord.packageName ?: "unknown", "开始启动虚拟应用")
        
        delay(300) // 模拟初始化时间
        
        // 创建启动VirtualAppActivity的Intent
        val virtualAppIntent = Intent().apply {
            setClass(VirtualCore.get().getContext(), VirtualAppActivity::class.java)
            putExtra(VirtualAppActivity.EXTRA_PACKAGE_NAME, stubRecord.packageName)
            putExtra(VirtualAppActivity.EXTRA_APK_PATH, stubRecord.apkPath)
            
            // 尝试获取目标Activity名称
            val targetActivityName = findTargetActivity(stubRecord)
            if (targetActivityName != null) {
                putExtra(VirtualAppActivity.EXTRA_ACTIVITY_NAME, targetActivityName)
            }
            
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        
        // 启动VirtualAppActivity
        VirtualCore.get().getContext().startActivity(virtualAppIntent)
        
        VLog.logVirtualAppInfo("VirtualLaunchScreen", stubRecord.packageName ?: "unknown", "虚拟应用启动成功")
        true
    } catch (e: Exception) {
        VLog.e("VirtualLaunchScreen", "启动虚拟应用失败", e)
        false
    }
}

/**
 * 查找目标Activity
 */
private fun findTargetActivity(stubRecord: StubActivityRecord): String? {
    return try {
        val apkPath = stubRecord.apkPath ?: return null
        val context = VirtualCore.get().getContext()
        val pm = context.packageManager
        
        val packageInfo = pm.getPackageArchiveInfo(
            apkPath,
            PackageManager.GET_ACTIVITIES
        ) ?: return null
        
        // 查找合适的启动Activity
        val targetActivity = packageInfo.activities?.find { activityInfo ->
            activityInfo.name.contains("MainActivity") ||
            activityInfo.name.contains("LauncherActivity") ||
            activityInfo.name.contains("SplashActivity") ||
            activityInfo.name.endsWith("Activity")
        } ?: packageInfo.activities?.firstOrNull()
        
        targetActivity?.name?.also { name ->
            VLog.d("VirtualLaunchScreen", "找到目标Activity: $name")
        }
    } catch (e: Exception) {
        VLog.e("VirtualLaunchScreen", "查找目标Activity失败", e)
        null
    }
}