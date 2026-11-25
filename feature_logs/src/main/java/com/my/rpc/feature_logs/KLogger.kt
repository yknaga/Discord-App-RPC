/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * KLogger.kt is part of Rpc
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.rpc.feature_logs

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.my.rpc.domain.interfaces.Logger
import com.my.rpc.domain.model.logs.LogEvent
import com.my.rpc.domain.model.logs.LogLevel

class KLogger: Logger {
    private val logs = mutableStateListOf<LogEvent>()
    fun getLogs(): SnapshotStateList<LogEvent> {
        return logs
    }

    override fun clear() {
        synchronized(logs) { logs.clear() }
    }

    override fun i(tag: String, event: String) {
        addToLog(LogLevel.INFO, tag, event)
    }

    override fun e(tag: String, event: String) {
        addToLog(LogLevel.ERROR, tag, event)
    }

    override fun d(tag: String, event: String) {
        addToLog(LogLevel.DEBUG, tag, event)
    }

    override fun w(tag: String, event: String) {
        addToLog(LogLevel.WARN, tag, event)
    }

    private fun addToLog(level: LogLevel, tag: String, event: String) {
        val log = LogEvent(level, tag, event, System.currentTimeMillis())

        // Keep the in-app log buffer (maximum 300 entries).
        synchronized(logs) {
            if (logs.size >= 300) {
                logs.removeAt(0)
            }
            logs.add(log)
        }

        // Mirror the same event to Android Logcat.
        val priority = when (level) {
            LogLevel.INFO -> android.util.Log.INFO
            LogLevel.DEBUG -> android.util.Log.DEBUG
            LogLevel.WARN -> android.util.Log.WARN
            LogLevel.ERROR -> android.util.Log.ERROR
        }
        android.util.Log.println(priority, "DiscordAppRPC", "[$tag] $event")
    }

    companion object {
        private var instance: KLogger? = null
        @JvmStatic
        fun getInstance(): KLogger? {
            var localInstance = instance
            if (localInstance == null) {
                synchronized(KLogger::class.java) {
                    localInstance = instance
                    if (localInstance == null) {
                        localInstance = KLogger()
                        instance = localInstance
                    }
                }
            }
            return localInstance
        }

        @JvmStatic
        fun init() {
            if (instance == null) {
                instance = KLogger()
            }
        }
    }
}
