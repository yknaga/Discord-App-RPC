/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * GetAppsUseCase.kt is part of Rpc
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.rpc.data.get_current_data.app

import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import com.blankj.utilcode.util.AppUtils
import com.my.rpc.data.rpc.CommonRpc
import com.my.rpc.data.rpc.RpcImage
import com.my.rpc.data.utils.cleanAppName
import com.my.rpc.data.utils.getLauncherAppName
import com.my.rpc.preference.Prefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Objects
import java.util.SortedMap
import java.util.TreeMap
import javax.inject.Inject

class GetCurrentlyRunningApp @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * The package currently in the foreground, tracked from usage events.
     *
     * The previous implementation only looked at the aggregated
     * `lastTimeUsed` timestamp. That reports the last app that was used, not
     * the app that is in the foreground now: after the detected app was
     * closed, it kept being returned as "currently running", so the service
     * never noticed the app had ended and kept the presence alive.
     */
    private var foregroundPackageName: String? = null
    private var lastEventTime = 0L
    private var seeded = false

    operator fun invoke(beginTime: Long = System.currentTimeMillis() - 10000): CommonRpc {
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val currentTimeMillis = System.currentTimeMillis()

        if (!seeded) {
            // Seed with the most recently used app, then let usage events
            // correct it (they also cover a foreground change that happened
            // shortly before the service started).
            foregroundPackageName =
                lastUsedPackageName(usageStatsManager, beginTime, currentTimeMillis)
            lastEventTime = currentTimeMillis - 60_000
            seeded = true
        }
        updateForegroundFromEvents(usageStatsManager, currentTimeMillis)

        val packageName = foregroundPackageName ?: return CommonRpc()
        if (packageName == "com.my.rpc" || packageName == "com.discord") {
            return CommonRpc()
        }
        return CommonRpc(
            name = context.getLauncherAppName(packageName).cleanAppName(),
            details = null,
            state = null,
            largeImage = RpcImage.ApplicationIcon(packageName, context),
            packageName = packageName
        )
    }

    /** Most recently used package according to the aggregated usage stats. */
    private fun lastUsedPackageName(
        usageStatsManager: UsageStatsManager,
        beginTime: Long,
        endTime: Long,
    ): String? {
        val queryUsageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, beginTime, endTime
        )
        if (queryUsageStats != null && queryUsageStats.size > 1) {
            val treeMap: SortedMap<Long, UsageStats> = TreeMap()
            for (usageStats in queryUsageStats) {
                treeMap[usageStats.lastTimeUsed] = usageStats
            }
            if (!treeMap.isEmpty()) {
                return treeMap[treeMap.lastKey()]?.packageName
            }
        }
        return null
    }

    /**
     * Applies usage events newer than the last processed one:
     * an app becomes foreground on ACTIVITY_RESUMED and stops being
     * foreground when it is stopped (or, before Android 10, paused, which
     * was the only background signal available then). Pauses alone do not
     * clear the foreground on Android 10+, so transient UI such as the
     * notification shade does not kill the presence.
     */
    private fun updateForegroundFromEvents(
        usageStatsManager: UsageStatsManager,
        endTime: Long,
    ) {
        val usageEvents = usageStatsManager.queryEvents(lastEventTime, endTime) ?: return
        val event = UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.timeStamp > lastEventTime) {
                lastEventTime = event.timeStamp
            }
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    foregroundPackageName = event.packageName

                UsageEvents.Event.ACTIVITY_STOPPED ->
                    if (foregroundPackageName == event.packageName) {
                        foregroundPackageName = null
                    }

                UsageEvents.Event.ACTIVITY_PAUSED ->
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                        foregroundPackageName == event.packageName
                    ) {
                        foregroundPackageName = null
                    }
            }
        }
    }
}
