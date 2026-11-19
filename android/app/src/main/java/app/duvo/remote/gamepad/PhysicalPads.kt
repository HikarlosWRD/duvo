/*
 * Duvo - remote control and streaming for a Windows PC.
 * Copyright (C) 2026 Duvo contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package app.duvo.remote.gamepad

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import app.duvo.remote.stream.NativeBridge
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure mapping of Android gamepad input to Moonlight flags/axes (JVM-testable). */
object PadMapping {
    fun buttonFor(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> Btn.A
        KeyEvent.KEYCODE_BUTTON_B -> Btn.B
        KeyEvent.KEYCODE_BUTTON_X -> Btn.X
        KeyEvent.KEYCODE_BUTTON_Y -> Btn.Y
        KeyEvent.KEYCODE_BUTTON_L1 -> Btn.LB
        KeyEvent.KEYCODE_BUTTON_R1 -> Btn.RB
        KeyEvent.KEYCODE_BUTTON_THUMBL -> Btn.LS
        KeyEvent.KEYCODE_BUTTON_THUMBR -> Btn.RS
        KeyEvent.KEYCODE_BUTTON_START -> Btn.START
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> Btn.BACK
        KeyEvent.KEYCODE_BUTTON_MODE -> Btn.GUIDE
        KeyEvent.KEYCODE_DPAD_UP -> Btn.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Btn.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Btn.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Btn.RIGHT
        else -> 0
    }

    /** Key codes some pads send for digital triggers. */
    fun triggerFor(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_L2 -> -1
        KeyEvent.KEYCODE_BUTTON_R2 -> 1
        else -> 0
    }

    fun stick(v: Float, deadZone: Float = 0.07f): Short {
        if (abs(v) < deadZone) return 0
        return (v.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
    }

    fun trigger(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).roundToInt()

    /** Hat axes (-1/0/1) to d-pad flags. */
    fun hat(x: Float, y: Float): Int =
        (if (x < -0.5f) Btn.LEFT else if (x > 0.5f) Btn.RIGHT else 0) or
            (if (y < -0.5f) Btn.UP else if (y > 0.5f) Btn.DOWN else 0)

    /** Sony pads emulate a DS4 on the PC; everything else an Xbox 360 pad. */
    fun ctypeFor(vendorId: Int): Int = if (vendorId == SONY_VENDOR) NativeBridge.CTYPE_PS else NativeBridge.CTYPE_XBOX

    fun isGamepad(sources: Int): Boolean =
        sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    const val SONY_VENDOR = 0x054C
}

/**
 * Physical controllers attached to the phone (Bluetooth/USB), each forwarded as its own pad slot.
 * Feed it the activity's key and motion events; rumble and motion sensors go back to the device
 * (Android 12+ for per-controller vibrators and sensors; older versions vibrate the phone).
 */
class PhysicalPads(
    private val context: Context,
    private val sender: ControllerSender,
    private val bridge: NativeBridge,
    /** Called when the set of connected controllers changes (the UI offers hiding the virtual pad). */
    private val onChanged: (count: Int) -> Unit = {},
    /** LB + RB + Start held together on any physical controller: open the in-stream menu. */
    private val onMenuChord: () -> Unit = {},
) : InputManager.InputDeviceListener {
    private class Pad(val deviceId: Int, val slot: Int) {
        var buttons = 0
        var hat = 0
        var lt = 0
        var rt = 0
        var lx: Short = 0
        var ly: Short = 0
        var rx: Short = 0
        var ry: Short = 0
        var sensorListener: SensorEventListener? = null
        var chordDown = false
        fun state() = GamepadState(buttons or hat, lt, rt, lx, ly, rx, ry)
    }

    private val pads = HashMap<Int, Pad>()
    private val inputManager = context.getSystemService(InputManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    val count: Int get() = synchronized(pads) { pads.size }

    fun start() {
        inputManager.registerInputDeviceListener(this, main)
        for (id in inputManager.inputDeviceIds) inputManager.getInputDevice(id)?.let { if (eligible(it)) attach(it) }
    }

    fun stop() {
        inputManager.unregisterInputDeviceListener(this)
        synchronized(pads) { pads.values.toList() }.forEach { detach(it.deviceId) }
    }

    /** Returns true when the event came from a controller and was used. */
    fun handle(e: InputEvent): Boolean {
        val dev = e.device ?: return false
        if (!eligible(dev)) return false
        val pad = synchronized(pads) { pads[dev.id] } ?: attach(dev) ?: return false
        when (e) {
            is KeyEvent -> {
                val flag = PadMapping.buttonFor(e.keyCode)
                val trig = PadMapping.triggerFor(e.keyCode)
                if (flag == 0 && trig == 0) return false
                val down = e.action == KeyEvent.ACTION_DOWN
                if (flag != 0) pad.buttons = if (down) pad.buttons or flag else pad.buttons and flag.inv()
                if (trig < 0) pad.lt = if (down) 255 else 0
                if (trig > 0) pad.rt = if (down) 255 else 0
            }
            is MotionEvent -> {
                if (e.action != MotionEvent.ACTION_MOVE) return false
                pad.lx = PadMapping.stick(e.getAxisValue(MotionEvent.AXIS_X))
                pad.ly = PadMapping.stick(-e.getAxisValue(MotionEvent.AXIS_Y))
                pad.rx = PadMapping.stick(e.getAxisValue(MotionEvent.AXIS_Z))
                pad.ry = PadMapping.stick(-e.getAxisValue(MotionEvent.AXIS_RZ))
                val l = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
                val r = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
                pad.lt = PadMapping.trigger(l)
                pad.rt = PadMapping.trigger(r)
                pad.hat = PadMapping.hat(e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y))
            }
            else -> return false
        }
        val chord = Btn.LB or Btn.RB or Btn.START
        val now = (pad.buttons and chord) == chord
        if (now && !pad.chordDown) onMenuChord()
        pad.chordDown = now
        sender.update(pad.slot, pad.state())
        return true
    }

    /** Built-in devices can claim gamepad sources (e.g. a cover "hall_sensor"): only external, real ones count. */
    private fun eligible(dev: InputDevice) = PadMapping.isGamepad(dev.sources) && !dev.isVirtual && dev.isExternal

    private fun attach(dev: InputDevice): Pad? {
        synchronized(pads) {
            pads[dev.id]?.let { return it }
            val slot = sender.firstFreeSlot() ?: return null
            var caps = NativeBridge.CCAP_ANALOG_TRIGGERS or NativeBridge.CCAP_RUMBLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sm = dev.sensorManager
                if (sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) caps = caps or NativeBridge.CCAP_ACCEL
                if (sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) caps = caps or NativeBridge.CCAP_GYRO
            }
            val pad = Pad(dev.id, slot)
            pads[dev.id] = pad
            sender.connect(slot, PadMapping.ctypeFor(dev.vendorId), caps)
            main.post { onChanged(pads.size) }
            return pad
        }
    }

