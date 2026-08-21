package com.example.erp
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.ScaleAnimation
import android.view.animation.AnimationSet
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logo = findViewById<ImageView>(R.id.logoImage)
        val dot1 = findViewById<View>(R.id.dot1)
        val dot2 = findViewById<View>(R.id.dot2)
        val dot3 = findViewById<View>(R.id.dot3)

        // ✅ LOGO ANIMATION — fade in + scale up
        val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 900 }
        val scaleUp = ScaleAnimation(
            0.6f, 1f, 0.6f, 1f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply { duration = 900 }

        val logoAnim = AnimationSet(true).apply {
            addAnimation(fadeIn)
            addAnimation(scaleUp)
        }
        logo.startAnimation(logoAnim)

        // ✅ DOTS ANIMATION — staggered fade
        val dot1Anim = AlphaAnimation(0.2f, 1.0f).apply {
            duration = 600
            repeatMode = AlphaAnimation.REVERSE
            repeatCount = AlphaAnimation.INFINITE
            startOffset = 0
        }
        val dot2Anim = AlphaAnimation(0.2f, 1.0f).apply {
            duration = 600
            repeatMode = AlphaAnimation.REVERSE
            repeatCount = AlphaAnimation.INFINITE
            startOffset = 200
        }
        val dot3Anim = AlphaAnimation(0.2f, 1.0f).apply {
            duration = 600
            repeatMode = AlphaAnimation.REVERSE
            repeatCount = AlphaAnimation.INFINITE
            startOffset = 400
        }

        dot1.startAnimation(dot1Anim)
        dot2.startAnimation(dot2Anim)
        dot3.startAnimation(dot3Anim)

        // ✅ NAVIGATE TO MAIN
        handler.postDelayed({
            dot1.clearAnimation()
            dot2.clearAnimation()
            dot3.clearAnimation()
            logo.clearAnimation()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}