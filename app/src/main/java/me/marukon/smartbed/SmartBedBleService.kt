package me.marukon.smartbed

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SmartBedBleService : Service() {

  companion object {
    const val ACTION_CONNECT = "me.marukon.smartbed.action.CONNECT"
    const val ACTION_DISCONNECT = "me.marukon.smartbed.action.DISCONNECT"
    const val ACTION_TOGGLE_ONCE = "me.marukon.smartbed.action.TOGGLE_ONCE"
    const val ACTION_START_KEEP = "me.marukon.smartbed.action.START_KEEP"
    const val ACTION_STOP_KEEP = "me.marukon.smartbed.action.STOP_KEEP"

    const val ACTION_EVENT = "me.marukon.smartbed.action.EVENT"
    const val ACTION_STATE = "me.marukon.smartbed.action.STATE"
    const val EXTRA_EVENT = "event"
    const val EXTRA_STATUS = "status"
    const val EXTRA_KEEP_RUNNING = "keep_running"
    const val EXTRA_NEXT_SEND_AT_ELAPSED = "next_send_at_elapsed"
    const val EXTRA_BED_LIGHT_ON = "bed_light_on"

    private const val CHANNEL_ID = "smartbed_ble"
    private const val NOTIFY_ID = 1001

    private const val TARGET_MAC = "CC:D4:C4:85:78:AF"

    private const val KEEP_INTERVAL_MS = 301_000L // 完整周期 301 秒（确保覆盖硬件的300秒自动关机）
    private const val RECONNECT_WINDOW_MS = 15_000L // 提前 15 秒重连窗口，仅预热不发指令
    private const val IDLE_DISCONNECT_MS = 5_000L   // 成功发送后空闲 5 秒自动断开
    private const val MIN_CONNECT_ATTEMPT_GAP_MS = 5_000L // 重连防抖降低到5秒

    private val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
  }

  // ===== 指令动作枚举 =====
  enum class PendingAction {
    NONE,
    TOGGLE_MANUAL,
    KEEP_START_OR_AUTO,
    KEEP_STOP
  }

  private val mainHandler = Handler(Looper.getMainLooper())

  private var gatt: BluetoothGatt? = null
  private var rxChar: BluetoothGattCharacteristic? = null
  private var txChar: BluetoothGattCharacteristic? = null

  private var keepRunning = false
  private var pendingAction = PendingAction.NONE
  private var connectInProgress = false
  private var lastConnectAttemptMs = 0L
  private var isForegroundMode = false
  private var nextKeepSendAtElapsed = 0L
  private var bedLightOn = false

  // ===== 唤醒锁模块 =====
  private var wakeLock: PowerManager.WakeLock? = null

  @SuppressLint("WakelockTimeout")
  private fun acquireWakeLock(timeoutMs: Long) {
    if (wakeLock == null) {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartBed::KeepAliveWakelock")
      wakeLock?.setReferenceCounted(false)
    }
    if (wakeLock?.isHeld == false) {
      wakeLock?.acquire(timeoutMs)
      postEvent("🔋已获取 CPU 唤醒锁")
    }
  }

  private fun releaseWakeLock() {
    if (wakeLock?.isHeld == true) {
      wakeLock?.release()
      postEvent("🔋已释放 CPU 唤醒锁")
    }
  }

  private fun isReady(): Boolean = gatt != null && rxChar != null

  // ===== 核心定时任务 =====

  // 任务1：空闲自动释放蓝牙连接
  private val idleDisconnectTask = Runnable {
    if (gatt != null) {
      postEvent("🕒指令发送完毕且空闲已达5秒，自动断开连接以释放设备")
      internalDisconnect() // 只断开蓝牙，不清理保活业务状态

      if (!keepRunning) releaseWakeLock()
    }
  }

  private fun resetIdleTimer() {
    mainHandler.removeCallbacks(idleDisconnectTask)
    mainHandler.postDelayed(idleDisconnectTask, IDLE_DISCONNECT_MS)
  }

  // 任务2：核心倒计时与控制任务 (修复逻辑版)
  private val countdownTask = object : Runnable {
    override fun run() {
      val now = SystemClock.elapsedRealtime()

      if (keepRunning && nextKeepSendAtElapsed > 0) {
        val timeRemain = nextKeepSendAtElapsed - now

        if (timeRemain <= 0) {
          // 情况1：倒计时真正结束，必须立刻发送开灯指令！
          // 确保当前没有其它正在排队的动作，避免重复触发
          if (pendingAction == PendingAction.NONE) {
            postEvent("⏱️ 周期倒计时结束，准点发送保活开灯指令")
            dispatchAction(PendingAction.KEEP_START_OR_AUTO)
          }
        } else if (timeRemain <= RECONNECT_WINDOW_MS) {
          // 情况2：进入提前重连窗口（如最后15秒）
          // 仅提前建立物理连接预热通道，绝不提前发送指令，防止打乱硬件倒计时
          if (pendingAction == PendingAction.NONE && !isReady() && !connectInProgress) {
            postEvent("⏳ 提前唤醒蓝牙预热通道，准备迎接下发指令...")
            requestConnectIfNeeded("pre_connect_keep_alive")
          }
        }
      }

      broadcastState()

      // 只要服务需要保活或状态需要维护，就维持心跳循环
      if (keepRunning || bedLightOn || nextKeepSendAtElapsed > now || pendingAction != PendingAction.NONE) {
        mainHandler.postDelayed(this, 1_000L)
      }
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createChannelIfNeeded()
    postEvent("蓝牙服务已创建")
    broadcastState()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_CONNECT -> {
        requestConnectIfNeeded("ACTION_CONNECT")
      }
      ACTION_DISCONNECT -> {
        fullCleanupDisconnect()
      }
      ACTION_TOGGLE_ONCE -> {
        if (!keepRunning) acquireWakeLock(15_000L)
        dispatchAction(PendingAction.TOGGLE_MANUAL)
      }
      ACTION_START_KEEP -> {
        keepRunning = true
        acquireWakeLock(12 * 60 * 60 * 1000L)
        enterForegroundMode("持续开灯运行中（空闲自动断开）")

        // 确保启动引擎前清理旧的任务
        mainHandler.removeCallbacks(countdownTask)

        // 触发首次开灯动作
        dispatchAction(PendingAction.KEEP_START_OR_AUTO)

        // 启动主引擎心跳
        mainHandler.post(countdownTask)
      }
      ACTION_STOP_KEEP -> {
        keepRunning = false
        exitForegroundMode()
        dispatchAction(PendingAction.KEEP_STOP)
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    fullCleanupDisconnect()
    postEvent("蓝牙服务已销毁")
    super.onDestroy()
  }

  // ===== 核心指令分发引擎 =====

  private fun dispatchAction(action: PendingAction) {
    if (isReady()) {
      executeAction(action)
    } else {
      postEvent("检测到未连接，动作[${action.name}]已被缓存，准备发起连接")
      pendingAction = action
      requestConnectIfNeeded("dispatchAction_${action.name}")
    }
  }

  private fun executeAction(action: PendingAction) {
    postEvent("正在执行动作: ${action.name}")
    val ok = writeHex("05020002000000")

    if (!ok) {
      postEvent("⚠️ 指令发送失败，断开连接并稍后重试")
      pendingAction = action
      internalDisconnect()
      mainHandler.postDelayed({ requestConnectIfNeeded("retry_write_fail") }, MIN_CONNECT_ATTEMPT_GAP_MS)
      return
    }

    when (action) {
      PendingAction.TOGGLE_MANUAL -> {
        bedLightOn = !bedLightOn
        if (!keepRunning) {
          nextKeepSendAtElapsed = if (bedLightOn) SystemClock.elapsedRealtime() + KEEP_INTERVAL_MS else 0L
        }
        resetIdleTimer()
      }
      PendingAction.KEEP_START_OR_AUTO -> {
        // 核心修复：只有当指令真正物理发送成功后，才重置倒计时！
        bedLightOn = true
        nextKeepSendAtElapsed = SystemClock.elapsedRealtime() + KEEP_INTERVAL_MS
        resetIdleTimer() // 启动 5 秒自动断开
      }
      PendingAction.KEEP_STOP -> {
        bedLightOn = false
        mainHandler.postDelayed({ fullCleanupDisconnect() }, 1000L)
        return
      }
      PendingAction.NONE -> {}
    }

    broadcastState()
  }

  // ===== 蓝牙连接/断开封装 =====

  @SuppressLint("MissingPermission")
  private fun internalDisconnect() {
    connectInProgress = false
    gatt?.disconnect()
    gatt?.close()
    gatt = null
    rxChar = null
    txChar = null
    broadcastState()
  }

  private fun fullCleanupDisconnect() {
    keepRunning = false
    pendingAction = PendingAction.NONE
    nextKeepSendAtElapsed = 0L
    bedLightOn = false
    mainHandler.removeCallbacks(idleDisconnectTask)
    mainHandler.removeCallbacks(countdownTask)
    internalDisconnect()
    releaseWakeLock()
    stopSelfSafely("manual/full disconnect")
  }

  @SuppressLint("MissingPermission")
  private fun requestConnectIfNeeded(source: String) {
    if (isReady() || connectInProgress) return

    val now = SystemClock.elapsedRealtime()
    if (now - lastConnectAttemptMs < MIN_CONNECT_ATTEMPT_GAP_MS) return

    connectInProgress = true
    lastConnectAttemptMs = now
    broadcastState()

    if (!hasBtPermission()) {
      postEvent("缺少蓝牙权限: BLUETOOTH_CONNECT")
      connectInProgress = false
      return
    }

    val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter: BluetoothAdapter = manager.adapter ?: run {
      postEvent("蓝牙不可用")
      connectInProgress = false
      return
    }
    if (!adapter.isEnabled) {
      postEvent("请先打开蓝牙")
      connectInProgress = false
      return
    }

    val device: BluetoothDevice = try {
      adapter.getRemoteDevice(TARGET_MAC)
    } catch (e: IllegalArgumentException) {
      postEvent("MAC格式错误: ${e.message}")
      connectInProgress = false
      return
    }

    postEvent("发起物理连接 -> $TARGET_MAC (触发源=$source)")
    updateNotification("连接中...")

    gatt?.close()
    gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
  }

  // ===== GATT 回调 =====

  private val gattCallback = object : BluetoothGattCallback() {
    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        postEvent("BLE状态: 已连接，开始寻找服务")
        connectInProgress = false
        broadcastState()
        updateNotification("已连接，发现服务中...")
        gatt.discoverServices()

      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        postEvent("BLE状态: 已断开")
        internalDisconnect()
        updateNotification("等待下次唤醒")

        if (pendingAction != PendingAction.NONE) {
          postEvent("提示：当前仍有动作尚未执行，将在5秒后重试...")
          mainHandler.postDelayed({ requestConnectIfNeeded("retry_pending") }, MIN_CONNECT_ATTEMPT_GAP_MS)
        } else if (!keepRunning) {
          stopSelfSafely("disconnected idle")
        }
      }
    }

    @SuppressLint("MissingPermission")
    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
      val service: BluetoothGattService? = gatt.getService(NUS_SERVICE_UUID)
      if (service == null) {
        postEvent("未找到目标服务")
        internalDisconnect()
        return
      }

      rxChar = service.getCharacteristic(RX_UUID)
      txChar = service.getCharacteristic(TX_UUID)

      txChar?.let { tx ->
        gatt.setCharacteristicNotification(tx, true)
        val cccd: BluetoothGattDescriptor? = tx.getDescriptor(CCCD_UUID)
        if (cccd != null) {
          cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
          gatt.writeDescriptor(cccd)
        }
      }

      postEvent("服务已就绪，准备缓冲 500ms 后执行逻辑")
      updateNotification("连接就绪")
      connectInProgress = false

      mainHandler.postDelayed({
        if (pendingAction != PendingAction.NONE) {
          // 有排队任务，立刻执行（此时才会重置5分钟倒计时并启动5秒自动断开）
          val actionToRun = pendingAction
          pendingAction = PendingAction.NONE
          executeAction(actionToRun)
        } else {
          // 修复点：这属于"提前预热连接"，不要在这里调用 resetIdleTimer() 导致被提前意外断开！
          // 让引擎维持该连接，直到 countdownTask 里的时间归零主动触发执行发送。
          postEvent("✅ 提前重连就绪，等待准点发射信号...")
          broadcastState()
        }
      }, 500L)
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
      val data = characteristic.value ?: return
      postEvent("RX => ${bytesToHex(data)}")
    }
  }

  // ===== 工具函数 =====

  @SuppressLint("MissingPermission")
  private fun writeHex(hex: String): Boolean {
    val g = gatt
    val rx = rxChar
    if (g == null || rx == null) {
      postEvent("发送失败：未找到有效连接通道")
      return false
    }

    val data = hexToBytes(hex)
    rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    rx.value = data
    val ok = g.writeCharacteristic(rx)
    postEvent("TX <= $hex  (底层接受状态=$ok)")
    return ok
  }

  private fun hasBtPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else true
  }

  private fun createChannelIfNeeded() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val nm = getSystemService(NotificationManager::class.java)
      val channel = NotificationChannel(CHANNEL_ID, "SmartBed BLE", NotificationManager.IMPORTANCE_LOW)
      nm.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(text: String): Notification {
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("SmartBed 持续开灯")
      .setContentText(text)
      .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
      .setOngoing(true)
      .build()
  }

  private fun enterForegroundMode(text: String) {
    val notification = buildNotification(text)
    if (!isForegroundMode) {
      startForeground(NOTIFY_ID, notification)
      isForegroundMode = true
    } else {
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      nm.notify(NOTIFY_ID, notification)
    }
  }

  private fun exitForegroundMode() {
    if (!isForegroundMode) return
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    isForegroundMode = false
  }

  private fun updateNotification(text: String) {
    if (!isForegroundMode) return
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(NOTIFY_ID, buildNotification(text))
  }

  private fun postEvent(msg: String) {
    val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    val line = "[$ts] $msg"
    val i = Intent(ACTION_EVENT)
    i.setPackage(packageName)
    i.putExtra(EXTRA_EVENT, line)
    sendBroadcast(i)
  }

  private fun broadcastState() {
    val status = when {
      isReady() -> "已连接"
      connectInProgress -> "连接中..."
      else -> "未连接"
    }
    val i = Intent(ACTION_STATE)
    i.setPackage(packageName)
    i.putExtra(EXTRA_STATUS, status)
    i.putExtra(EXTRA_KEEP_RUNNING, keepRunning)
    i.putExtra(EXTRA_NEXT_SEND_AT_ELAPSED, nextKeepSendAtElapsed)
    i.putExtra(EXTRA_BED_LIGHT_ON, bedLightOn)
    sendBroadcast(i)
  }

  private fun stopSelfSafely(reason: String) {
    if (isForegroundMode) return
    postEvent("服务清理，停止后台 ($reason)")
    stopSelf()
  }

  private fun hexToBytes(hex: String): ByteArray {
    val clean = hex.replace(" ", "").lowercase(Locale.getDefault())
    require(clean.length % 2 == 0) { "Hex长度必须为偶数" }
    return ByteArray(clean.length / 2) { i ->
      clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
  }

  private fun bytesToHex(bytes: ByteArray): String {
    return bytes.joinToString(separator = "") { b -> "%02x".format(b) }
  }
}