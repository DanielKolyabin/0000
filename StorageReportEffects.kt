package ru.relabs.kurjer.presentation.storageReport

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.relabs.kurjer.R
import ru.relabs.kurjer.domain.models.TaskId
import ru.relabs.kurjer.domain.models.photo.StorageReportPhoto
import ru.relabs.kurjer.domain.models.storage.StorageReportId
import ru.relabs.kurjer.presentation.RootScreen
import ru.relabs.kurjer.presentation.base.tea.msgEffect
import ru.relabs.kurjer.presentation.base.tea.wrapInLoaders
import ru.relabs.kurjer.uiOld.fragments.YandexMapFragment
import ru.relabs.kurjer.uiOld.helpers.formatedWithSecs
import ru.relabs.kurjer.utils.CustomLog
import ru.relabs.kurjer.utils.Either
import ru.relabs.kurjer.utils.ImageUtils
import ru.relabs.kurjer.utils.Left
import ru.relabs.kurjer.utils.Right
import ru.relabs.kurjer.utils.awaitFirst
import ru.relabs.kurjer.utils.calculateDistance
import ru.relabs.kurjer.utils.extensions.isLocationExpired
import java.io.File
import java.util.Date
import java.util.UUID

private fun isLocationInRadius(
    photoLocation: Location,
    storageLat: Double,
    storageLon: Double,
    storageRadius: Float
): Boolean {
    val photoRadius = photoLocation.accuracy.toFloat()
    val distance = calculateDistance(
        photoLocation.latitude,
        photoLocation.longitude,
        storageLat,
        storageLon
    )

    return distance <= (storageRadius + photoRadius)
}

// Проверка точности координат (пункт 2 из ТЗ)
private fun isLocationAccurate(location: Location?): Boolean {
    return location?.accuracy?.let { it <= 50f } ?: false
}
object StorageReportEffects {


    fun effectLoadPhotos(): StorageReportEffect = wrapInLoaders({
        StorageReportMessages.msgAddLoaders(it)
    }) { c, s ->
        val photos =
            s.storageReport?.id?.let { c.storageReportUseCase.getPhotosWithUriByReportId(it) }
        if (photos != null) {
            messages.send(StorageReportMessages.msgPhotosLoaded(photos))
        }
    }

    fun effectCollectTasks(taskIds: List<TaskId>): StorageReportEffect = { c, s ->
        coroutineScope {
            launch { c.taskUseCase.watchTasks(taskIds).collect { messages.send(StorageReportMessages.msgTasksLoaded(it)) } }
        }

    }

    fun effectNavigateBack(): StorageReportEffect = { c, s ->
        withContext(Dispatchers.Main) {
            c.router.exit()
        }
    }

    fun effectReloadReport(): StorageReportEffect = wrapInLoaders({
        StorageReportMessages.msgAddLoaders(it)
    }) { c, s ->
        val storageId = s.tasks.firstOrNull()?.storage?.id
        val taskIds = s.tasks.map { it.id }
        val reports = storageId?.let { c.storageReportUseCase.getOpenedByStorageIdWithTaskIds(it, taskIds) }
        if (!reports.isNullOrEmpty()) {
            messages.send(StorageReportMessages.msgReportLoaded(reports.first()))
        }
    }

    fun effectValidateReportExistenceAnd(msgFactory: () -> StorageReportMessage): StorageReportEffect =
        { c, s ->
            if (s.storageReport == null) {
                val storageId = s.tasks.firstOrNull()?.storage?.id
                messages.send(StorageReportMessages.msgAddLoaders(1))
                val report = storageId?.let {
                    c.storageReportUseCase.createNewStorageReport(
                        it,
                        s.tasks.map { task -> task.id })
                }
                report?.let { messages.send(StorageReportMessages.msgReportLoaded(it)) }
                messages.send(StorageReportMessages.msgAddLoaders(-1))
            }
            messages.send(msgFactory())
        }

    fun effectValidateRadiusAndRequestPhoto(): StorageReportEffect = { c, s ->
        val id = s.storageReport?.id ?: StorageReportId(0)
        effectValidatePhotoRadiusAnd({ msgEffect(effectRequestPhoto(id)) }, false)(c, s)
    }

