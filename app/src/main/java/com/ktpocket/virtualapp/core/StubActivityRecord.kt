package com.ktpocket.virtualapp.core

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.IBinder

/**
 * Stub Activity记录，用于在Intent之间传递虚拟应用信息
 */
class StubActivityRecord {
    var intent: Intent? = null
    var info: ActivityInfo? = null
    var userId: Int = 0
    var virtualToken: IBinder? = null
    var packageName: String? = null
    var apkPath: String? = null

    constructor(intent: Intent?, info: ActivityInfo?, userId: Int, virtualToken: IBinder?) {
        this.intent = intent
        this.info = info
        this.userId = userId
        this.virtualToken = virtualToken
    }

    constructor(intent: Intent?, packageName: String?, apkPath: String?, userId: Int = 0) {
        this.intent = intent
        this.packageName = packageName
        this.apkPath = apkPath
        this.userId = userId
    }

    constructor(stubIntent: Intent) {
        this.intent = stubIntent.getParcelableExtra("_VA_|_intent_")
        this.packageName = stubIntent.getStringExtra("_VA_|_package_name_")
        this.apkPath = stubIntent.getStringExtra("_VA_|_apk_path_")
        this.userId = stubIntent.getIntExtra("_VA_|_user_id_", 0)
    }

    fun saveToIntent(stubIntent: Intent) {
        intent?.let { stubIntent.putExtra("_VA_|_intent_", it) }
        packageName?.let { stubIntent.putExtra("_VA_|_package_name_", it) }
        apkPath?.let { stubIntent.putExtra("_VA_|_apk_path_", it) }
        stubIntent.putExtra("_VA_|_user_id_", userId)
    }
}