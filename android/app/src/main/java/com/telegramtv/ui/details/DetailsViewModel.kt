package com.telegramtv.ui.details

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telegramtv.data.model.FileItem
import com.telegramtv.data.model.WatchProgress
import com.telegramtv.data.repository.AuthRepository
import com.telegramtv.data.repository.FilesRepository
import com.telegramtv.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Details screen UI state.
 */
data class DetailsUiState(
    val isLoading: Boolean = true,
    val file: FileItem? = null,
    val watchProgress: WatchProgress? = null,
    val serverUrl: String = "",
    val error: String? = null,
    val downloadStarted: Boolean = false,
    val downloadId: Long? = null,
    val downloadStatus: Int? = null,
    val downloadProgress: Int = 0,
    // New fields for enhanced download UI
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val downloadSpeed: Long = 0L,        // bytes per second
    val localFilePath: String? = null,    // path to downloaded file
    val isFileLocal: Boolean = false      // whether file exists locally
)

/**
 * ViewModel for the file details screen.
 */
@HiltViewModel
class DetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val filesRepository: FilesRepository,
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val fileId: Int = savedStateHandle.get<Int>("fileId") ?: 0

    private val _uiState = MutableStateFlow(DetailsUiState())
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()

    init {
        loadFileDetails()
    }

    /**
     * Load file details and watch progress.
     */
    fun loadFileDetails() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val serverUrl = settingsRepository.getServerUrl()
            _uiState.value = _uiState.value.copy(serverUrl = serverUrl)

            // Load file details
            val fileResult = filesRepository.getFile(fileId)
            fileResult.fold(
                onSuccess = { file ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        file = file
                    )
                    // Check if file is already downloaded locally
                    checkLocalFile(file.fileName)
                    // Load watch progress
                    loadWatchProgress()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load file details"
                    )
                }
            )
        }
    }

    /**
     * Load watch progress for the file.
     */
    private suspend fun loadWatchProgress() {
        val progressResult = filesRepository.getWatchProgress(fileId)
        progressResult.fold(
            onSuccess = { progress ->
                _uiState.value = _uiState.value.copy(watchProgress = progress)
            },
            onFailure = {
                // Ignore - no progress saved yet
            }
        )
    }

    /**
     * Check if the file already exists in local Downloads folder.
     */
    private fun checkLocalFile(fileName: String) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        val localFile = File(downloadsDir, fileName)
        if (localFile.exists() && localFile.length() > 0) {
            _uiState.value = _uiState.value.copy(
                isFileLocal = true,
                localFilePath = localFile.absolutePath,
                downloadStatus = DownloadManager.STATUS_SUCCESSFUL
            )
        }
    }

    /**
     * Get the resume position in milliseconds, or 0 to start from beginning.
     */
    fun getResumePositionMs(): Long {
        return (_uiState.value.watchProgress?.position ?: 0) * 1000L
    }

    /**
     * Get thumbnail URL.
     */
    fun getThumbnailUrl(): String {
        return "${_uiState.value.serverUrl}/api/stream/$fileId/thumbnail"
    }

    /**
     * Get stream URL.
     */
    fun getStreamUrl(): String {
        return "${_uiState.value.serverUrl}/api/stream/$fileId"
    }

    /**
     * Get local file URI for offline playback.
     */
    fun getLocalFileUri(): Uri? {
        val path = _uiState.value.localFilePath ?: return null
        val file = File(path)
        return if (file.exists()) Uri.fromFile(file) else null
    }

    /**
     * Delete the locally downloaded file.
     */
    fun deleteLocalFile(context: Context) {
        val path = _uiState.value.localFilePath ?: return
        val file = File(path)

        viewModelScope.launch {
            try {
                if (file.exists() && file.delete()) {
                    _uiState.value = _uiState.value.copy(
                        isFileLocal = false,
                        localFilePath = null,
                        downloadStatus = null,
                        downloadStarted = false,
                        downloadId = null,
                        downloadProgress = 0,
                        downloadedBytes = 0L,
                        totalBytes = -1L,
                        downloadSpeed = 0L
                    )
                    Toast.makeText(context, "File deleted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Could not delete file", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Format bytes to human-readable string.
     */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        return "%.1f %s".format(bytes / Math.pow(1024.0, index.toDouble()), units[index])
    }

    /**
     * Download file to device storage using Android DownloadManager.
     */
    fun startDownload(context: Context) {
        val file = _uiState.value.file ?: return
        val serverUrl = _uiState.value.serverUrl
        if (serverUrl.isBlank()) return

        // Prevent multiple downloads
        if (_uiState.value.downloadId != null && _uiState.value.downloadStatus == DownloadManager.STATUS_RUNNING) {
            return
        }

        viewModelScope.launch {
            try {
                val token = authRepository.getAccessToken()
                if (token.isNullOrBlank()) {
                    Toast.makeText(context, "Not authenticated", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Build download URL with ?download=1 to force Content-Disposition: attachment
                val downloadUrl = "$serverUrl/api/stream/$fileId?download=1"

                val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                    // Add auth header
                    addRequestHeader("Authorization", "Bearer $token")

                    // Save to Downloads folder
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        file.fileName
                    )

                    // Show notification during & after download
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    setTitle(file.fileName)
                    setDescription("Downloading from TelegramTV")

                    // Set MIME type
                    file.mimeType?.let { setMimeType(it) }

                    // Allow mobile & wifi
                    setAllowedNetworkTypes(
                        DownloadManager.Request.NETWORK_WIFI or
                        DownloadManager.Request.NETWORK_MOBILE
                    )
                }

                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val id = downloadManager.enqueue(request)

                _uiState.value = _uiState.value.copy(
                    downloadStarted = true,
                    downloadId = id,
                    downloadStatus = DownloadManager.STATUS_PENDING,
                    downloadProgress = 0,
                    downloadedBytes = 0L,
                    totalBytes = -1L,
                    downloadSpeed = 0L
                )
                
                Toast.makeText(
                    context,
                    "⬇ Downloading: ${file.fileName}",
                    Toast.LENGTH_SHORT
                ).show()

                // Start tracking progress
                trackDownloadProgress(context, id)

            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Download failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                _uiState.value = _uiState.value.copy(
                    downloadStarted = false,
                    error = e.message
                )
            }
        }
    }

    /**
     * Poll DownloadManager for progress updates until finished.
     */
    private fun trackDownloadProgress(context: Context, downloadId: Long) {
        viewModelScope.launch {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            var isDownloading = true
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()

            while (isDownloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)

                if (cursor != null && cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val downloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                    if (statusIndex >= 0) {
                        val status = cursor.getInt(statusIndex)
                        val downloaded = if (downloadedIndex >= 0) cursor.getLong(downloadedIndex) else 0L
                        val total = if (totalIndex >= 0) cursor.getLong(totalIndex) else -1L

                        val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0

                        // Calculate speed
                        val now = System.currentTimeMillis()
                        val timeDelta = (now - lastTime).coerceAtLeast(1)
                        val bytesDelta = downloaded - lastBytes
                        val speed = if (timeDelta > 0) (bytesDelta * 1000) / timeDelta else 0L

                        lastBytes = downloaded
                        lastTime = now

                        _uiState.value = _uiState.value.copy(
                            downloadStatus = status,
                            downloadProgress = progress,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            downloadSpeed = speed
                        )

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            isDownloading = false
                            // Update local file state
                            val fileName = _uiState.value.file?.fileName ?: ""
                            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS
                            )
                            val localFile = File(downloadsDir, fileName)
                            _uiState.value = _uiState.value.copy(
                                isFileLocal = true,
                                localFilePath = localFile.absolutePath
                            )
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            isDownloading = false
                        }
                    }
                    cursor.close()
                } else {
                    isDownloading = false // Download not found
                }

                if (isDownloading) {
                    kotlinx.coroutines.delay(1000) // Poll every second
                }
            }
        }
    }
}
