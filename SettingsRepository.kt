package ru.relabs.kurjer.domain.repositories

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.joda.time.DateTime
import ru.relabs.kurjer.domain.models.GpsRefreshTimes
import ru.relabs.kurjer.utils.Left
import ru.relabs.kurjer.utils.Right
import java.util.Date

/**
 * Created by Daniil Kurchanov on 13.01.2020.
 */
class SettingsRepository(
    private val api: DeliveryRepository,
    private val sharedPreferences: SharedPreferences
) {
    val scope = CoroutineScope(Dispatchers.Main)
    val closeLimit: Date
        get() = DateTime().withTime(4, 0, 0, 0).toDate()
    var closeGpsUpdateTime: GpsRefreshTimes = loadSavedGPSRefreshTimes()
    var isCloseRadiusRequired: Boolean = sharedPreferences.getBoolean(RADIUS_REQUIRED_KEY, true)
    var isPhotoRadiusRequired: Boolean = sharedPreferences.getBoolean(PHOTO_REQUIRED_KEY, true)
    var isStorageCloseRadiusRequired: Boolean = sharedPreferences.getBoolean(
        STORAGE_RADIUS_REQUIRED_KEY, true
    )
    var isStoragePhotoRadiusRequired: Boolean = sharedPreferences.getBoolean(
        STORAGE_PHOTO_REQUIRED_KEY, true
    )
    var canSkipUpdates: Boolean = loadCanSkipUpdates()
    var canSkipUnfinishedTaskItem: Boolean = loadCanSkipUnfinishedTaskItem()

    private var updateJob: Job? = null

    fun resetData() {
        closeGpsUpdateTime = GpsRefreshTimes(40, 40)
        isCloseRadiusRequired = false
        isPhotoRadiusRequired = false
        canSkipUpdates = false
        canSkipUnfinishedTaskItem = false
        sharedPreferences.edit {
            remove(RADIUS_REQUIRED_KEY)
            remove(RADIUS_KEY)
            remove(PHOTO_REQUIRED_KEY)
            remove(PHOTO_GPS_KEY)
            remove(CLOSE_GPS_KEY)
            remove(UPDATES_SKIP_KEY)
        }
    }

    suspend fun startRemoteUpdating() {
        updateJob?.cancel()
        updateJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                loadSettingsRemote()
                delay(60 * 1000)
            }
        }
    }

    fun saveFromRestored(
        restoredGpsRefreshTimes: GpsRefreshTimes,
        restoredIsCloseRadiusRequired: Boolean,
        restoredIsPhotoRadiusRequired: Boolean,
        restoredIsStorageCloseRadiusRequired: Boolean,
        restoredIsStoragePhotoRadiusRequired: Boolean,
        restoredCanSkipUpdates: Boolean,
        restoredCanSkipUnfinishedTaskItem: Boolean
    ) {
        isCloseRadiusRequired = restoredIsCloseRadiusRequired
        isPhotoRadiusRequired = restoredIsPhotoRadiusRequired
        isStorageCloseRadiusRequired = restoredIsStorageCloseRadiusRequired
        isStoragePhotoRadiusRequired = restoredIsStoragePhotoRadiusRequired
        closeGpsUpdateTime = restoredGpsRefreshTimes
        canSkipUpdates = restoredCanSkipUpdates
        canSkipUnfinishedTaskItem = restoredCanSkipUnfinishedTaskItem
        saveRadius(isCloseRadiusRequired, isPhotoRadiusRequired)
        saveStorageRadius(isStorageCloseRadiusRequired, isStoragePhotoRadiusRequired)
        saveUpdatesSkipping(canSkipUpdates)
        saveUnfinishedTaskItemSkipping(canSkipUnfinishedTaskItem)
        saveGPSRefreshTime(closeGpsUpdateTime)

    }

    private suspend fun loadSettingsRemote() = withContext(Dispatchers.Default) {
        when (val r = api.getAppSettings()) {
            is Right -> {
                isCloseRadiusRequired = r.value.isCloseRadiusRequired
                isPhotoRadiusRequired = r.value.isPhotoRadiusRequired
                isStorageCloseRadiusRequired = r.value.isStorageCloseRadiusRequired
                isStoragePhotoRadiusRequired = r.value.isStoragePhotoRadiusRequired
                closeGpsUpdateTime = r.value.gpsRefreshTimes
                canSkipUpdates = r.value.canSkipUpdates
                canSkipUnfinishedTaskItem = r.value.canSkipUnfinishedTaskitem
                saveRadius(isCloseRadiusRequired, isPhotoRadiusRequired)
                saveStorageRadius(isStorageCloseRadiusRequired, isStoragePhotoRadiusRequired)
                saveUpdatesSkipping(canSkipUpdates)
                saveUnfinishedTaskItemSkipping(canSkipUnfinishedTaskItem)
                saveGPSRefreshTime(closeGpsUpdateTime)
            }

            is Left -> Unit
        }
    }

    private fun saveUpdatesSkipping(canSkipUpdates: Boolean) {
        sharedPreferences.edit {
            putBoolean(UPDATES_SKIP_KEY, canSkipUpdates)
        }
    }

    private fun saveUnfinishedTaskItemSkipping(canSkipUnfinishedTaskItem: Boolean) {
        sharedPreferences.edit {
            putBoolean(UNFINISHED_TASK_ITEMS_SKIP_KEY, canSkipUnfinishedTaskItem)
        }
    }

    private fun saveGPSRefreshTime(gpsRefreshTimes: GpsRefreshTimes) {
        sharedPreferences.edit {
            putInt(PHOTO_GPS_KEY, gpsRefreshTimes.photo)
            putInt(CLOSE_GPS_KEY, gpsRefreshTimes.close)
        }
    }

    fun loadSavedGPSRefreshTimes(): GpsRefreshTimes {
        val photo = sharedPreferences.getInt(PHOTO_GPS_KEY, 40)
        val close = sharedPreferences.getInt(CLOSE_GPS_KEY, 40)
        return GpsRefreshTimes(close = close, photo = photo)
    }

    private fun loadCanSkipUpdates(): Boolean {
        return sharedPreferences.getBoolean(UPDATES_SKIP_KEY, false)
    }

    private fun loadCanSkipUnfinishedTaskItem(): Boolean {
        return sharedPreferences.getBoolean(UNFINISHED_TASK_ITEMS_SKIP_KEY, false)
    }

    private fun saveRadius(isCloseRadiusRequired: Boolean, isPhotoRadiusRequired: Boolean) {
        val editor = sharedPreferences.edit()
        editor.putBoolean(RADIUS_REQUIRED_KEY, isCloseRadiusRequired)
        editor.putBoolean(PHOTO_REQUIRED_KEY, isPhotoRadiusRequired)
        editor.apply()
    }

    private fun saveStorageRadius(
        isStorageCloseRadiusRequired: Boolean,
        isStoragePhotoRadiusRequired: Boolean
    ) {
        val editor = sharedPreferences.edit()
        // Используем правильные ключи для склада
        editor.putBoolean(STORAGE_RADIUS_REQUIRED_KEY, isStorageCloseRadiusRequired)
        editor.putBoolean(STORAGE_PHOTO_REQUIRED_KEY, isStoragePhotoRadiusRequired)
        editor.apply()
    }

    companion object {
        const val RADIUS_REQUIRED_KEY = "radius_required"
        const val PHOTO_REQUIRED_KEY = "photo_required"
        const val STORAGE_RADIUS_REQUIRED_KEY = "storage_radius_required"
        const val STORAGE_PHOTO_REQUIRED_KEY = "storage_photo_required"
        const val CLOSE_GPS_KEY = "close_gps"
        const val UPDATES_SKIP_KEY = "can_skip_updates"
        const val UNFINISHED_TASK_ITEMS_SKIP_KEY = "can_skip_unfinished_task_items"
        const val PHOTO_GPS_KEY = "photo_gps"

        @Deprecated("Kept for migration purpose")
        const val RADIUS_KEY = "radius"

        @Deprecated("Kept for migration purpose")
        const val DEFAULT_REQUIRED_RADIUS = 50
    }
}