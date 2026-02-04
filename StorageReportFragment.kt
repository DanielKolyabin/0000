package ru.relabs.kurjer.presentation.storageReport

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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.FileProvider
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import ru.relabs.kurjer.R
import ru.relabs.kurjer.domain.models.TaskId
import ru.relabs.kurjer.domain.models.storage.StorageReportId
import ru.relabs.kurjer.presentation.base.compose.common.themes.DeliveryTheme
import ru.relabs.kurjer.presentation.base.fragment.BaseFragment
import ru.relabs.kurjer.presentation.base.tea.defaultController
import ru.relabs.kurjer.presentation.base.tea.msgEmpty
import ru.relabs.kurjer.presentation.base.tea.sendMessage
import ru.relabs.kurjer.utils.CustomLog
import ru.relabs.kurjer.utils.extensions.showDialog
import java.io.File
import java.util.UUID

class StorageReportFragment : BaseFragment() {
    private var newPhotoData: StoragePhotoData? = null

    private val controller = defaultController(StorageReportState(), StorageReportContext())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taskIds =
            arguments?.getParcelableArrayList<TaskId>(ARG_TASK_IDS)?.toList()

        if (taskIds != null) {
            controller.start(StorageReportMessages.msgInit(taskIds))
        } else {
            controller.start(msgEmpty())
            showDialog(
                getString(R.string.unknown_runtime_error_code, "srf:100"),
                R.string.ok to {
                    uiScope.sendMessage(
                        controller,
                        StorageReportMessages.msgNavigateBack()
                    )
                }
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { DeliveryTheme { StorageReportScreen(controller) } }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        newPhotoData = savedInstanceState?.getParcelable<StoragePhotoData>(
            PHOTO_DATA_KEY
        )?.also {
            savedInstanceState.remove(PHOTO_DATA_KEY)
            CustomLog.writeToFile("Request Photo: Photo Data Restored ${it}")
        }
        controller.context.errorContext.attach(view)
        controller.context.getBatteryLevel = ::getBatteryLevel
        controller.context.requestPhoto = ::requestPhoto

        // ========== НАЧАЛО НОВОГО КОДА ==========
        // Раскомментировать и добавить все колбэки:
        controller.context.showCloseError = ::showCloseError
        controller.context.showError = ::showFatalError
        controller.context.showPausedWarning = ::showPausedWarning
        controller.context.showPhotosWarning = ::showPhotosWarning
        controller.context.showPreCloseDialog = ::showPreCloseDialog
        // ========== КОНЕЦ НОВОГО КОДА ==========

        controller.context.contentResolver = { requireContext().contentResolver }

        // ========== НАЧАЛО НОВОГО КОДА ==========
        // Добавляем колбэк для сообщения о неточных координатах
        controller.context.showInaccurateCoordinatesDialog = ::showInaccurateCoordinatesDialog
        // ========== КОНЕЦ НОВОГО КОДА ==========
    }
    // Метод для показа диалога о неточных координатах (пункт 3 из ТЗ)
    private fun showInaccurateCoordinatesDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Неточные координаты")
            .setMessage(getString(R.string.report_inaccurate_coordinates_error))
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        controller.context.getBatteryLevel = { null }
        controller.context.requestPhoto = { _, _, _ -> }

        // ========== НАЧАЛО НОВОГО КОДА ==========
        // Раскомментировать и добавить сброс всех колбэков:
        controller.context.showCloseError = { _, _, _, _ -> }
        controller.context.showError = { _, _ -> }
        controller.context.showPausedWarning = {}
        controller.context.showPhotosWarning = {}
        controller.context.showPreCloseDialog = { _ -> }
        // ========== КОНЕЦ НОВОГО КОДА ==========

        // ========== НАЧАЛО НОВОГО КОДА ==========
        // Сбрасываем колбэк для сообщения о неточных координатах
        controller.context.showInaccurateCoordinatesDialog = {}
        // ========== КОНЕЦ НОВОГО КОДА ==========

        controller.context.contentResolver = { null }
        controller.context.errorContext.detach()
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.stop()
    }

