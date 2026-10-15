package dev.ririxi.bunnylock.data

import android.content.Context
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject

data class Carrier(
    val rat: Rat,
    val primary: Boolean,
    val arfcn: Long? = null,
    val pci: Int? = null,
    val band: Int? = null,
    val bandwidthKhz: Int? = null,
    val rsrp: Int? = null,
) {
    val bandLabel get() = rat.bandLabel(band)

    val frequencyName get() = rat.frequencyName(band)
}

data class Signal(
    val rat: Rat,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
)

object Carriers {
    fun signals(
        context: Context,
        subId: Int,
    ): List<Signal> {
        val defaultTelephony = context.getSystemService(TelephonyManager::class.java) ?: return emptyList()
        val telephony = if (subId >= 0) defaultTelephony.createForSubscriptionId(subId) else defaultTelephony
        val strength = runCatching { telephony.signalStrength }.getOrNull() ?: return emptyList()
        return strength.cellSignalStrengths.mapNotNull { signal ->
            when (signal) {
                is CellSignalStrengthLte ->
                    Signal(Rat.LTE, signal.rsrp.availableOrNull(), signal.rsrq.availableOrNull(), signal.rssnr.availableOrNull())

                is CellSignalStrengthNr ->
                    Signal(Rat.NR, signal.ssRsrp.availableOrNull(), signal.ssRsrq.availableOrNull(), signal.ssSinr.availableOrNull())

                else -> null
            }
        }
    }

    private fun JSONObject.intOrNull(key: String): Int? = if (has(key)) optInt(key) else null

    fun fromReport(report: JSONArray?): List<Carrier> {
        if (report === null) return emptyList()
        return (0 until report.length()).mapNotNull { index ->
            val carrier = report.getJSONObject(index)
            val rat = Rat.entries.firstOrNull { it.name == carrier.optString("rat") } ?: return@mapNotNull null
            val arfcn = if (carrier.has("channel")) carrier.optLong("channel") else null
            Carrier(
                rat,
                carrier.optBoolean("primary"),
                arfcn,
                carrier.intOrNull("pci"),
                bandOf(rat, arfcn, carrier.intOrNull("band")),
                carrier.intOrNull("bandwidth")?.takeIf { it > 0 },
            )
        }
    }

    private fun bandOf(
        rat: Rat,
        arfcn: Long?,
        reported: Int?,
    ): Int? {
        val reportedBands = listOfNotNull(reported).toIntArray()
        return when {
            arfcn === null -> reported
            rat == Rat.LTE -> Cells.lteBand(reportedBands, arfcn.toInt())
            rat == Rat.NR -> Cells.nrBand(reportedBands, arfcn.toInt())
            else -> reported
        }
    }

    private fun fromCells(cells: List<Cell>): List<Carrier> {
        val serving = cells.filter { it.registered }
        val anchoredOnLte = serving.any { it.rat == Rat.LTE }
        return serving.map { cell ->
            Carrier(
                cell.rat,
                primary = cell.rat != Rat.NR || !anchoredOnLte,
                arfcn = cell.arfcn,
                pci = cell.pci.takeIf { it >= 0 },
                band = cell.band,
                bandwidthKhz = cell.bandwidthKhz,
                rsrp = cell.rsrp,
            )
        }
    }

    /** Fills gaps from the cell list and signal readings, and adds networks only the cell list knows. */
    fun complete(
        reported: List<Carrier>,
        cells: List<Cell>,
        signals: List<Signal>,
    ): List<Carrier> {
        // Android keeps its last list after losing service, so a list without any reading is stale.
        val inService = signals.isNotEmpty() || cells.any { it.registered }
        val current = if (inService) reported else emptyList()
        val carriers = current + fromCells(cells).filter { serving -> current.none { it.rat == serving.rat } }
        val ordered = carriers.sortedWith(compareByDescending<Carrier> { it.primary }.thenBy { it.rat.ordinal })
        return ordered.mapIndexed { index, carrier ->
            val cell = cells.firstOrNull { it.rat == carrier.rat && it.arfcn == carrier.arfcn && it.pci == carrier.pci }
            val leadsItsNetwork = ordered.indexOfFirst { it.rat == carrier.rat } == index
            val networkRsrp = if (leadsItsNetwork) signals.firstOrNull { it.rat == carrier.rat }?.rsrp else null
            carrier.copy(
                band = carrier.band ?: cell?.band,
                bandwidthKhz = carrier.bandwidthKhz ?: cell?.bandwidthKhz,
                rsrp = carrier.rsrp ?: cell?.rsrp ?: networkRsrp,
            )
        }
    }
}