    private fun detach(deviceId: Int) {
        val pad = synchronized(pads) { pads.remove(deviceId) } ?: return
        stopSensors(pad)
        sender.disconnect(pad.slot)
        main.post { onChanged(count) }
    }

    fun rumble(slot: Int, low: Int, high: Int): Boolean {
        val pad = synchronized(pads) { pads.values.firstOrNull { it.slot == slot } } ?: return false
        val dev = inputManager.getInputDevice(pad.deviceId) ?: return false
        val amp = (maxOf(low, high) / 257).coerceIn(0, 255)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = dev.vibratorManager
            if (vm.vibratorIds.isEmpty()) return false
            if (amp == 0) vm.cancel() else vm.vibrate(CombinedVibration.createParallel(VibrationEffect.createOneShot(RUMBLE_MS, amp)))
            return true
        }
        @Suppress("DEPRECATION")
        val v = dev.vibrator
        if (!v.hasVibrator()) return false
        if (amp == 0) v.cancel() else v.vibrate(VibrationEffect.createOneShot(RUMBLE_MS, amp))
        return true
    }

    /** The host asks for accelerometer/gyro reports of the pad in [slot]. */
    fun setMotion(slot: Int, motionType: Int, rateHz: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val pad = synchronized(pads) { pads.values.firstOrNull { it.slot == slot } } ?: return
        val dev = inputManager.getInputDevice(pad.deviceId) ?: return
        val sm = dev.sensorManager
        val type = if (motionType == NativeBridge.MOTION_GYRO) Sensor.TYPE_GYROSCOPE else Sensor.TYPE_ACCELEROMETER
        val sensor = sm.getDefaultSensor(type) ?: return
        if (rateHz == 0) { pad.sensorListener?.let { sm.unregisterListener(it, sensor) }; return }
        val listener = pad.sensorListener ?: object : SensorEventListener {
            override fun onSensorChanged(ev: SensorEvent) {
                val gyro = ev.sensor.type == Sensor.TYPE_GYROSCOPE
                // Moonlight expects accel in m/s^2 and gyro in degrees/s.
                val k = if (gyro) RAD_TO_DEG else 1f
                bridge.sendControllerMotion(
                    pad.slot,
                    if (gyro) NativeBridge.MOTION_GYRO else NativeBridge.MOTION_ACCEL,
                    ev.values[0] * k, ev.values[1] * k, ev.values[2] * k,
                )
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }.also { pad.sensorListener = it }
        sm.registerListener(listener, sensor, 1_000_000 / rateHz.coerceIn(1, 1000))
    }

    private fun stopSensors(pad: Pad) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val l = pad.sensorListener ?: return
        runCatching { inputManager.getInputDevice(pad.deviceId)?.sensorManager?.unregisterListener(l) }
            .onFailure { Log.w("DuvoPad", "sensor unregister", it) }
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        inputManager.getInputDevice(deviceId)?.let { if (eligible(it)) attach(it) }
    }

    override fun onInputDeviceRemoved(deviceId: Int) = detach(deviceId)

    override fun onInputDeviceChanged(deviceId: Int) {}

    companion object {
        private const val RUMBLE_MS = 5_000L // host sends an explicit stop (0/0)
        private const val RAD_TO_DEG = 57.29578f
    }
}
