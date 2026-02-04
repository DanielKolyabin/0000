package ru.relabs.kurjer.domain.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

import java.util.*

@Parcelize
data class GPSCoordinatesModel(
        val lat: Double,
        val long: Double,
        val time: Date
): Parcelable {
    val isEmpty: Boolean
        get() = lat == 0.0 || long == 0.0

    val isOld: Boolean
        get() = time.time - Date().time > 3 * 60 * 1000
}
