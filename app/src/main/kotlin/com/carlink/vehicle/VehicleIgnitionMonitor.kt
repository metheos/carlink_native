package com.carlink.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lightweight AAOS ignition monitor that reports raw state transitions.
 *
 * Uses reflection to avoid direct android.car compile-time dependency.
 */
class VehicleIgnitionMonitor(
    private val context: Context,
    private val onIgnitionStateChanged: (previousState: Int?, currentState: Int) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    private var carInstance: Any? = null
    private var propertyManager: Any? = null
    private var callbackProxy: Any? = null
    private var ignitionPropertyId: Int? = null
    private var latestIgnitionState: Int? = null
    private var pollingJob: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { connectAndSubscribe() }
    }

    fun stop() {
        if (!started.getAndSet(false)) return
        disconnectInternal()
    }

    private fun connectAndSubscribe() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            logInfo("[IGNITION_MONITOR] FEATURE_AUTOMOTIVE not reported; monitor disabled", tag = "VEHICLE")
            return
        }

        if (context.checkSelfPermission(CAR_POWERTRAIN_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            logWarn("[IGNITION_MONITOR] Missing $CAR_POWERTRAIN_PERMISSION; monitor disabled", tag = "VEHICLE")
            return
        }

        val carClass = try {
            Class.forName("android.car.Car")
        } catch (_: ClassNotFoundException) {
            logWarn("[IGNITION_MONITOR] android.car.Car not present on this runtime", tag = "VEHICLE")
            return
        }

        try {
            val car = carClass.getMethod("createCar", Context::class.java).invoke(null, context)
            val wasConnected = invokeBoolean(car, "isConnected") == true
            val wasConnecting = invokeBoolean(car, "isConnecting") == true
            if (!wasConnected && !wasConnecting) {
                runCatching {
                    carClass.getMethod("connect").invoke(car)
                }.onFailure { throwable ->
                    if (!isAlreadyConnectedOrConnectingError(throwable)) {
                        throw throwable
                    }
                }
            }

            waitForConnected(car)
            carInstance = car

            val propertyService = carClass.getField("PROPERTY_SERVICE").get(null) as String
            val manager = carClass.getMethod("getCarManager", String::class.java).invoke(car, propertyService)
                ?: error("Car.PROPERTY_SERVICE returned null")
            propertyManager = manager

            val propertyId = resolveIntConstant("android.car.VehiclePropertyIds", "IGNITION_STATE")
            if (propertyId == null) {
                logWarn("[IGNITION_MONITOR] VehiclePropertyIds.IGNITION_STATE is not available in this SDK/runtime", tag = "VEHICLE")
                disconnectInternal()
                return
            }
            ignitionPropertyId = propertyId

            val callbackInterface = Class.forName(
                "android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback",
            )
            callbackProxy = createCallbackProxy(callbackInterface)

            readInitialValue(manager, propertyId)

            val subscribed = subscribe(manager, callbackInterface, callbackProxy ?: return, propertyId)
            if (!subscribed) {
                logWarn("[IGNITION_MONITOR] Ignition subscription was rejected by CarPropertyManager", tag = "VEHICLE")
                startPollingFallback(manager, propertyId)
            } else {
                logInfo("[IGNITION_MONITOR] Ignition monitor subscribed", tag = "VEHICLE")
            }
        } catch (throwable: Throwable) {
            val root = throwable.rootCause()
            logWarn("[IGNITION_MONITOR] Setup failed: ${root.javaClass.simpleName}: ${root.message}", tag = "VEHICLE")
            disconnectInternal()
        }
    }

    private fun readInitialValue(
        manager: Any,
        propertyId: Int,
    ) {
        try {
            val propertyValue = manager.javaClass
                .getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(manager, propertyId, 0)
            if (propertyValue != null) {
                handleChangeEvent(propertyValue)
            }
        } catch (_: Throwable) {
            // Initial read failure is acceptable; callback updates may still arrive.
        }
    }

    private fun createCallbackProxy(callbackInterface: Class<*>): Any {
        return Proxy.newProxyInstance(
            callbackInterface.classLoader,
            arrayOf(callbackInterface),
        ) { _, method, args ->
            when (method.name) {
                "onChangeEvent" -> {
                    args?.firstOrNull()?.let(::handleChangeEvent)
                    null
                }

                "onErrorEvent" -> null
                else -> null
            }
        }
    }

    private fun handleChangeEvent(propertyValue: Any) {
        if (!started.get()) return

        val expectedPropertyId = ignitionPropertyId ?: return
        val propertyId = invokeInt(propertyValue, "getPropertyId") ?: return
        if (propertyId != expectedPropertyId) return

        val value = invokeMethod(propertyValue, "getValue") as? Int ?: return
        val previous = latestIgnitionState
        if (previous == value) return

        latestIgnitionState = value
        onIgnitionStateChanged(previous, value)
    }

    private fun subscribe(
        manager: Any,
        callbackInterface: Class<*>,
        callback: Any,
        propertyId: Int,
    ): Boolean {
        val managerClass = manager.javaClass
        val subscribeMethod = managerClass.methods.firstOrNull { method ->
            method.name == "subscribePropertyEvents" &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                method.parameterTypes[1] == Float::class.javaPrimitiveType &&
                method.parameterTypes[2] == callbackInterface
        }
        val registerMethod = managerClass.methods.firstOrNull { method ->
            method.name == "registerCallback" &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes[0] == callbackInterface &&
                method.parameterTypes[1] == Int::class.javaPrimitiveType &&
                method.parameterTypes[2] == Float::class.javaPrimitiveType
        }
        val sampleRate = resolveFloatConstant(
            "android.car.hardware.property.CarPropertyManager",
            "SENSOR_RATE_ONCHANGE",
        ) ?: DEFAULT_SAMPLE_RATE

        return try {
            when {
                subscribeMethod != null -> subscribeMethod.invoke(manager, propertyId, sampleRate, callback) as? Boolean ?: false
                registerMethod != null -> registerMethod.invoke(manager, callback, propertyId, sampleRate) as? Boolean ?: false
                else -> false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun disconnectInternal() {
        pollingJob?.cancel()
        pollingJob = null

        val manager = propertyManager
        val callback = callbackProxy

        if (manager != null && callback != null) {
            val callbackInterface = callback.javaClass.interfaces.firstOrNull()
            if (callbackInterface != null) {
                runCatching {
                    manager.javaClass
                        .getMethod("unsubscribePropertyEvents", callbackInterface)
                        .invoke(manager, callback)
                }
                runCatching {
                    manager.javaClass
                        .getMethod("unregisterCallback", callbackInterface)
                        .invoke(manager, callback)
                }
            }
        }

        runCatching {
            carInstance?.javaClass?.getMethod("disconnect")?.invoke(carInstance)
        }

        propertyManager = null
        callbackProxy = null
        carInstance = null
        ignitionPropertyId = null
        latestIgnitionState = null
    }

    private fun waitForConnected(
        car: Any,
        timeoutMs: Long = 2000L,
    ): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startedAt < timeoutMs) {
            if (invokeBoolean(car, "isConnected") == true) return true
            Thread.sleep(50)
        }
        return invokeBoolean(car, "isConnected") == true
    }

    private fun invokeMethod(
        target: Any,
        methodName: String,
    ): Any? {
        return try {
            target.javaClass.getMethod(methodName).invoke(target)
        } catch (_: Throwable) {
            null
        }
    }

    private fun invokeInt(
        target: Any,
        methodName: String,
    ): Int? = invokeMethod(target, methodName) as? Int

    private fun invokeBoolean(
        target: Any,
        methodName: String,
    ): Boolean? = invokeMethod(target, methodName) as? Boolean

    private fun resolveIntConstant(
        className: String,
        fieldName: String,
    ): Int? {
        return try {
            Class.forName(className).getField(fieldName).getInt(null)
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveFloatConstant(
        className: String,
        fieldName: String,
    ): Float? {
        return try {
            Class.forName(className).getField(fieldName).getFloat(null)
        } catch (_: Throwable) {
            null
        }
    }

    private fun isAlreadyConnectedOrConnectingError(throwable: Throwable): Boolean {
        val root = throwable.rootCause()
        if (root !is IllegalStateException) return false
        val message = root.message?.lowercase(Locale.US) ?: return false
        return message.contains("already connected") || message.contains("already connecting")
    }

    private fun Throwable.rootCause(): Throwable {
        val cause = if (this is InvocationTargetException) targetException else this.cause
        return cause?.rootCause() ?: this
    }

    private fun startPollingFallback(
        manager: Any,
        propertyId: Int,
    ) {
        pollingJob?.cancel()
        logInfo("[IGNITION_MONITOR] Subscription failed — starting fallback polling every 1s", tag = "VEHICLE")
        pollingJob =
            scope.launch {
                while (started.get() && isActive) {
                    runCatching {
                        val propertyValue = manager.javaClass
                            .getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                            .invoke(manager, propertyId, 0)
                        if (propertyValue != null) {
                            handleChangeEvent(propertyValue)
                        }
                    }
                    delay(POLLING_INTERVAL_MS)
                }
            }
    }

    private companion object {
        private const val DEFAULT_SAMPLE_RATE = 1.0f
        private const val POLLING_INTERVAL_MS = 1000L
        private const val CAR_POWERTRAIN_PERMISSION = "android.car.permission.CAR_POWERTRAIN"
    }
}


