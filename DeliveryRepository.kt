package ru.relabs.kurjer.domain.repositories

import android.graphics.BitmapFactory
import android.location.Location
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.joda.time.DateTime
import retrofit2.HttpException
import retrofit2.Response
import ru.relabs.kurjer.BuildConfig
import ru.relabs.kurjer.data.api.DeliveryApi
import ru.relabs.kurjer.data.database.AppDatabase
import ru.relabs.kurjer.data.database.entities.FirmRejectReason
import ru.relabs.kurjer.data.database.entities.ReportQueryItemEntity
import ru.relabs.kurjer.data.database.entities.SendQueryItemEntity
import ru.relabs.kurjer.data.database.entities.TaskItemPhotoEntity
import ru.relabs.kurjer.data.database.entities.storage.StorageReportRequestEntity
import ru.relabs.kurjer.data.models.PhotoReportRequest
import ru.relabs.kurjer.data.models.TaskItemReportRequest
import ru.relabs.kurjer.data.models.auth.UserLogin
import ru.relabs.kurjer.data.models.common.ApiError
import ru.relabs.kurjer.data.models.common.ApiErrorContainer
import ru.relabs.kurjer.data.models.common.DomainException
import ru.relabs.kurjer.data.models.common.EitherE
import ru.relabs.kurjer.data.models.storage.StorageReportPhotoRequest
import ru.relabs.kurjer.data.models.storage.StorageReportRequest
import ru.relabs.kurjer.domain.mappers.SettingsMapper
import ru.relabs.kurjer.domain.mappers.network.PasswordMapper
import ru.relabs.kurjer.domain.mappers.network.PauseMapper
import ru.relabs.kurjer.domain.mappers.network.TaskMapper
import ru.relabs.kurjer.domain.mappers.network.UpdatesMapper
import ru.relabs.kurjer.domain.mappers.network.UserMapper
import ru.relabs.kurjer.domain.models.AppSettings
import ru.relabs.kurjer.domain.models.AppUpdatesInfo
import ru.relabs.kurjer.domain.models.PauseDurations
import ru.relabs.kurjer.domain.models.PauseTimes
import ru.relabs.kurjer.domain.models.Task
import ru.relabs.kurjer.domain.models.TaskItemState
import ru.relabs.kurjer.domain.models.User
import ru.relabs.kurjer.domain.models.id
import ru.relabs.kurjer.domain.models.photo.StorageReportPhoto
import ru.relabs.kurjer.domain.models.state
import ru.relabs.kurjer.domain.models.storage.StorageReportId
import ru.relabs.kurjer.domain.providers.ConnectivityProvider
import ru.relabs.kurjer.domain.providers.DeviceUUIDProvider
import ru.relabs.kurjer.domain.providers.DeviceUniqueIdProvider
import ru.relabs.kurjer.domain.providers.FirebaseToken
import ru.relabs.kurjer.domain.providers.FirebaseTokenProvider
import ru.relabs.kurjer.domain.providers.PathsProvider
import ru.relabs.kurjer.domain.storage.AuthTokenStorage
import ru.relabs.kurjer.domain.storage.CurrentUserStorage
import ru.relabs.kurjer.domain.storage.SavedUserStorage
import ru.relabs.kurjer.utils.CustomLog
import ru.relabs.kurjer.utils.Either
import ru.relabs.kurjer.utils.ImageUtils
import ru.relabs.kurjer.utils.Left
import ru.relabs.kurjer.utils.Right
import ru.relabs.kurjer.utils.debug
import ru.relabs.kurjer.utils.log
import timber.log.Timber
import java.io.FileNotFoundException
import java.net.URL
import java.util.UUID

