package ru.relabs.kurjer.presentation.report

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.provider.MediaStore
import android.text.Html
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.FileProvider
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import ru.relabs.kurjer.R
import ru.relabs.kurjer.domain.models.EntranceNumber
import ru.relabs.kurjer.domain.models.Task
import ru.relabs.kurjer.domain.models.TaskId
import ru.relabs.kurjer.domain.models.TaskItem
import ru.relabs.kurjer.domain.models.TaskItemId
import ru.relabs.kurjer.domain.models.id
import ru.relabs.kurjer.presentation.base.compose.common.themes.DeliveryTheme
import ru.relabs.kurjer.presentation.base.fragment.BaseFragment
import ru.relabs.kurjer.presentation.base.tea.defaultController
import ru.relabs.kurjer.presentation.base.tea.msgEmpty
import ru.relabs.kurjer.presentation.base.tea.sendMessage
import ru.relabs.kurjer.presentation.dialogs.RejectFirmDialog
import ru.relabs.kurjer.utils.CustomLog
import ru.relabs.kurjer.utils.extensions.hideKeyboard
import ru.relabs.kurjer.utils.extensions.showDialog
import java.io.File
import java.util.UUID


/**
 * Created by Daniil Kurchanov on 02.04.2020.
 */

class ReportFragment : BaseFragment() {
    private var nextPhotoData: ReportPhotoData? = null

    private val controller = defaultController(ReportState(), ReportContext())

    //    private var renderJob: Job? = null
    private var isCloseClicked = false
        set(v) {
            isCloseClickedFlow.tryEmit(v)
            field = v
        }
    private val isCloseClickedFlow = MutableStateFlow(isCloseClicked)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val itemIds = arguments?.getParcelableArrayList<ArgItem>(ARG_ITEMS_KEY)?.map {
            Pair(it.task, it.taskItem)
        }
        val selectedItemId = arguments?.getInt(ARG_SELECTED_TASK_ITEM_ID, ARG_SELECTED_TASK_ITEM_EMPTY)
            ?.takeIf { it != ARG_SELECTED_TASK_ITEM_EMPTY }
            ?.let { TaskItemId(it) }

