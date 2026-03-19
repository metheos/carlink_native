package com.carlink.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VehiclePropertyDebugState(
    val summary: String = "Checking AAOS vehicle property APIs…",
    val notes: List<String> = emptyList(),
    val signalLines: List<String> = emptyList(),
    val previousSignalLines: List<String> = emptyList(),
    val ignitionStateRawValue: Int? = null,
    val previousIgnitionStateRawValue: Int? = null,
)

@Composable
fun rememberVehiclePropertyDebugState(): VehiclePropertyDebugState {
    val context = LocalContext.current.applicationContext
    val monitor = remember(context) { VehiclePropertyDebugMonitor(context) }
    val state by monitor.state.collectAsStateWithLifecycle()

    DisposableEffect(monitor) {
        monitor.start()
        onDispose { monitor.stop() }
    }

    return state
}

private class VehiclePropertyDebugMonitor(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val stateFlow = MutableStateFlow(VehiclePropertyDebugState())
    private val lock = Any()
    private val notes = linkedSetOf<String>()
    private val signalLines = LinkedHashMap<String, String>()
    private val previousSignalLines = LinkedHashMap<String, String>()
    private val trackedById = LinkedHashMap<Int, TrackedProperty>()

    private var summary: String = stateFlow.value.summary
    private var carInstance: Any? = null
    private var propertyManager: Any? = null
    private var callbackProxy: Any? = null
    private var successfulSubscriptions: Int = 0
    private var ignitionStateRawValue: Int? = null
    private var previousIgnitionStateRawValue: Int? = null

    val state = stateFlow.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return

        scope.launch {
            connectAndSubscribe()
        }
    }

    fun stop() {
        if (!started.getAndSet(false)) return
        disconnectInternal()
    }

    private fun connectAndSubscribe() {
        resetState("Checking AAOS vehicle property APIs…")
        addNote("AAOS exposes vehicle state through CarPropertyManager and VehiclePropertyIds.")
        addNote("Signal availability is OEM-specific and many properties are permission-gated on production AAOS builds.")

        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            updateSummary("FEATURE_AUTOMOTIVE is not reported on this device.")
            addNote("The debug listener is only started when the device reports the automotive system feature.")
            publishState()
            return
        }

        val carClass = try {
            Class.forName("android.car.Car")
        } catch (_: ClassNotFoundException) {
            updateSummary("android.car APIs are not present on this runtime.")
            publishState()
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

            if (!waitForConnected(car)) {
                addNote("Car service did not report connected state within timeout; continuing best-effort.")
            }

            carInstance = car

            val propertyService = carClass.getField("PROPERTY_SERVICE").get(null) as String
            val manager = carClass.getMethod("getCarManager", String::class.java).invoke(car, propertyService)
                ?: error("Car.PROPERTY_SERVICE returned null")
            propertyManager = manager

            val callbackInterface = Class.forName(
                "android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback",
            )
            callbackProxy = createCallbackProxy(callbackInterface)

            successfulSubscriptions = 0
            trackedById.clear()

            trackedProperties.forEach { tracked ->
                registerTrackedProperty(manager, callbackInterface, tracked)
            }

            val subscribedCount = successfulSubscriptions
            updateSummary(
                if (subscribedCount > 0) {
                    "Connected to CarPropertyManager. Subscribed to $subscribedCount/${trackedProperties.size} tracked signals."
                } else {
                    "Connected to CarPropertyManager, but none of the tracked signals were subscribable."
                },
            )
            publishState()
        } catch (throwable: Throwable) {
            val rootCause = throwable.rootCause()
            updateSummary("AAOS vehicle property hookup failed: ${rootCause.javaClass.simpleName}: ${rootCause.message ?: "Unknown error"}")
            publishState()
            disconnectInternal()
        }
    }

    private fun registerTrackedProperty(
        manager: Any,
        callbackInterface: Class<*>,
        tracked: TrackedProperty,
    ) {
        val propertyId = resolveIntConstant("android.car.VehiclePropertyIds", tracked.fieldName)
        if (propertyId == null) {
            addNote("${tracked.label}: ${tracked.fieldName} is not defined in this SDK.")
            publishState()
            return
        }

        trackedById[propertyId] = tracked

        val permission = tracked.permission
        if (permission != null && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            setSignalLine(tracked, 0, "permission not granted ($permission)")
            publishState()
            return
        }

        val config = try {
            manager.javaClass.getMethod("getCarPropertyConfig", Int::class.javaPrimitiveType).invoke(manager, propertyId)
        } catch (throwable: Throwable) {
            setSignalLine(tracked, 0, describeFailure("config lookup failed", throwable))
            publishState()
            return
        }

        if (config == null) {
            setSignalLine(tracked, 0, "not exposed by this vehicle / HAL")
            publishState()
            return
        }

        val areaIds = readAreaIds(config).takeIf { it.isNotEmpty() } ?: intArrayOf(0)
        areaIds.take(MAX_INITIAL_AREAS).forEach { areaId ->
            readInitialValue(manager, tracked, propertyId, areaId)
        }

        val subscribed = subscribe(manager, callbackInterface, callbackProxy ?: return, propertyId, tracked.rateField)
        if (subscribed) {
            successfulSubscriptions += 1
        } else {
            setSignalLine(tracked, 0, "subscription rejected by CarPropertyManager")
        }
        publishState()
    }

    private fun readInitialValue(
        manager: Any,
        tracked: TrackedProperty,
        propertyId: Int,
        areaId: Int,
    ) {
        try {
            val propertyValue = manager.javaClass
                .getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(manager, propertyId, areaId)
            if (propertyValue != null) {
                handleChangeEvent(propertyValue)
            } else {
                setSignalLine(tracked, areaId, "no initial value returned")
            }
        } catch (throwable: Throwable) {
            setSignalLine(tracked, areaId, describeFailure("initial read failed", throwable))
        }
    }

    private fun createCallbackProxy(callbackInterface: Class<*>): Any {
        return Proxy.newProxyInstance(
            callbackInterface.classLoader,
            arrayOf(callbackInterface),
        ) { proxy, method, args ->
            when (method.name) {
                "onChangeEvent" -> {
                    args?.firstOrNull()?.let(::handleChangeEvent)
                    null
                }

                "onErrorEvent" -> {
                    val propertyId = (args?.getOrNull(0) as? Int) ?: UNKNOWN_PROPERTY_ID
                    val areaId = (args?.getOrNull(1) as? Int) ?: 0
                    val tracked = trackedById[propertyId]
                    if (tracked != null) {
                        val errorCode = args?.getOrNull(2) as? Int
                        val errorText = if (errorCode == null) "property callback error" else "property callback error (code=$errorCode)"
                        setSignalLine(tracked, areaId, errorText)
                        publishState()
                    }
                    null
                }

                "toString" -> "VehiclePropertyDebugCallback"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
    }

    private fun handleChangeEvent(propertyValue: Any) {
        if (!started.get()) return

        val propertyId = invokeInt(propertyValue, "getPropertyId") ?: return
        val tracked = trackedById[propertyId] ?: return
        val areaId = invokeInt(propertyValue, "getAreaId") ?: 0
        val status = invokeInt(propertyValue, "getStatus") ?: invokeInt(propertyValue, "getPropertyStatus") ?: -1
        val timestampNanos = invokeLong(propertyValue, "getTimestamp") ?: 0L
        val value = invokeMethod(propertyValue, "getValue")

        val formattedValue = tracked.formatValue(value)
        val statusText = decodePropertyStatus(status)
        val ageMs = if (timestampNanos > 0L) {
            ((SystemClock.elapsedRealtimeNanos() - timestampNanos).coerceAtLeast(0L) / 1_000_000L)
        } else {
            null
        }

        val description = buildString {
            append(formattedValue)
            append(" (")
            append(statusText)
            if (ageMs != null) {
                append(", age=")
                append(ageMs)
                append("ms")
            }
            append(")")
        }

        if (tracked.isIgnitionState()) {
            val rawIgnition = value as? Int
            if (rawIgnition != null && rawIgnition != ignitionStateRawValue) {
                previousIgnitionStateRawValue = ignitionStateRawValue
                ignitionStateRawValue = rawIgnition
            }
        }

        setSignalLine(tracked, areaId, description)
        publishState()
    }

    private fun subscribe(
        manager: Any,
        callbackInterface: Class<*>,
        callback: Any,
        propertyId: Int,
        rateField: String,
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
            rateField,
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
    }

    private fun setSignalLine(
        tracked: TrackedProperty,
        areaId: Int,
        valueText: String,
    ) {
        synchronized(lock) {
            val key = tracked.key(areaId)
            val newLine = tracked.render(areaId, valueText)
            val currentLine = signalLines[key]
            if (currentLine != null && currentLine != newLine) {
                previousSignalLines[key] = currentLine
            }
            signalLines[key] = newLine
        }
    }

    private fun addNote(note: String) {
        synchronized(lock) {
            notes += note
        }
    }

    private fun updateSummary(newSummary: String) {
        synchronized(lock) {
            summary = newSummary
        }
    }

    private fun resetState(newSummary: String) {
        synchronized(lock) {
            summary = newSummary
            notes.clear()
            signalLines.clear()
            previousSignalLines.clear()
            trackedById.clear()
            successfulSubscriptions = 0
            ignitionStateRawValue = null
            previousIgnitionStateRawValue = null
        }
        publishState()
    }

    private fun publishState() {
        val snapshot = synchronized(lock) {
            VehiclePropertyDebugState(
                summary = summary,
                notes = notes.toList(),
                signalLines = signalLines.values.toList(),
                previousSignalLines = previousSignalLines.values.toList(),
                ignitionStateRawValue = ignitionStateRawValue,
                previousIgnitionStateRawValue = previousIgnitionStateRawValue,
            )
        }
        stateFlow.value = snapshot
    }

    private fun readAreaIds(config: Any): IntArray {
        return try {
            @Suppress("UNCHECKED_CAST")
            (config.javaClass.getMethod("getAreaIds").invoke(config) as? IntArray) ?: intArrayOf()
        } catch (_: Throwable) {
            intArrayOf()
        }
    }

    private fun decodePropertyStatus(status: Int): String {
        val available = resolveIntConstant("android.car.hardware.CarPropertyValue", "STATUS_AVAILABLE")
        val unavailable = resolveIntConstant("android.car.hardware.CarPropertyValue", "STATUS_UNAVAILABLE")
        val error = resolveIntConstant("android.car.hardware.CarPropertyValue", "STATUS_ERROR")
        return when (status) {
            available -> "available"
            unavailable -> "unavailable"
            error -> "error"
            else -> "status=$status"
        }
    }

    private fun describeFailure(prefix: String, throwable: Throwable): String {
        val rootCause = throwable.rootCause()
        val message = rootCause.message?.takeIf { it.isNotBlank() } ?: rootCause.javaClass.simpleName
        return "$prefix: ${rootCause.javaClass.simpleName}: $message"
    }

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

    private fun invokeLong(
        target: Any,
        methodName: String,
    ): Long? = invokeMethod(target, methodName) as? Long

    private fun invokeBoolean(
        target: Any,
        methodName: String,
    ): Boolean? = invokeMethod(target, methodName) as? Boolean

    private fun waitForConnected(
        car: Any,
        timeoutMs: Long = 2000L,
    ): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startedAt < timeoutMs) {
            val connected = invokeBoolean(car, "isConnected") == true
            if (connected) return true
            Thread.sleep(50)
        }
        return invokeBoolean(car, "isConnected") == true
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

    private data class TrackedProperty(
        val label: String,
        val fieldName: String,
        val permission: String? = null,
        val rateField: String = "SENSOR_RATE_ONCHANGE",
    ) {
        fun isIgnitionState(): Boolean = fieldName == "IGNITION_STATE"

        fun key(areaId: Int): String = "$label#$areaId"

        fun render(
            areaId: Int,
            valueText: String,
        ): String {
            val areaSuffix = if (areaId == 0) "" else " [area=0x${areaId.toUInt().toString(16).padStart(8, '0')}]"
            return "$label$areaSuffix: $valueText"
        }

        fun formatValue(value: Any?): String {
            return when (fieldName) {
                "GEAR_SELECTION" -> (value as? Int)?.let { decodeStaticValue("android.car.VehicleGear", it) } ?: stringify(value)
                "IGNITION_STATE" -> (value as? Int)?.let { decodeStaticValue("android.car.VehicleIgnitionState", it) } ?: stringify(value)
                "ENV_OUTSIDE_TEMPERATURE",
                -> (value as? Number)?.toDouble()?.let { tempC ->
                    String.format(Locale.US, "%.1f °C", tempC)
                } ?: stringify(value)
                else -> stringify(value)
            }
        }

        private fun decodeStaticValue(
            className: String,
            value: Int,
        ): String {
            return try {
                Class.forName(className)
                    .getMethod("toString", Int::class.javaPrimitiveType)
                    .invoke(null, value) as? String ?: value.toString()
            } catch (_: Throwable) {
                value.toString()
            }
        }

        private fun stringify(value: Any?): String {
            return when (value) {
                null -> "null"
                is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
                is IntArray -> value.joinToString(prefix = "[", postfix = "]")
                is LongArray -> value.joinToString(prefix = "[", postfix = "]")
                is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
                is Array<*> -> value.joinToString(prefix = "[", postfix = "]")
                else -> value.toString()
            }
        }
    }

    private companion object {
        private const val DEFAULT_SAMPLE_RATE = 1.0f
        private const val MAX_INITIAL_AREAS = 4
        private const val UNKNOWN_PROPERTY_ID = -1

        private val trackedProperties = listOf(
            TrackedProperty(
                label = "Gear selection",
                fieldName = "GEAR_SELECTION",
                permission = "android.car.permission.CAR_POWERTRAIN",
            ),
            TrackedProperty(
                label = "Ignition state",
                fieldName = "IGNITION_STATE",
                permission = "android.car.permission.CAR_POWERTRAIN",
            ),
            TrackedProperty(
                label = "Outside temperature",
                fieldName = "ENV_OUTSIDE_TEMPERATURE",
                permission = "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
            ),
        )
    }
}