    private suspend fun showFatalError(code: String, isFatal: Boolean) =
        withContext(Dispatchers.Main) {
            FirebaseCrashlytics.getInstance().log("fatal error $isFatal $code")
            showDialog(
                getString(R.string.unknown_runtime_error_code, code),
                R.string.ok to {
                    if (isFatal) {
                        uiScope.sendMessage(controller, StorageReportMessages.msgNavigateBack())
                    }
                }
            ).setOnDismissListener {
            }
        }

    private fun showPreCloseDialog(location: Location?) {
        showDialog(
            R.string.report_close_ask,
            R.string.yes to {
                uiScope.sendMessage(
                    controller,
                    StorageReportMessages.msgPerformClose(location, true)
                )
            },
            R.string.no to {},
            style = R.style.RedAlertDialog
        ).setOnDismissListener {

        }
    }

    private fun showCloseError(
        msgRes: Int,
        withPreClose: Boolean,
        location: Location? = null,
        vararg msgFormat: Any
    ) {
        val text = Html.fromHtml(resources.getString(msgRes, *msgFormat))
        showDialog(
            text,
            R.string.ok to {
                if (withPreClose) {
                    showPreCloseDialog(location)
                }
            }
        ).setOnDismissListener {

        }
    }

    private fun showPhotosWarning() {
        showDialog(
            R.string.report_close_no_photos,
            R.string.ok to {}
        )
    }

    private fun showPausedWarning() {
        showDialog(
            R.string.report_close_paused_warning,
            R.string.ok to {
                uiScope.sendMessage(
                    controller,
                    StorageReportMessages.msgInterruptPause()
                )
            },
            R.string.cancel to {}
        ).setOnDismissListener {
        }
    }

    private fun requestPhoto(storageReportId: StorageReportId, targetFile: File, uuid: UUID) {
        val photoUri = FileProvider.getUriForFile(
            requireContext(),
            "com.relabs.kurjer.file_provider",
            targetFile
        )
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            newPhotoData =
                StoragePhotoData(storageReportId, photoUri, targetFile, uuid)
            CustomLog.writeToFile("Request Photo: Store photo data: ${newPhotoData}")
            startActivityForResult(intent, REQUEST_PHOTO_CODE)
        } else {
            uiScope.sendMessage(controller, StorageReportMessages.msgPhotoError(1))
        }
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Используем свою константу вместо ReportFragment.REQUEST_PHOTO_CODE
        if (requestCode != REQUEST_PHOTO_CODE) return

        val photoData = newPhotoData
        newPhotoData = null
        if (resultCode != Activity.RESULT_OK && resultCode != Activity.RESULT_CANCELED) {
            uiScope.sendMessage(controller, StorageReportMessages.msgPhotoError(2))
            return
        }
        if (resultCode == Activity.RESULT_CANCELED) {
            return
        }
        if (photoData == null) {
            CustomLog.writeToFile("Request Photo: Photo data is null")
            uiScope.sendMessage(controller, StorageReportMessages.msgPhotoError(3))
            return
        }

        val uri = data?.data ?: photoData.photoUri
        if (requireContext().contentResolver.getType(uri) == null) {
            uiScope.sendMessage(controller, StorageReportMessages.msgPhotoError(4))
            return
        }

        uiScope.sendMessage(
            controller,
            StorageReportMessages.msgPhotoCaptured(
                photoData.storageReportId,
                photoData.photoUri,
                photoData.targetFile,
                photoData.uuid
            )
        )
    }


    companion object {
        private const val PHOTO_DATA_KEY = "photo_data"
        private const val ARG_TASK_IDS = "task_ids"
        private const val REQUEST_PHOTO_CODE = 501

        fun newInstance(taskId: List<TaskId>) = StorageReportFragment().apply {
            arguments = Bundle().apply {
                putParcelableArrayList(ARG_TASK_IDS, ArrayList(taskId))
            }
        }
    }

    @Parcelize
    private data class StoragePhotoData(
        val storageReportId: StorageReportId,
        val photoUri: Uri,
        val targetFile: File,
        val uuid: UUID,
    ) : Parcelable
}