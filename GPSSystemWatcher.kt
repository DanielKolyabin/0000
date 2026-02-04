package ru.relabs.kurjer.presentation.host.systemWatchers

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import ru.relabs.kurjer.presentation.host.featureCheckers.GPSFeatureChecker

class GPSSystemWatcher(
    a: Activity,
    private val gpsFeatureChecker: GPSFeatureChecker
) : SystemWatcher(a, null) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!gpsFeatureChecker.isFeatureEnabled()) {
                gpsFeatureChecker.requestFeature()
            }
        }
    }

    override fun onResume() {
        activity?.registerReceiver(receiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
    }

    override fun onPause() {
        activity?.unregisterReceiver(receiver)
    }
}