    private fun effectRequestPhoto(id: StorageReportId): StorageReportEffect = { c, s ->
        when (s.storageReport) {
            null -> c.showError("sre:100", true)
            else -> {
                val photoUUID = UUID.randomUUID()
                val photoFile = c.storageReportUseCase.getStoragePhotoFile(id, photoUUID)
                withContext(Dispatchers.Main) {
                    c.requestPhoto(id, photoFile, photoUUID)
                }
            }
        }
    }


    private fun effectValidatePhotoRadiusAnd(
        msgFactory: () -> StorageReportMessage,
        withAnyRadiusWarning: Boolean,
        withLocationLoading: Boolean = true
    ): StorageReportEffect = wrapInLoaders({
        StorageReportMessages.msgAddLoaders(it)
    }) { c, s ->
        when (s.storageReport) {
            null -> c.showError("sre:106", true)
            else -> {
                if (s.tasks.isNotEmpty()) {
                    val storage = s.tasks.first().storage
                    val location = c.locationProvider.lastReceivedLocation()

                    // Пункт 8 из ТЗ: дописываем точность в лог для крэшдога
                    CustomLog.writeToFile(
                        "GPS LOG: Storage photo check - lat=${location?.latitude}, " +
                                "lon=${location?.longitude}, accuracy=${location?.accuracy}m, " +
                                "time=${Date(location?.time ?: 0).formatedWithSecs()}, " +
                                "storage=(${storage.lat}, ${storage.long}), " +
                                "storageRadius=${storage.closeDistance}m, " +
                                "storageId=${storage.id}"
                    )

                    // Проверяем пересечение множеств (пункт 1 из ТЗ)
                    val isInRadius = location?.let { loc ->
                        isLocationInRadius(
                            loc,
                            storage.lat.toDouble(),
                            storage.long.toDouble(),
                            storage.closeDistance.toFloat()
                        )
                    } ?: false

                    val locationNotValid = location == null || Date(location.time).isLocationExpired()
                    val isAccurate = isLocationAccurate(location)

                    CustomLog.writeToFile(
                        "GPS LOG: Storage validation - valid: ${!locationNotValid}, " +
                                "inRadius: $isInRadius, accurate: $isAccurate, " +
                                "photoRadiusRequired: ${c.settingsRepository.isStoragePhotoRadiusRequired}, " +
                                "storageId: ${storage.id}"
                    )

                    // Проверяем режим Mar для склада
                    val isMarMode = !c.settingsRepository.isStoragePhotoRadiusRequired

                    if (locationNotValid && withLocationLoading) {
                        coroutineScope {
                            messages.send(StorageReportMessages.msgAddLoaders(1))
                            messages.send(StorageReportMessages.msgGPSLoading(true))
                            val delayJob =
                                async { delay(c.settingsRepository.closeGpsUpdateTime.photo * 1000L) }
                            val gpsJob = async(Dispatchers.Default) {
                                c.locationProvider.updatesChannel().apply {
                                    receive()
                                    cancel()
                                }
                            }
                            listOf(delayJob, gpsJob).awaitFirst()
                            listOf(delayJob, gpsJob).forEach {
                                if (it.isActive) {
                                    it.cancel()
                                }
                            }
                            messages.send(StorageReportMessages.msgGPSLoading(false))
                            messages.send(StorageReportMessages.msgAddLoaders(-1))
                            messages.send(
                                msgEffect(
                                    effectValidatePhotoRadiusAnd(
                                        msgFactory,
                                        withAnyRadiusWarning,
                                        false
                                    )
                                )
                            )
                        }
                    } else {
                        if (isMarMode) {
                            // Режим Mar - не показываем сообщения о неточных координатах
                            val distance = location?.let {
                                calculateDistance(
                                    location.latitude,
                                    location.longitude,
                                    storage.lat.toDouble(),
                                    storage.long.toDouble()
                                )
                            } ?: Int.MAX_VALUE.toDouble()

                            if (distance > storage.closeDistance && withAnyRadiusWarning &&
                                !c.settingsRepository.isStorageCloseRadiusRequired) {
                                withContext(Dispatchers.Main) {
                                    c.showCloseError(
                                        R.string.storage_report_close_location_far_warning,
                                        false,
                                        null,
                                        emptyArray()
                                    )
                                }
                            }
                            messages.send(msgFactory())
                        } else {
                            when {
                                locationNotValid -> withContext(Dispatchers.Main) {
                                    c.showCloseError(
                                        R.string.report_close_location_null_error,
                                        false,
                                        null,
                                        emptyArray()
                                    )
                                }

                                !isInRadius -> {
                                    if (isAccurate) {
                                        // Точные координаты, но вне радиуса
                                        withContext(Dispatchers.Main) {
                                            c.showCloseError(
                                                R.string.storage_close_location_far_error,
                                                false,
                                                null,
                                                emptyArray()
                                            )
                                        }
                                    } else {
                                        // Неточные координаты и вне радиуса
                                        withContext(Dispatchers.Main) {
                                            c.showInaccurateCoordinatesDialog()
                                        }
                                    }
                                }

                                else -> messages.send(msgFactory())
                            }
                        }
                    }
                }
            }
        }
    }

