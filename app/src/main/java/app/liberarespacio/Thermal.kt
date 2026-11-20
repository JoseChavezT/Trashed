package app.liberarespacio

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Pausa el trabajo pesado mientras el teléfono está caliente.
 *
 * Usa el estado térmico del sistema (Android 10+) y, como respaldo, la temperatura de la
 * batería, porque algunos teléfonos siempre reportan THERMAL_STATUS_NONE.
 * Con histéresis: se pausa a 42 °C o estado MODERATE y se reanuda bajo 39 °C y estado LIGHT.
 */
class Thermal(private val context: Context) {

    private val power = context.getSystemService(PowerManager::class.java)

    private fun status(): Int = runCatching { power.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)

    /** Temperatura de la batería en °C, o null si no se puede leer. */
    fun batteryCelsius(): Double? {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (t == Int.MIN_VALUE) null else t / 10.0
    }

    private fun hot(): Boolean =
        status() >= PowerManager.THERMAL_STATUS_MODERATE || (batteryCelsius() ?: 0.0) >= HOT_C

    private fun cool(): Boolean =
        status() <= PowerManager.THERMAL_STATUS_LIGHT && (batteryCelsius() ?: 0.0) < RESUME_C

    /**
     * Si está caliente, espera hasta que se enfríe. [onWait] recibe true al empezar a esperar
     * (con la temperatura) y false al reanudar. Sale antes si [cancelled] devuelve true.
     */
    @Synchronized
    fun coolDown(cancelled: () -> Boolean, onWait: (Boolean, Double?) -> Unit) {
        if (!hot()) return
        onWait(true, batteryCelsius())
        while (!cancelled() && !cool()) {
            Thread.sleep(3000)
            onWait(true, batteryCelsius())
        }
        onWait(false, batteryCelsius())
    }

    companion object {
        const val HOT_C = 42.0
        const val RESUME_C = 39.0
    }
}
