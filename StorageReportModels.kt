package ru.relabs.kurjer.presentation.storageReport

import android.content.ContentResolver
import android.location.Location
import android.net.Uri
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ru.relabs.kurjer.domain.controllers.TaskEventController
import ru.relabs.kurjer.domain.models.StorageClosure
import ru.relabs.kurjer.domain.models.Task
import ru.relabs.kurjer.domain.models.photo.StorageReportPhoto
import ru.relabs.kurjer.domain.models.storage.StorageReport
import ru.relabs.kurjer.domain.models.storage.StorageReportId
import ru.relabs.kurjer.domain.providers.LocationProvider
import ru.relabs.kurjer.domain.repositories.SettingsRepository
import ru.relabs.kurjer.domain.repositories.TextSizeStorage
import ru.relabs.kurjer.domain.useCases.StorageReportUseCase
import ru.relabs.kurjer.domain.useCases.TaskUseCase
import ru.relabs.kurjer.presentation.base.tea.ElmEffect
import ru.relabs.kurjer.presentation.base.tea.ElmMessage
import ru.relabs.kurjer.presentation.base.tea.ElmRender
import ru.relabs.kurjer.presentation.base.tea.ErrorContext
import ru.relabs.kurjer.presentation.base.tea.ErrorContextImpl
import ru.relabs.kurjer.presentation.base.tea.RouterContext
import ru.relabs.kurjer.presentation.base.tea.RouterContextMainImpl
import java.io.File
import java.util.UUID

data class StorageReportState(
    val tasks: List<Task> = listOf(),
    val storageReport: StorageReport? = null,
    val storagePhotos: List<StoragePhotoWithUri> = listOf(),
    val loaders: Int = 0,
    val isGPSLoading: Boolean = false
) {
    val closureList = tasks.flatMap { task ->
        task.storage.closes.map { storageClosure ->
            Closure(
                task,
                storageClosure
            )
        }
    }.sortedBy { it.closure.closeDate }
}

data class Closure(val task: Task, val closure: StorageClosure)
data class StoragePhotoWithUri(val photo: StorageReportPhoto, val uri: Uri)

class StorageReportContext(val errorContext: ErrorContextImpl = ErrorContextImpl()) :
    ErrorContext by errorContext,
    RouterContext by RouterContextMainImpl(),
    KoinComponent {
    val taskUseCase: TaskUseCase by inject()
    val storageReportUseCase: StorageReportUseCase by inject()
    val locationProvider: LocationProvider by inject()
    val settingsRepository: SettingsRepository by inject()
    val textSizeStorage: TextSizeStorage by inject()
    val taskEventController: TaskEventController by inject()

    // Добавляем новый колбэк для сообщения о неточных координатах (пункт 3 из ТЗ)
    var showInaccurateCoordinatesDialog: () -> Unit = {}
    var showError: suspend (code: String, isFatal: Boolean) -> Unit = { _, _ -> }
    var showCloseError: (msgRes: Int, showNext: Boolean, location: Location?, msgFormat: Array<Any>) -> Unit =
        { _, _, _, _ -> }
    var requestPhoto: (id: StorageReportId, targetFile: File, uuid: UUID) -> Unit = { _, _, _ -> }
    var contentResolver: () -> ContentResolver? = { null }
    var getBatteryLevel: () -> Float? = { null }
    var showPausedWarning: () -> Unit = {}
    var showPhotosWarning: () -> Unit = {}
    var showPreCloseDialog: (location: Location?) -> Unit = { _ -> }

}

typealias StorageReportMessage = ElmMessage<StorageReportContext, StorageReportState>
typealias StorageReportEffect = ElmEffect<StorageReportContext, StorageReportState>
typealias StorageReportRender = ElmRender<StorageReportState>