    fun effectShowPhotoError(errorCode: Int): StorageReportEffect = { c, s ->
        c.showError("Не удалось сделать фотографию re:photo:$errorCode", false)
    }

    fun effectValidateRadiusAndSavePhoto(
        storageReportId: StorageReportId,
        photoUri: Uri,
        targetFile: File,
        uuid: UUID
    ): StorageReportEffect = { c, s ->
        effectValidatePhotoRadiusAnd({
            msgEffect(
                effectSavePhotoFromFile(
                    storageReportId,
                    photoUri,
                    targetFile,
                    uuid
                )
            )
        }, true)(c, s)
    }

    private fun effectSavePhotoFromFile(
        storageReportId: StorageReportId,
        photoUri: Uri,
        targetFile: File,
        uuid: UUID
    ): StorageReportEffect = { c, s ->
        val contentResolver = c.contentResolver()
        if (contentResolver == null) {
            messages.send(msgEffect(effectShowPhotoError(8)))
        } else {
            val bmp = BitmapFactory.decodeStream(contentResolver.openInputStream(photoUri))
            if (bmp == null) {
                messages.send(msgEffect(effectShowPhotoError(7)))
            } else {
                effectSavePhotoFromBitmap(storageReportId, bmp, targetFile, uuid)(c, s)
            }
        }
    }

    private fun effectSavePhotoFromBitmap(
        storageReportId: StorageReportId,
        bitmap: Bitmap,
        targetFile: File,
        uuid: UUID
    ): StorageReportEffect = { c, s ->
        CustomLog.writeToFile("Save photo ${storageReportId.id} ${uuid}")
        when (val report = s.storageReport) {
            null -> c.showError("sre:102", true)
            else -> {
                when (savePhotoFromBitmapToFile(bitmap, targetFile)) {
                    is Left -> messages.send(StorageReportMessages.msgPhotoError(6))
                    is Right -> {
                        val location = c.locationProvider.lastReceivedLocation()
                        val photo = c.storageReportUseCase.savePhoto(report.id, uuid, location)
                        messages.send(StorageReportMessages.msgNewPhoto(photo))
                    }
                }
            }
        }
    }

    private fun savePhotoFromBitmapToFile(
        bitmap: Bitmap,
        targetFile: File
    ): Either<Exception, File> = Either.of {
        val resized = ImageUtils.resizeBitmap(bitmap, 1024f, 768f)
        bitmap.recycle()
        ImageUtils.saveImage(resized, targetFile)
        targetFile
    }

    fun effectRemovePhoto(removedPhoto: StorageReportPhoto): StorageReportEffect = { c, s ->
        c.storageReportUseCase.removePhoto(removedPhoto)
    }

    fun effectUpdateDescription(text: String): StorageReportEffect = { c, s ->
        when (val report = s.storageReport) {
            null -> c.showError("sre:103", true)
            else -> {
                val updated = c.storageReportUseCase.updateReport(report.copy(description = text))
                messages.send(StorageReportMessages.msgSavedReportLoaded(updated))
            }
        }
    }

    fun navigateMap(): StorageReportEffect = { c, s ->
        withContext(Dispatchers.Main) {
            val storage = s.tasks.firstOrNull()?.storage
            if (storage != null) {
                c.router.navigateTo(
                    RootScreen.yandexMap(
                        storages = listOf(
                            YandexMapFragment.StorageLocation(
                                storage.lat,
                                storage.long
                            )
                        )
                    ) {}
                )
            }
        }
    }

