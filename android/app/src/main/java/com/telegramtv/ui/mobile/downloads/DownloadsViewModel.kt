package com.telegramtv.ui.mobile.downloads

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import java.io.File

data class DownloadItem(
    val id: Long,
    val title: String,
    val status: Int, // DownloadManager.STATUS_*
    val totalSize: Long,
    val downloadedSize: Long,
    val speed: Long = 0L, // bytes per second
    val localUri: String?,
    val mediaType: String?,
    val fileId: Int? = null
)

data class DownloadsUiState(
    val downloads: List<DownloadItem> = emptyList(),
    val isLoading: Boolean = false
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                loadDownloads()
                delay(2000) // Poll every 2 seconds
            }
        }
    }

    private val lastSizeMap = mutableMapOf<Long, Long>()
    private val lastTimeMap = mutableMapOf<Long, Long>()

    private suspend fun loadDownloads() = withContext(Dispatchers.IO) {
        val query = DownloadManager.Query()
        val cursor = downloadManager.query(query)
        val items = mutableListOf<DownloadItem>()
        val currentTime = System.currentTimeMillis()

        cursor.use {
            if (it.moveToFirst()) {
                val idCol = it.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleCol = it.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val statusCol = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val totalSizeCol = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val downloadedSizeCol = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val uriCol = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val typeCol = it.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                val descCol = it.getColumnIndex(DownloadManager.COLUMN_DESCRIPTION)

                do {
                    val id = it.getLong(idCol)
                    val title = it.getString(titleCol)
                    val status = it.getInt(statusCol)
                    val totalSize = it.getLong(totalSizeCol)
                    val downloadedSize = it.getLong(downloadedSizeCol)
                    val localUri = it.getString(uriCol)
                    val mediaType = it.getString(typeCol)
                    val description = it.getString(descCol) ?: ""
                    
                    val fileId = try {
                        if (description.startsWith("FILE_ID:")) {
                            description.substringAfter("FILE_ID:").toIntOrNull()
                        } else null
                    } catch (e: Exception) { null }

                    // Calculate speed
                    var speed = 0L
                    if (status == DownloadManager.STATUS_RUNNING) {
                        val lastSize = lastSizeMap[id] ?: 0L
                        val lastTime = lastTimeMap[id] ?: 0L
                        
                        if (lastTime > 0 && currentTime > lastTime) {
                            val sizeDiff = downloadedSize - lastSize
                            val timeDiff = currentTime - lastTime
                            if (sizeDiff > 0) {
                                speed = (sizeDiff * 1000) / timeDiff
                            }
                        }
                        
                        lastSizeMap[id] = downloadedSize
                        lastTimeMap[id] = currentTime
                    } else {
                        lastSizeMap.remove(id)
                        lastTimeMap.remove(id)
                    }

                    items.add(DownloadItem(id, title, status, totalSize, downloadedSize, speed, localUri, mediaType, fileId))
                } while (it.moveToNext())
            }
        }
        
        // Sort by ID descending (newest first)
        items.sortByDescending { it.id }

        _uiState.value = _uiState.value.copy(downloads = items)
    }
    
    fun deleteDownload(downloadId: Long) {
        viewModelScope.launch {
            downloadManager.remove(downloadId)
            loadDownloads()
        }
    }
}
