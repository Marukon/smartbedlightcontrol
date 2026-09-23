package me.marukon.smartbed

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

  private lateinit var tvStatus: TextView
  private lateinit var tvCountdown: TextView
  private lateinit var btnToggleLight: Button
  private lateinit var btnKeepLightOn: Button

  private var keepRunning = false
  private var bedLightOn = false

  private val eventReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      when (intent?.action) {
        SmartBedBleService.ACTION_STATE -> {
          val status = intent.getStringExtra(SmartBedBleService.EXTRA_STATUS) ?: "未连接"
          val keepOn = intent.getBooleanExtra(SmartBedBleService.EXTRA_KEEP_RUNNING, false)
          val nextAtElapsed = intent.getLongExtra(SmartBedBleService.EXTRA_NEXT_SEND_AT_ELAPSED, 0L)
          val lightOn = intent.getBooleanExtra(SmartBedBleService.EXTRA_BED_LIGHT_ON, false)

          keepRunning = keepOn
          bedLightOn = lightOn

          tvStatus.text = if (status == "已连接") "状态: ✅已连接" else "状态: $status"

          btnKeepLightOn.text = if (keepRunning) {
            "正在处于持续开灯状态（点击关灯）"
          } else {
            "持续打开床底灯"
          }

          btnToggleLight.text = if (bedLightOn) "关闭床底灯" else "打开床底灯（一次）"

          // 核心修改逻辑：控制按钮的显示与隐藏
          // 1. 当处于持续开灯状态时，隐藏单次开灯按钮
          if (keepRunning) {
            btnToggleLight.visibility = View.GONE
          } else {
            btnToggleLight.visibility = View.VISIBLE
          }

          // 2. 当单次开灯处于打开状态时（非持续开灯模式且灯亮着），隐藏持续开灯按钮
          if (!keepRunning && bedLightOn) {
            btnKeepLightOn.visibility = View.GONE
          } else {
            btnKeepLightOn.visibility = View.VISIBLE
          }

          tvCountdown.text = if (nextAtElapsed > 0L) {
            val remainMs = (nextAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            "本轮开灯时间还剩下 ${formatRemain(remainMs)}"
          } else {
            "本轮开灯时间还剩下 --:--"
          }
        }
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    supportActionBar?.title = "布布床灯控制"

    tvStatus = findViewById(R.id.tvStatus)
    tvCountdown = findViewById(R.id.tvCountdown)
    btnToggleLight = findViewById(R.id.btnToggleLight0502)
    btnKeepLightOn = findViewById(R.id.btnKeepLightOn)

    ensurePermissions()
    requestIgnoreBatteryOptimizationIfNeeded()

    val f = IntentFilter(SmartBedBleService.ACTION_STATE)
    ContextCompat.registerReceiver(
      this,
      eventReceiver,
      f,
      ContextCompat.RECEIVER_NOT_EXPORTED
    )

    findViewById<Button>(R.id.btnConnect).setOnClickListener {
      sendServiceAction(SmartBedBleService.ACTION_CONNECT, foreground = false)
      tvStatus.text = "状态: 连接中..."
    }

    findViewById<Button>(R.id.btnDisconnect).setOnClickListener {
      sendServiceAction(SmartBedBleService.ACTION_DISCONNECT, foreground = false)
      tvStatus.text = "状态: 断开中..."
    }

    btnToggleLight.setOnClickListener {
      sendServiceAction(SmartBedBleService.ACTION_TOGGLE_ONCE, foreground = false)
    }

    btnKeepLightOn.setOnClickListener {
      if (keepRunning) {
        sendServiceAction(SmartBedBleService.ACTION_STOP_KEEP, foreground = false)
      } else {
        sendServiceAction(SmartBedBleService.ACTION_START_KEEP, foreground = true)
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    unregisterReceiver(eventReceiver)
  }

  private fun requestIgnoreBatteryOptimizationIfNeeded() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    if (pm.isIgnoringBatteryOptimizations(packageName)) return
    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
      data = Uri.parse("package:$packageName")
    }
    startActivity(i)
  }

  private fun sendServiceAction(action: String, foreground: Boolean) {
    val i = Intent(this, SmartBedBleService::class.java).apply { this.action = action }
    if (foreground) {
      ContextCompat.startForegroundService(this, i)
    } else {
      startService(i)
    }
  }

  private fun requiredPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS
      )
    } else {
      arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
  }

  private fun ensurePermissions() {
    val need = requiredPermissions().filter {
      ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }
    if (need.isNotEmpty()) {
      ActivityCompat.requestPermissions(this, need.toTypedArray(), 1001)
    }
  }

  private fun formatRemain(ms: Long): String {
    val totalSec = (ms / 1000L).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
  }
}