        if (itemIds == null || selectedItemId == null) {
            showDialog(
                getString(R.string.unknown_runtime_error_code, "rf:100"),
                R.string.ok to { uiScope.sendMessage(controller, ReportMessages.msgBackClicked()) }
            )
            controller.start(msgEmpty())
        } else {
            controller.start(ReportMessages.msgInit(itemIds, selectedItemId))
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        controller.stop()
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DeliveryTheme {
                    ReportScreen(controller, isCloseClickedFlow) { isCloseClicked = true }
                }
            }
        }
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        nextPhotoData = savedInstanceState?.getParcelable<ReportPhotoData>(NEXT_PHOTO_DATA_KEY)?.also {
            savedInstanceState.remove(NEXT_PHOTO_DATA_KEY)

            CustomLog.writeToFile("Request Photo: Photo Data Restored ${it}")
        }

        controller.context.errorContext.attach(view)
        controller.context.requestPhoto = ::requestPhoto
        controller.context.hideKeyboard = ::hideKeyboard
        controller.context.showCloseError = ::showCloseError
        controller.context.showPausedWarning = ::showPausedWarning
        controller.context.showPhotosWarning = ::showPhotosWarning
        controller.context.showPreCloseDialog = ::showPreCloseDialog
        controller.context.getBatteryLevel = ::getBatteryLevel
        controller.context.showError = ::showFatalError
        controller.context.contentResolver = { requireContext().contentResolver }
        controller.context.showRejectDialog = ::showRejectDialog
        controller.context.showDescriptionInputDialog = ::showDescriptionInputDialog
        // Добавляем колбэк для сообщения о неточных координатах
        controller.context.showInaccurateCoordinatesDialog = ::showInaccurateCoordinatesDialog
    }

    private fun showInaccurateCoordinatesDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Неточные координаты")
            .setMessage(getString(R.string.report_inaccurate_coordinates_error))
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                isCloseClicked = false
            }
            .setCancelable(false)
            .show()
    }

    private fun showDescriptionInputDialog(entranceNumber: EntranceNumber, description: String, isEditable: Boolean) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            isEnabled = isEditable
            filters = arrayOf(InputFilter.LengthFilter(100))
            setText(description)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Описание")
            .setView(input)
            .setPositiveButton("Ок") { _, _ ->
                uiScope.sendMessage(
                    controller,
                    ReportMessages.msgEntranceDescriptionChanged(entranceNumber, input.text.toString())
                )
            }
            .setNegativeButton("Отмена") { _, _ -> }
            .show()
    }

    private fun showRejectDialog(reasons: List<String>) {
        RejectFirmDialog(reasons) {
            uiScope.sendMessage(controller, ReportMessages.msgCloseClicked(it))
        }.apply {
            setOnDismissListener { isCloseClicked = false }
        }.show(childFragmentManager, "dialog_reject")
    }

    private suspend fun showFatalError(code: String, isFatal: Boolean) = withContext(Dispatchers.Main) {
        FirebaseCrashlytics.getInstance().log("fatal error $isFatal $code")
        showDialog(
            getString(R.string.unknown_runtime_error_code, code),
            R.string.ok to {
                if (isFatal) {
                    uiScope.sendMessage(controller, ReportMessages.msgBackClicked())
                }
            }
        ).setOnDismissListener {
            isCloseClicked = false
        }

        Unit
    }

    private fun getBatteryLevel(): Float? {
        val ifilter = IntentFilter("android.intent.action.BATTERY_CHANGED")
        val battery = context?.registerReceiver(null as BroadcastReceiver?, ifilter)
        return battery?.let { it ->
            val level = it.getIntExtra("level", -1)
            val scale = it.getIntExtra("scale", -1)
            level.toFloat() / scale.toFloat()
        }
    }

    private fun showPreCloseDialog(location: Location?, rejectReason: String?) {
        showDialog(
            R.string.report_close_ask,
            R.string.yes to { uiScope.sendMessage(controller, ReportMessages.msgPerformClose(location, rejectReason)) },
            R.string.no to {},
            style = R.style.RedAlertDialog
        ).setOnDismissListener {
            isCloseClicked = false
        }
    }

    private fun showCloseError(
        msgRes: Int,
        withPreClose: Boolean,
        location: Location? = null,
        rejectReason: String?,
        vararg msgFormat: Any
    ) {
        val text = Html.fromHtml(resources.getString(msgRes, *msgFormat))
        showDialog(
            text,
            R.string.ok to {
                if (withPreClose) {
                    showPreCloseDialog(location, rejectReason)
                }
            }
        ).setOnDismissListener {
            isCloseClicked = false
        }
    }

    private fun showPhotosWarning() {
        showDialog(
            R.string.report_close_no_photos,
            R.string.ok to {}
        ).setOnDismissListener {
            isCloseClicked = false
        }
    }

    private fun showPausedWarning() {
        showDialog(
            R.string.report_close_paused_warning,
            R.string.ok to { uiScope.sendMessage(controller, ReportMessages.msgInterruptPause()) },
            R.string.cancel to {}
        ).setOnDismissListener {
            isCloseClicked = false
        }
    }

    private fun requestPhoto(entrance: Int, multiplePhoto: Boolean, targetFile: File, uuid: UUID) {
        val photoUri = FileProvider.getUriForFile(
            requireContext(),
            "com.relabs.kurjer.file_provider",
            targetFile
        )

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            nextPhotoData = ReportPhotoData(entrance, multiplePhoto, photoUri, targetFile, uuid)
            CustomLog.writeToFile("Request Photo: Store photo data: ${nextPhotoData}")
            startActivityForResult(intent, REQUEST_PHOTO_CODE)
        } else {
            uiScope.sendMessage(controller, ReportMessages.msgPhotoError(1))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        nextPhotoData?.let {
            outState.putParcelable(NEXT_PHOTO_DATA_KEY, it)
            CustomLog.writeToFile("Request Photo: Save Photo Data ${nextPhotoData}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PHOTO_CODE) return
        val photoData = nextPhotoData
        nextPhotoData = null
        if (resultCode != Activity.RESULT_OK && resultCode != Activity.RESULT_CANCELED) {
            uiScope.sendMessage(controller, ReportMessages.msgPhotoError(2))
            return
        }
        if (resultCode == Activity.RESULT_CANCELED) {
            return
        }
        if (photoData == null) {
            CustomLog.writeToFile("Request Photo: Photo data is null")
            uiScope.sendMessage(controller, ReportMessages.msgPhotoError(3))
            return
        }

        val uri = data?.data ?: photoData.photoUri
        if (requireContext().contentResolver.getType(uri) == null) {
            uiScope.sendMessage(controller, ReportMessages.msgPhotoError(4))
            return
        }

        uiScope.sendMessage(
            controller,
            ReportMessages.msgPhotoCaptured(
                photoData.entrance,
                photoData.multiplePhoto,
                photoData.photoUri,
                photoData.targetFile,
                photoData.uuid
            )
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        controller.context.showError = { _, _ -> }
        controller.context.hideKeyboard = {}
        controller.context.requestPhoto = { _, _, _, _ -> }
        controller.context.showCloseError = { _, _, _, _, _ -> }
        controller.context.showPausedWarning = {}
        controller.context.showPhotosWarning = {}
        controller.context.showPreCloseDialog = { _, _ -> }
        controller.context.getBatteryLevel = { null }
        controller.context.contentResolver = { null }
        // Сбрасываем колбэк для сообщения о неточных координатах
        controller.context.showInaccurateCoordinatesDialog = {}
        // Сбрасываем другие колбэки
        controller.context.showRejectDialog = {}
        controller.context.showDescriptionInputDialog = { _, _, _ -> }

        controller.context.errorContext.detach()
        isCloseClicked = false
    }

    override fun interceptBackPressed(): Boolean {
        return false
    }

    companion object {
        private const val NEXT_PHOTO_DATA_KEY = "next_photo_data"
        const val ARG_ITEMS_KEY = "items"
        const val ARG_SELECTED_TASK_ITEM_ID = "task_item_id"
        const val ARG_SELECTED_TASK_ITEM_EMPTY = -999
        const val REQUEST_PHOTO_CODE = 501

        fun newInstance(items: List<Pair<Task, TaskItem>>, selectedTaskItem: TaskItem) = ReportFragment().apply {
            arguments = Bundle().apply {
                putParcelableArrayList(ARG_ITEMS_KEY, ArrayList(items.map { ArgItem(it.first.id, it.second.id) }))
                putInt(ARG_SELECTED_TASK_ITEM_ID, selectedTaskItem.id.id)
            }
        }
    }

    @Parcelize
    private data class ArgItem(val task: TaskId, val taskItem: TaskItemId) : Parcelable

    @Parcelize
    private data class ReportPhotoData(
        val entrance: Int,
        val multiplePhoto: Boolean,
        val photoUri: Uri,
        val targetFile: File,
        val uuid: UUID
    ) : Parcelable
}