    fun effectCloseCheck(withLocationLoading: Boolean): StorageReportEffect = wrapInLoaders({
        StorageReportMessages.msgAddLoaders(it)
    }) { c, s ->
        val storage = s.tasks.firstOrNull()?.storage
        val location = c.storageReportUseCase.getLastLocation()

        if (storage == null) {
            withContext(Dispatchers.Main) {
                c.showError("sre:1", true)
            }
        } else if (s.storageReport == null) {
            withContext(Dispatchers.Main) {
                c.showError("sre:2", true)
            }
        } else if (s.storagePhotos.isEmpty() && storage.photoRequired) {
            withContext(Dispatchers.Main) {
                c.showPhotosWarning()
            }
        } else if (c.storageReportUseCase.checkPause()) {
            withContext(Dispatchers.Main) {
                c.showPausedWarning()
            }
        } else if (withLocationLoading && (location == null || Date(location.time).isLocationExpired())) {
            coroutineScope {
                messages.send(StorageReportMessages.msgAddLoaders(1))
                messages.send(StorageReportMessages.msgGPSLoading(true))
                c.storageReportUseCase.loadNewLocation(this)
                messages.send(StorageReportMessages.msgGPSLoading(false))
                messages.send(StorageReportMessages.msgAddLoaders(-1))
                messages.send(msgEffect(effectCloseCheck(false)))
            }
        } else {
            // Используем новую логику проверки
            val isInRadius = location?.let { loc ->
                isLocationInRadius(
                    loc,
                    storage.lat.toDouble(),
                    storage.long.toDouble(),
                    storage.closeDistance.toFloat()
                )
            } ?: false

            val isAccurate = isLocationAccurate(location)
            val required = c.storageReportUseCase.getCloseRadiusRequirement()

            val shadowClose: Boolean = withContext(Dispatchers.Main) {
                if (required) {
                    when {
                        location == null -> {
                            c.showCloseError(
                                R.string.report_close_location_null_error,
                                false,
                                null,
                                emptyArray()
                            )
                            true
                        }

                        !isInRadius -> {
                            if (isAccurate) {
                                c.showCloseError(
                                    R.string.storage_close_location_far_error,
                                    false,
                                    null,
                                    emptyArray()
                                )
                                true
                            } else {
                                // Для закрытия склада с неточными координатами
                                c.showCloseError(
                                    R.string.storage_close_location_far_error,
                                    false,
                                    null,
                                    emptyArray()
                                )
                                true
                            }
                        }

                        else -> {
                            c.showPreCloseDialog(location)
                            false
                        }
                    }
                } else {
                    // Режим Mar для закрытия
                    val distance = location?.let {
                        calculateDistance(
                            location.latitude,
                            location.longitude,
                            storage.lat.toDouble(),
                            storage.long.toDouble()
                        )
                    } ?: Int.MAX_VALUE.toDouble()

                    when {
                        location == null -> {
                            c.showCloseError(
                                R.string.report_close_location_null_warning,
                                true,
                                location,
                                emptyArray()
                            )
                            false
                        }

                        distance > storage.closeDistance -> {
                            c.showCloseError(
                                R.string.storage_report_close_location_far_warning,
                                true,
                                location,
                                emptyArray()
                            )
                            false
                        }

                        else -> {
                            c.showPreCloseDialog(location)
                            false
                        }
                    }
                }
            }
            if (shadowClose) {
                effectValidateReportExistenceAnd { msgEffect(effectPerformClose(location, false)) }(c, s)
            }
        }
    }

    fun effectPerformClose(location: Location?, withClose: Boolean): StorageReportEffect =
        wrapInLoaders({ StorageReportMessages.msgAddLoaders(it) }) { c, s ->
            when (val report = s.storageReport) {
                null -> {
                    c.showError("sre:2", true)
                }

                else -> {
                    if (s.tasks.isNotEmpty()) {
                        effectInterruptPause()(c, s)
                        val storage = s.tasks.first().storage
                        c.storageReportUseCase.createStorageReportRequests(
                            report, location, c.getBatteryLevel() ?: 0f, storage, withClose
                        )
                        if (withClose) {
                            messages.send(msgEffect(effectNavigateBack()))
                        }
                    } else {
                        c.showError("sre:3", true)
                    }
                }
            }
        }

    fun effectInterruptPause(): StorageReportEffect = { c, s ->
        c.storageReportUseCase.stopPause()
    }
}





