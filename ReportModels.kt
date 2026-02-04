package ru.relabs.kurjer.presentation.report

import android.content.ContentResolver
import android.location.Location
import android.net.Uri
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ru.relabs.kurjer.domain.controllers.TaskEventController
import ru.relabs.kurjer.domain.models.CoupleType
import ru.relabs.kurjer.domain.models.EntranceNumber
import ru.relabs.kurjer.domain.models.ReportEntranceSelection
import ru.relabs.kurjer.domain.models.Task
import ru.relabs.kurjer.domain.models.TaskItem
import ru.relabs.kurjer.domain.models.TaskItemEntrance
import ru.relabs.kurjer.domain.models.TaskItemId
import ru.relabs.kurjer.domain.models.TaskItemResult
import ru.relabs.kurjer.domain.models.TaskItemState
import ru.relabs.kurjer.domain.models.photo.TaskItemPhoto
import ru.relabs.kurjer.domain.models.state
import ru.relabs.kurjer.domain.providers.LocationProvider
import ru.relabs.kurjer.domain.providers.PathsProvider
import ru.relabs.kurjer.domain.repositories.DeliveryRepository
import ru.relabs.kurjer.domain.repositories.PauseRepository
import ru.relabs.kurjer.domain.repositories.PhotoRepository
import ru.relabs.kurjer.domain.repositories.SettingsRepository
import ru.relabs.kurjer.domain.repositories.TaskRepository
import ru.relabs.kurjer.domain.repositories.TextSizeStorage
import ru.relabs.kurjer.domain.useCases.ReportUseCase
import ru.relabs.kurjer.domain.useCases.StorageReportUseCase
import ru.relabs.kurjer.presentation.base.tea.ElmEffect
import ru.relabs.kurjer.presentation.base.tea.ElmMessage
import ru.relabs.kurjer.presentation.base.tea.ErrorContext
import ru.relabs.kurjer.presentation.base.tea.ErrorContextImpl
import ru.relabs.kurjer.presentation.base.tea.RouterContext
import ru.relabs.kurjer.presentation.base.tea.RouterContextMainImpl
import java.io.File
import java.util.Date
import java.util.UUID

/**
 * Created by Daniil Kurchanov on 02.04.2020.
 */

data class TaskWithItem(
    val task: Task,
    val taskItem: TaskItem
)

data class ReportState(
    val tasks: List<TaskWithItem> = emptyList(),
    val selectedTask: TaskWithItem? = null,
    val selectedTaskPhotos: List<TaskPhotoWithUri> = emptyList(),
    val selectedTaskReport: TaskItemResult? = null,
    val loaders: Int = 0,
    val isGPSLoading: Boolean = false,
    val exits: Int = 0,
    val isEntranceSelectionChanged: Boolean = false,

    val coupling: ReportCoupling = emptyMap()
) {
    val available: Boolean =
        selectedTask != null && selectedTask.taskItem.state == TaskItemState.CREATED && selectedTask.task.startTime < Date()
    val entrancesInfo: List<ReportEntranceItem>
        get() {
            val taskItem = selectedTask?.taskItem
            val task = selectedTask?.task
            return if (taskItem != null && taskItem is TaskItem.Common) {
                taskItem.entrancesData.map { entrance ->
                    val reportEntrance = selectedTaskReport?.entrances?.firstOrNull { it.entranceNumber == entrance.number }
                    ReportEntranceItem(
                        taskItem,
                        entrance,
                        entrance.number,
                        reportEntrance?.selection ?: ReportEntranceSelection(false, false, false, false),
                        task?.let { coupling.isCouplingEnabled(task, entrance.number) } ?: false,
                        selectedTaskPhotos.any { photo -> photo.photo.entranceNumber == entrance.number },
                        !reportEntrance?.userDescription.isNullOrEmpty()
                    )
                }
            } else {
                listOf()
            }
        }
    val firmAddress: String? =
        if (selectedTask != null && selectedTask.taskItem is TaskItem.Firm) {
            listOf(
                selectedTask.task.name,
                "№${selectedTask.task.edition}",
                "${selectedTask.taskItem.copies}экз.",
                selectedTask.taskItem.firmName,
                selectedTask.taskItem.office
            )
                .filter { it.isNotEmpty() }
                .joinToString(", ")
        } else {
            null
        }
}

data class ReportEntranceItem(
    val taskItem: TaskItem.Common,
    val entrance: TaskItemEntrance,
    val entranceNumber: EntranceNumber,
    val selection: ReportEntranceSelection,
    val coupleEnabled: Boolean,
    val hasPhoto: Boolean,
    val hasDescription: Boolean
)

data class TaskPhotoWithUri(val photo: TaskItemPhoto, val uri: Uri)
data class TaskPhotoWithUris(val Multiple: TaskItemPhoto, val uri: Uri)

class ReportContext(val errorContext: ErrorContextImpl = ErrorContextImpl()) :
    ErrorContext by errorContext,
    RouterContext by RouterContextMainImpl(),
    KoinComponent {

    val taskRepository: TaskRepository by inject()
    val photoRepository: PhotoRepository by inject()
    val locationProvider: LocationProvider by inject()
    val pauseRepository: PauseRepository by inject()
    val settingsRepository: SettingsRepository by inject()
    val reportUseCase: ReportUseCase by inject()
    val taskEventController: TaskEventController by inject()
    val pathsProvider: PathsProvider by inject()
    val deliveryRepository: DeliveryRepository by inject()
    val storageReportUseCase: StorageReportUseCase by inject()
    val textSizeStorage: TextSizeStorage by inject()

    // Добавляем новый колбэк для сообщения о неточных координатах (пункт 3 из ТЗ)
    var showInaccurateCoordinatesDialog: () -> Unit = {}
    var showError: suspend (code: String, isFatal: Boolean) -> Unit = { _, _ -> }
    var requestPhoto: (entrance: Int, multiplePhoto: Boolean, targetFile: File, uuid: UUID) -> Unit = { _, _, _, _ -> }
    var hideKeyboard: () -> Unit = {}
    var showCloseError: (msgRes: Int, showNext: Boolean, location: Location?, rejectReason: String?, msgFormat: Array<Any>) -> Unit =
        { _, _, _, _, _ -> }
    var showPausedWarning: () -> Unit = {}
    var showPhotosWarning: () -> Unit = {}
    var showPreCloseDialog: (location: Location?, rejectReason: String?) -> Unit = { _, _ -> }
    var getBatteryLevel: () -> Float? = { null }
    var contentResolver: () -> ContentResolver? = { null }
    var showRejectDialog: (reasons: List<String>) -> Unit = {}
    var showDescriptionInputDialog: (number: EntranceNumber, current: String, isEditable: Boolean) -> Unit = { _, _, _ -> }
    var showProblemApartmentsWarning: (apartments: List<String>?, entranceNumber: EntranceNumber, taskItemId: TaskItemId) -> Unit =
        { _, _, _ -> }
}

enum class EntranceSelectionButton {
    Euro, Watch, Stack, Reject
}

typealias ReportCoupling = Map<Pair<EntranceNumber, CoupleType>, Boolean>
typealias ReportMessage = ElmMessage<ReportContext, ReportState>
typealias ReportEffect = ElmEffect<ReportContext, ReportState>

fun ReportCoupling.isCouplingEnabled(task: Task, entranceNumber: EntranceNumber): Boolean {
    return this.getOrElse(entranceNumber to task.coupleType) { false }
}