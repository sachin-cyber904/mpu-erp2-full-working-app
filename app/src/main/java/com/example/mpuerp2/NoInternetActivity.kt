package com.example.mpuerp2

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class NoInternetActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_no_internet)

        // ✅ RETRY BUTTON
        findViewById<Button>(R.id.retryButton).setOnClickListener {
            if (isInternetAvailable()) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                // shake the button to show still no internet
                val shake = android.view.animation.AnimationUtils.loadAnimation(
                    this, android.R.anim.cycle_interpolator
                )
                findViewById<Button>(R.id.retryButton).startAnimation(shake)
            }
        }
    }

    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}