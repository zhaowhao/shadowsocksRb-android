/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2018 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2018 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.Build.VERSION_CODES.O
import android.os.Build.VERSION_CODES.Q
import android.os.UserManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.work.Configuration
import androidx.work.WorkManager
import com.github.shadowsocks.acl.Acl
import com.github.shadowsocks.aidl.ShadowsocksConnection
import com.github.shadowsocks.core.BuildConfig
import com.github.shadowsocks.core.R
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.database.ProfileManager
import com.github.shadowsocks.net.TcpFastOpen
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.subscription.SubscriptionService
import com.github.shadowsocks.utils.*
import com.github.shadowsocks.work.UpdateCheck
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlin.reflect.KClass

object Core : Configuration.Provider {
    const val TAG = "Core"
    lateinit var app: Application
        @VisibleForTesting set
    lateinit var configureIntent: (Context) -> PendingIntent
    val activity by lazy { app.getSystemService<ActivityManager>()!! }
    val clipboard by lazy { app.getSystemService<ClipboardManager>()!! }
    val connectivity by lazy { app.getSystemService<ConnectivityManager>()!! }
    val notification by lazy { app.getSystemService<NotificationManager>()!! }
    val user by lazy { app.getSystemService<UserManager>()!! }
    val packageInfo: PackageInfo by lazy { getPackageInfo(app.packageName) }
    val deviceStorage by lazy { if (Build.VERSION.SDK_INT < 24) app else DeviceStorageApp(app) }
    val directBootSupported by lazy {
        Build.VERSION.SDK_INT >= 24 && try {
            app.getSystemService<DevicePolicyManager>()?.storageEncryptionStatus ==
                    DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER
        } catch (_: RuntimeException) {
            false
        }
    }

    val activeProfileIds get() = ProfileManager.getProfile(DataStore.profileId).let {
        if (it == null) emptyList() else listOfNotNull(it.id, it.udpFallback)
    }
    val currentProfile: ProfileManager.ExpandedProfile? get() {
        if (DataStore.directBootAware) DirectBoot.getDeviceProfile()?.apply { return this }
        return ProfileManager.expand(ProfileManager.getProfile(DataStore.profileId) ?: return null)
    }

    fun switchProfile(id: Long): Profile {
        val result = ProfileManager.getProfile(id) ?: ProfileManager.createProfile()
        DataStore.profileId = result.id
        return result
    }

    fun init(app: Application, configureClass: KClass<out Any>) {
        this.app = app
        this.configureIntent = {
            PendingIntent.getActivity(it, 0, Intent(it, configureClass.java)
                    .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE)
        }

        if (Build.VERSION.SDK_INT >= 24) {  // migrate old files
            deviceStorage.moveDatabaseFrom(app, Key.DB_PUBLIC)
        }

        // overhead of debug mode is minimal: https://github.com/Kotlin/kotlinx.coroutines/blob/f528898/docs/debugging.md#debug-mode
        System.setProperty(DEBUG_PROPERTY_NAME, DEBUG_PROPERTY_VALUE_ON)

        UpdateCheck.enqueue()

        // handle data restored/crash
        if (Build.VERSION.SDK_INT >= 24 && DataStore.directBootAware && user.isUserUnlocked) {
            DirectBoot.flushTrafficStats()
        }
        if (DataStore.tcpFastOpen && !TcpFastOpen.sendEnabled) TcpFastOpen.enableTimeout()
        if (DataStore.publicStore.getLong(Key.assetUpdateTime, -1) != packageInfo.lastUpdateTime) {
            val assetManager = app.assets
            try {
                for (file in assetManager.list("acl")!!) assetManager.open("acl/$file").use { input ->
                    File(deviceStorage.noBackupFilesDir, file).outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: IOException) {
                printLog(e)
            }
            DataStore.publicStore.putLong(Key.assetUpdateTime, packageInfo.lastUpdateTime)
        }
        updateNotificationChannels()
    }

    override fun getWorkManagerConfiguration() = Configuration.Builder().apply {
        setDefaultProcessName(app.packageName + ":bg")
        setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.VERBOSE else Log.INFO)
        setExecutor { GlobalScope.launch { it.run() } }
        setTaskExecutor { GlobalScope.launch { it.run() } }
    }.build()

    fun updateNotificationChannels() {
        if (Build.VERSION.SDK_INT >= O) @RequiresApi(O) {
            notification.createNotificationChannels(listOf(
                    NotificationChannel("service-vpn", app.getText(R.string.service_vpn),
                            if (Build.VERSION.SDK_INT >= 28) NotificationManager.IMPORTANCE_MIN
                            else NotificationManager.IMPORTANCE_LOW),   // #1355
                    NotificationChannel("service-proxy", app.getText(R.string.service_proxy),
                            NotificationManager.IMPORTANCE_LOW),
                    NotificationChannel("service-transproxy", app.getText(R.string.service_transproxy),
                            NotificationManager.IMPORTANCE_LOW),
                    SubscriptionService.notificationChannel,
                    NotificationChannel("update", app.getText(R.string.update_channel),
                            NotificationManager.IMPORTANCE_DEFAULT)
            ).apply {
                forEach {
                    it.setShowBadge(false)
                    if (Build.VERSION.SDK_INT >= Q) {
                        it.setAllowBubbles(false)
                    }
                }
            })
        }
    }

    fun getPackageInfo(packageName: String) = app.packageManager.getPackageInfo(packageName, 0)!!

    fun trySetPrimaryClip(clip: String) = try {
        clipboard.setPrimaryClip(ClipData.newPlainText(null, clip))
        true
    } catch (e: RuntimeException) {
        Log.d(TAG, e.readableMessage, e)
        false
    }

    fun startService() = ContextCompat.startForegroundService(app, Intent(app, ShadowsocksConnection.serviceClass))
    fun reloadService() = app.sendBroadcast(Intent(Action.RELOAD).setPackage(app.packageName))
    fun stopService() = app.sendBroadcast(Intent(Action.CLOSE).setPackage(app.packageName))
}
