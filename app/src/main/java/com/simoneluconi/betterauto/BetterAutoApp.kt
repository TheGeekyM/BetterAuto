package com.simoneluconi.betterauto

import android.app.Application
import com.simoneluconi.betterauto.assist.DrivingAssist

/** Starts what every entry point needs before anything logs: the service, the app, the listeners. */
class BetterAutoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        // Follows the car sessions from here: the end of a drive is seen even with no car screen left
        DrivingAssist.init(this)
    }
}