class DeliveryRepository(
    private val deliveryApi: DeliveryApi,
    private val authTokenStorage: AuthTokenStorage,
    private val currentUserStorage: CurrentUserStorage,
    private val deviceIdProvider: DeviceUUIDProvider,
    private val deviceUniqueIdProvider: DeviceUniqueIdProvider,
    private val firebaseTokenProvider: FirebaseTokenProvider,
    private val database: AppDatabase,
    private val networkClient: OkHttpClient,
    private val pathsProvider: PathsProvider,
    private val storageRepository: StorageRepository,
    private val queryRepository: QueryRepository,
    private val savedUserStorage: SavedUserStorage,
    private val connectivityProvider: ConnectivityProvider,
) {
    private var availableFirmRejectReasons: List<String> = listOf()
    fun isAuthenticated(): Boolean = authTokenStorage.getToken() != null

    suspend fun login(login: UserLogin, password: String): EitherE<Pair<User, String>> =
        anonymousRequest {
            val r = deliveryApi.login(
                login.login,
                password,
                deviceIdProvider.getOrGenerateDeviceUUID().id,
                currentTime()
            )
            val user = UserMapper.fromRaw(r.user)

            user to r.token
        }

//    suspend fun login(token: String): EitherE<Pair<User, String>> = anonymousRequest {
//        val r = deliveryApi.loginByToken(
//            token,
//            deviceIdProvider.getOrGenerateDeviceUUID().id,
//            currentTime()
//        )
//        val user = UserMapper.fromRaw(r.user)
//
//        user to token
//    }

    suspend fun getTasks(): EitherE<List<Task>> = authenticatedRequest { token ->
        val deviceId = deviceIdProvider.getOrGenerateDeviceUUID()
        CustomLog.writeToFile("Received tasks from server")
        deliveryApi.getTasks(
            token,
            currentTime(),
            "${BuildConfig.VERSION_CODE}"
        ).map {
            val task = TaskMapper.fromRaw(it, deviceId)
            CustomLog.writeToFile("Task: ${task.id.id}, task items: ${task.items.map {ti ->  ti.id.id }}")
            task
        }.filter {
            val completed = it.items.all { item -> item.state == TaskItemState.CLOSED }
            if (completed) {
                queryRepository.putSendQuery(SendQueryData.TaskCompleted(it.id))
            }
            !completed
        }
    }

    suspend fun getAppUpdatesInfo(): EitherE<AppUpdatesInfo> = anonymousRequest {
        val updates = UpdatesMapper.fromRaw(deliveryApi.getUpdateInfo())

        updates
    }

    suspend fun updatePushToken(): EitherE<Boolean> = authenticatedRequest { token ->
        when (val t = firebaseTokenProvider.get()) {
            is Right -> when (val r = updatePushToken(t.value)) {
                is Right -> r.value
                is Left -> throw r.value
            }

            is Left -> {
                throw t.value
            }
        }
    }

    suspend fun updatePushToken(firebaseToken: FirebaseToken): EitherE<Boolean> =
        authenticatedRequest { token ->
            firebaseTokenProvider.set(firebaseToken)
            deliveryApi.sendPushToken(token, firebaseToken.token)
            true
        }

    suspend fun updateDeviceIMEI(): EitherE<Boolean> = authenticatedRequest { token ->
        deliveryApi.sendDeviceImei(token, deviceUniqueIdProvider.get().id)
        true
    }

    suspend fun updateLocation(location: Location): EitherE<Boolean> =
        authenticatedRequest { token ->
            deliveryApi.sendGPS(
                token,
                location.latitude,
                location.longitude,
                currentTime()
            )
            true
        }

    suspend fun sendStorageReport(item: StorageReportRequestEntity): Either<Exception, Unit> =
        Either.of {
            val report = storageRepository.getReportById(StorageReportId(item.storageReportId))
            val photosMap = mutableMapOf<String, StorageReportPhotoRequest>()
            val photoParts = mutableListOf<MultipartBody.Part>()
            val photos =
                storageRepository.getPhotosByReportId(StorageReportId(item.storageReportId))

            var imgCount = 0
            photos.forEachIndexed { i, photo ->
                try {
                    photoParts.add(storagePhotoToPart("img_$imgCount", item, photo))
                    photosMap["img_$imgCount"] =
                        StorageReportPhotoRequest("", photo.gps, photo.time)
                    imgCount++
                } catch (e: Throwable) {
                    e.fillInStackTrace().log()
                }
            }

            CustomLog.writeToFile(
                "User: ${currentUserStorage.getCurrentUserLogin()}, Task id = ${item.taskId}," +
                        " storage id = ${report.storageId.id}, GPS = ${report.gps.lat} ${report.gps.long} ${report.gps.time}, " +
                        "deviceCloseAnyDistance = ${report.closeData?.deviceCloseAnyDistance}, allowed distance = ${report.closeData?.deviceAllowedDistance}, " +
                        "distance = ${report.closeData?.deviceRadius}"
            )

            if (report.closeData != null) {
                val reportObject = StorageReportRequest(
                    item.taskId,
                    item.token,
                    report.storageId.id,
                    report.gps,
                    report.description,
                    report.closeData.closeTime,
                    report.closeData.batteryLevel,
                    report.closeData.deviceRadius,
                    report.closeData.deviceCloseAnyDistance,
                    report.closeData.deviceAllowedDistance,
                    report.closeData.isPhotoRequired,
                    photosMap
                )
                deliveryApi.sendStorageReport(report.id.id, reportObject, photoParts, item.token)
            }
        }

    //Reports
    suspend fun sendReport(item: ReportQueryItemEntity): Either<Exception, Unit> = Either.of {
        val photosMap = mutableMapOf<String, PhotoReportRequest>()
        val photoParts = mutableListOf<MultipartBody.Part>()
        val photos = database.photosDao().getByTaskItemId(item.taskItemId)

        var imgCount = 0
        photos.forEachIndexed { i, photo ->
            try {
                photoParts.add(photoEntityToPart("img_$imgCount", item, photo))
                photosMap["img_$imgCount"] =
                    PhotoReportRequest("", photo.gps, photo.entranceNumber, photo.date)
                imgCount++
            } catch (e: Throwable) {
                e.fillInStackTrace().log()
            }
        }

        val reportObject = TaskItemReportRequest(
            item.taskId, item.taskItemId, item.imageFolderId,
            item.gps, item.closeTime, item.userDescription, item.entrances, photosMap,
            item.batteryLevel, item.closeDistance, item.allowedDistance, item.radiusRequired,
            item.isRejected, item.rejectReason, item.deliveryType, item.isPhotoRequired
        )

        deliveryApi.sendTaskReport(
            item.taskItemId,
            reportObject,
            photoParts,
            item.token,
            "${BuildConfig.VERSION_CODE}"
        )
    }

    private fun photoEntityToPart(
        partName: String,
        reportEnt: ReportQueryItemEntity,
        photoEnt: TaskItemPhotoEntity
    ): MultipartBody.Part {
        val photoFile = pathsProvider.getTaskItemPhotoFileByID(
            reportEnt.taskItemId,
            UUID.fromString(photoEnt.UUID)
        )
        if (!photoFile.exists()) {
            throw FileNotFoundException(photoFile.path)
        }

        val request =
            RequestBody.run { photoFile.asRequestBody(MediaType.run { "image/jpeg".toMediaType() }) }

        return MultipartBody.Part.createFormData(partName, photoFile.name, request)
    }

    private fun storagePhotoToPart(
        partName: String,
        reportEnt: StorageReportRequestEntity,
        photo: StorageReportPhoto
    ): MultipartBody.Part {
        val photoFile = pathsProvider.getStoragePhotoFileById(
            reportId = reportEnt.storageReportId,
            uuid = UUID.fromString(photo.uuid)
        )
        if (!photoFile.exists()) {
            throw FileNotFoundException(photoFile.path)
        }

        val request =
            RequestBody.run { photoFile.asRequestBody(MediaType.run { "image/jpeg".toMediaType() }) }
        return MultipartBody.Part.createFormData(partName, photoFile.name, request)
    }

    //Pauses
    suspend fun getLastPauseTimes(): EitherE<PauseTimes> = authenticatedRequest { token ->
        PauseMapper.fromRaw(deliveryApi.getLastPauseTimes(token))
    }

    suspend fun isPauseAllowed(pauseType: PauseType): EitherE<Boolean> =
        authenticatedRequest { token ->
            deliveryApi.isPauseAllowed(token, pauseType.ordinal).status
        }

    suspend fun getPauseDurations(): EitherE<PauseDurations> = anonymousRequest {
        PauseMapper.fromRaw(deliveryApi.getPauseDurations())
    }

    suspend fun getAppSettings(): EitherE<AppSettings> = authenticatedRequest { token ->
        SettingsMapper.fromRaw(deliveryApi.getSettings(token))
    }

    suspend fun loadTaskMap(task: Task): Either<Exception, Unit> = Either.of {
        val url = URL(task.rastMapUrl)
        val bmp = BitmapFactory.decodeStream(url.openStream())
        val mapFile = pathsProvider.getTaskRasterizeMapFile(task)
        ImageUtils.saveImage(bmp, mapFile)
        bmp.recycle()
    }

    suspend fun loadEditionPhoto(task: Task): Either<Exception, Unit> = Either.of {
        if (task.editionPhotoUrl == null) return@of Unit
        val url = URL(task.editionPhotoUrl)
        val bmp = BitmapFactory.decodeStream(url.openStream())
        val mapFile = pathsProvider.getEditionPhotoFile(task)
        ImageUtils.saveImage(bmp, mapFile)
        bmp.recycle()
    }

    suspend fun sendQuery(item: SendQueryItemEntity): Either<java.lang.Exception, Unit> =
        Either.of {
            val postDataBuilder = FormBody.Builder()
            item.post_data.split("&").forEach {
                it.split("=")
                    .let {
                        val left = it.getOrNull(0)
                        val right = it.getOrNull(1)
                        if (left != null && right != null) {
                            left to right
                        } else {
                            null
                        }
                    }
                    ?.let {
                        postDataBuilder.add(it.first, it.second)
                    }
            }
            val request = Request.Builder()
                .url(item.url)
                .post(postDataBuilder.build())
                .build()
            val client = networkClient.newCall(request).execute()
            if (client.code != 200) {
                throw Exception("Wrong response code. Code: ${client.code}. Body: ${client.body}. Message: ${client.message}")
            }
        }

    suspend fun updateSavedData() {
        val token = authTokenStorage.getToken() ?: return
        if (savedUserStorage.getCredentials() != null && savedUserStorage.getToken() != null) return
        try {
            savedUserStorage.saveToken(token)
            savedUserStorage.saveCredentials(
                currentUserStorage.getCurrentUserLogin() ?: UserLogin(""),
                PasswordMapper.fromRaw(deliveryApi.getPassword(token)).also { Timber.d(it) }
            )
        } catch (e: Exception) {
            Timber.d(e)
        }
    }

    suspend fun getFirmRejectReasons(withRefresh: Boolean = false): EitherE<List<String>> =
        authenticatedRequest { token ->
            if (!withRefresh && availableFirmRejectReasons.isEmpty()) {
                availableFirmRejectReasons = database.firmRejectReasonDao().all.map { it.reason }
            }
            if ((withRefresh || availableFirmRejectReasons.isEmpty()) && token.isNotBlank()) {
                availableFirmRejectReasons = deliveryApi.getAvailableFirmRejectReasons(token)
                database.firmRejectReasonDao().clear()
                database.firmRejectReasonDao()
                    .insertAll(availableFirmRejectReasons.map { FirmRejectReason(0, it) })
            }
            availableFirmRejectReasons
        }

    private fun currentTime(): String = DateTime().toString("yyyy-MM-dd'T'HH:mm:ss")

    private suspend inline fun <T> authenticatedRequest(crossinline block: suspend (token: String) -> T): EitherE<T> {
        return authTokenStorage.getToken()?.let { token -> anonymousRequest { block(token) } }
            ?: Left(DomainException.ApiException(ApiError(401, "Empty token", null)))
    }

    private suspend inline fun <T> anonymousRequest(crossinline block: suspend () -> T): EitherE<T> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                Right(block())
            } catch (e: CancellationException) {
                debug("CancellationException $e")
                CustomLog.writeToFile(CustomLog.getStacktraceAsString(e))
                Left(DomainException.CanceledException)
            } catch (e: HttpException) {
                debug("HttpException $e")
                CustomLog.writeToFile(CustomLog.getStacktraceAsString(e))
                if (e.code() == 401) {
                    Left(DomainException.ApiException(ApiError(401, "Unauthorized", null)))
                } else {
                    mapApiException(e)?.let { Left(it) } ?: Left(DomainException.UnknownException)
                }
            } catch (e: Exception) {
                debug("UnknownException $e")
                CustomLog.writeToFile("UnknownException ${e.message}")
                Left(DomainException.UnknownException)
            }
        }

    private fun mapApiException(httpException: HttpException): DomainException.ApiException? {
        return parseErrorBody(httpException.response())?.let { DomainException.ApiException(it) }
    }

    private fun parseErrorBody(response: Response<*>?): ApiError? {
        return try {
            Gson().fromJson(response?.errorBody()?.string(), ApiErrorContainer::class.java)?.error
        } catch (e: Exception) {
            debug("Can't parse HTTP error", e)
            return null
        }
    }

    suspend fun updateNetworkStatus(status: Boolean) {
        connectivityProvider.setStatus(status)
    }


}