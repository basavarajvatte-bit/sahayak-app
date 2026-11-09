package com.sahayak.app

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle

/**
 * Lock screen hatane ki khaali screen. Sirf tab kaam karti hai jab phone bina PIN ke khul sakta ho,
 * jaise swipe lock ya Smart Lock (trusted place ya Bluetooth device). PIN wala lock kabhi nahi todti.
 */
class UnlockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val keys = getSystemService(KeyguardManager::class.java)
        if (!keys.isKeyguardLocked || keys.isDeviceLocked) {
            finish()
            return
        }
        keys.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = finish()
            override fun onDismissCancelled() = finish()
            override fun onDismissError() = finish()
        })
    }
}
