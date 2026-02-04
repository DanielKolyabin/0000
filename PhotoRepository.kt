package ru.relabs.kurjer.domain.repositories

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.relabs.kurjer.data.database.AppDatabase
import ru.relabs.kurjer.data.database.entities.TaskItemEntity
import ru.relabs.kurjer.data.database.entities.TaskItemPhotoEntity
import ru.relabs.kurjer.domain.mappers.database.DatabasePhotoMapper
import ru.relabs.kurjer.domain.models.GPSCoordinatesModel
import ru.relabs.kurjer.domain.models.TaskItem
import ru.relabs.kurjer.domain.models.address
import ru.relabs.kurjer.domain.models.id
import ru.relabs.kurjer.domain.models.photo.TaskItemPhoto
import ru.relabs.kurjer.domain.models.taskId
import ru.relabs.kurjer.domain.providers.PathsProvider
import ru.relabs.kurjer.utils.CustomLog
import java.util.Date
import java.util.UUID

class PhotoRepository(private val db: AppDatabase, private val pathsProvider: PathsProvider) {
    private val photosDao = db.photosDao()

    suspend fun savePhoto(
        entrance: Int,
        taskItem: TaskItem,
        uuid: UUID,
        location: Location?
    ): TaskItemPhoto =
        withContext(Dispatchers.IO) {

            // Пункт 8 из ТЗ: дописываем точность в крэшдог
            CustomLog.writeToFile(
                "GPS LOG: Save photo - lat=${location?.latitude}, " +
                        "lon=${location?.longitude}, accuracy=${location?.accuracy}m, " +
                        "entrance=$entrance, taskItemId=${taskItem.id}, " +
                        "address=${taskItem.address.name}, uuid=$uuid"
            )

            val gps = GPSCoordinatesModel(
                location?.latitude ?: 0.0,
                location?.longitude ?: 0.0,
                location?.time?.let { Date(it) } ?: Date(0)
            )

            val photoEntity =
                TaskItemPhotoEntity(0, uuid.toString(), gps, taskItem.id.id, entrance, Date())

            val id = db.photosDao().insert(photoEntity)
            CustomLog.writeToFile("Save photo $id ent=$entrance tii=${taskItem.id} ti=${taskItem.taskId} uuid=$uuid")

            DatabasePhotoMapper.fromEntity(photoEntity.copy(id = id.toInt()))
        }

    suspend fun getTaskItemPhotos(taskItem: TaskItem): List<TaskItemPhoto> =
        withContext(Dispatchers.IO) {
            db.photosDao().getByTaskItemId(taskItem.id.id).map {
                DatabasePhotoMapper.fromEntity(it)
            }
        }

    suspend fun getUnfinishedItemPhotos(): List<TaskItemPhoto> = withContext(Dispatchers.IO) {
        db.photosDao().all
            .filter {
                db.taskItemDao().getById(it.taskItemId)?.state == TaskItemEntity.STATE_CREATED
            }
            .map { DatabasePhotoMapper.fromEntity(it) }
    }

    suspend fun removePhoto(photo: TaskItemPhoto) {
        CustomLog.writeToFile("Remove photo ${photo.id} ent=${photo.entranceNumber} tii=${photo.taskItemId} ti=${photo.uuid}")
        val file =
            pathsProvider.getTaskItemPhotoFileByID(photo.taskItemId.id, UUID.fromString(photo.uuid))
        file.delete()
        db.photosDao().deleteById(photo.id.id)
    }

    suspend fun removePhoto(photo: TaskItemPhotoEntity) {
        CustomLog.writeToFile("Remove photo ${photo.id} ent=${photo.entranceNumber} tii=${photo.taskItemId} ti=${photo.UUID}")
        val file =
            pathsProvider.getTaskItemPhotoFileByID(photo.taskItemId, UUID.fromString(photo.UUID))
        file.delete()
        db.photosDao().deleteById(photo.id)
    }

    suspend fun deleteAllTaskPhotos() {
        photosDao.all.forEach { removePhoto(it) }
        photosDao.deleteAll()
    }
}