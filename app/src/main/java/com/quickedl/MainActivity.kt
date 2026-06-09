package com.quickedl

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("quickedl", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val videos = mutableListOf<VideoRow>()
    private val ranges = mutableListOf<EdlRange>()
    private var currentDirectory: DocumentFile? = null
    private var currentVideo: VideoRow? = null
    private var pendingInMs: Long? = null
    private var pendingOutMs: Long? = null
    private var slowAnchorMs = 0L
    private var isUserFastScrubbing = false
    private var isUserSlowScrubbing = false
    private var isFileListVisible = true
    private var loadGeneration = 0
    private var rangeLoadGeneration = 0

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var fileListButton: Button
    private lateinit var headerText: TextView
    private lateinit var statusText: TextView
    private lateinit var transportTimeText: TextView
    private lateinit var playPauseButton: Button
    private lateinit var listView: ListView
    private lateinit var adapter: VideoAdapter
    private lateinit var timelineView: EdlTimelineView
    private lateinit var fastSeek: SeekBar
    private lateinit var slowSeek: SeekBar

    private val tick = object : Runnable {
        override fun run() {
            updateScrubbersFromPlayer()
            handler.postDelayed(this, nextUiRefreshDelayMs())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        buildUi()
        restoreLastDirectory()
        handler.post(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildUi()
        updateHeader()
        updateStatus()
        updateScrubbersFromPlayer()
    }

    @Deprecated("Deprecated in platform API, still adequate for this minimal SAF picker.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_DIRECTORY || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        contentResolver.takePersistableUriPermission(uri, flags)
        prefs.edit().putString(KEY_LAST_TREE, uri.toString()).apply()
        loadDirectory(uri)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF7F7F7.toInt())
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 10, 12, 8)
        }
        topBar.addView(button("Folder") { openDirectoryPicker() })
        fileListButton = button(fileListButtonText()) { toggleFileList() }
        topBar.addView(fileListButton)
        headerText = TextView(this).apply {
            text = "Choose a folder"
            textSize = 15f
            setPadding(14, 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        topBar.addView(headerText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(topBar)

        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val content = LinearLayout(this).apply {
            orientation = if (isPortrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        listView = ListView(this)
        adapter = VideoAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> openVideo(videos[position]) }
        if (isFileListVisible) {
            if (isPortrait) {
                content.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.22f))
            } else {
                content.addView(listView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.25f))
            }
        }

        playerView = PlayerView(this).apply {
            player = this@MainActivity.player
            useController = false
        }
        val playerPanel = buildPlayerPanel()

        if (isPortrait) {
            val playerWeight = if (isFileListVisible) 0.53f else 0.75f
            content.addView(playerPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, playerWeight))
            content.addView(buildControlPanel(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.25f))
        } else {
            val playerWeight = if (isFileListVisible) 0.50f else 0.75f
            content.addView(playerPanel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, playerWeight))
            content.addView(buildControlPanel(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.25f))
        }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun buildPlayerPanel(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(playerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            timelineView = EdlTimelineView(this@MainActivity)
            addView(timelineView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)))
            addView(buildTransportRow(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }

    private fun buildTransportRow(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 0, 8, 2)

            transportTimeText = TextView(this@MainActivity).apply {
                text = "00:00 / 00:00"
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(transportTimeText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(transportButton("|<") { seekToStart() })
            addView(transportButton("-1F") { stepOneFrame(-1) })
            addView(transportButton("-5s") { seekRelative(-TRANSPORT_JUMP_MS) })
            playPauseButton = transportButton("Play") { togglePlayback() }
            addView(playPauseButton)
            addView(transportButton("+5s") { seekRelative(TRANSPORT_JUMP_MS) })
            addView(transportButton("+1F") { stepOneFrame(1) })
            addView(transportButton(">|") { seekToEnd() })
            addView(transportButton("⚙") { toast("Player settings are not available yet") })
        }

    private fun buildControlPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 2, 8, 4)
        }

        statusText = TextView(this).apply {
            text = "No video selected"
            textSize = 13f
            setPadding(2, 0, 2, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        panel.addView(statusText)

        fastSeek = SeekBar(this).apply { max = SEEK_MAX }
        fastSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) seekToFastProgress(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isUserFastScrubbing = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekToFastProgress(seekBar.progress)
                isUserFastScrubbing = false
            }
        })
        panel.addView(scrubRow("Fast", fastSeek))

        slowSeek = SeekBar(this).apply {
            max = SLOW_MAX
            progress = SLOW_CENTER
        }
        slowSeek.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) slowAnchorMs = player.currentPosition
            false
        }
        slowSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val offset = (((progress - SLOW_CENTER).toDouble() / SLOW_CENTER.toDouble()) * SLOW_HALF_WINDOW_MS).toLong()
                    player.seekTo(clampPosition(slowAnchorMs + offset))
                    updateTransport()
                    timelineView.invalidate()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isUserSlowScrubbing = true
                slowAnchorMs = player.currentPosition
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isUserSlowScrubbing = false
                seekBar.progress = SLOW_CENTER
            }
        })
        panel.addView(slowScrubRow(slowSeek))

        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(controlRow(
            button("Mark In") { markIn() },
            button("Mark Out") { markOut() },
            button("Clear In/Out") { clearInOut() }
        ))
        controls.addView(controlRow(
            button("Prev Edit Point") { jumpBoundary(-1) },
            button("Next Edit Point") { jumpBoundary(1) },
            button("Next Unmarked") { openNextUnmarkedVideo() }
        ))
        controls.addView(controlRow(button("Remove Range") { removeCurrentRange() }))
        panel.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return panel
    }

    private fun restoreLastDirectory() {
        prefs.getString(KEY_LAST_TREE, null)?.let { loadDirectory(Uri.parse(it)) }
    }

    private fun toggleFileList() {
        isFileListVisible = !isFileListVisible
        buildUi()
        updateHeader()
        updateStatus()
        updateScrubbersFromPlayer()
    }

    private fun fileListButtonText(): String =
        if (isFileListVisible) "Hide list" else "Show list"

    private fun openDirectoryPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_DIRECTORY)
    }

    private fun loadDirectory(uri: Uri) {
        val directory = DocumentFile.fromTreeUri(this, uri)
        if (directory == null || !directory.canRead()) {
            toast("Cannot read selected folder")
            return
        }
        currentDirectory = directory
        currentVideo = null
        pendingInMs = null
        pendingOutMs = null
        videos.clear()
        ranges.clear()
        adapter.notifyDataSetChanged()
        updateHeader()
        updateStatus()
        val generation = ++loadGeneration
        headerText.text = "${directory.name ?: "Folder"}  |  Loading videos..."

        Thread {
            val loaded = queryVideoRows(uri)
            handler.post {
                if (generation != loadGeneration) return@post
                videos.clear()
                videos.addAll(loaded)
                adapter.notifyDataSetChanged()
                updateHeader()
            }
        }.start()
    }

    private fun queryVideoRows(treeUri: Uri): List<VideoRow> {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val videosFound = mutableListOf<Pair<String, DocumentFile>>()
        val edlByName = mutableMapOf<String, DocumentFile>()

        runCatching {
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex).orEmpty()
                    val lowerName = name.lowercase(Locale.US)
                    val mime = cursor.getString(mimeIndex).orEmpty()
                    val isVideo = isVideoName(name) || mime.startsWith("video/")
                    if (!isVideo && lowerName.endsWith(".edl").not()) continue
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
                    val documentFile = DocumentFile.fromSingleUri(this, documentUri) ?: continue
                    if (lowerName.endsWith(".edl")) {
                        edlByName[lowerName] = documentFile
                    } else if (isVideo) {
                        videosFound.add(name to documentFile)
                    }
                }
            }
        }.getOrElse {
            handler.post { toast("Could not scan selected folder") }
        }

        return videosFound.sortedBy { it.first.lowercase(Locale.US) }.map { (name, video) ->
            val edl = edlByName["${baseName(name).lowercase(Locale.US)}.edl"]
            VideoRow(video, edl, 0L, isLocked(video))
        }
    }

    private fun openVideo(row: VideoRow) {
        currentVideo = row
        pendingInMs = null
        pendingOutMs = null
        ranges.clear()
        player.setMediaItem(MediaItem.fromUri(row.videoFile.uri))
        player.prepare()
        player.playWhenReady = true
        updateStatus()
        timelineView.invalidate()
        adapter.notifyDataSetChanged()
        loadRangesForCurrentVideo(row)
    }

    private fun loadRangesForCurrentVideo(row: VideoRow) {
        val generation = ++rangeLoadGeneration
        Thread {
            val loadedRanges = readRanges(row.edlFile)
            handler.post {
                if (generation != rangeLoadGeneration || row != currentVideo) return@post
                ranges.clear()
                ranges.addAll(loadedRanges)
                row.selectedMs = loadedRanges.sumOf { it.durationMs }
                updateHeader()
                updateStatus()
                updateCurrentVideoRow()
                timelineView.invalidate()
            }
        }.start()
    }

    private fun toggleLocked(row: VideoRow) {
        row.locked = !row.locked
        prefs.edit().putBoolean(lockKey(row.videoFile), row.locked).apply()
        if (row == currentVideo) {
            pendingInMs = null
            pendingOutMs = null
            updateStatus()
            timelineView.invalidate()
        }
        updateVideoRow(row)
    }

    private fun isLocked(file: DocumentFile): Boolean =
        prefs.getBoolean(lockKey(file), false)

    private fun lockKey(file: DocumentFile): String =
        "locked:${file.uri}"

    private fun markIn() {
        ensureEditableVideo() ?: return
        pendingInMs = player.currentPosition
        maybeSavePendingRange()
    }

    private fun markOut() {
        ensureEditableVideo() ?: return
        pendingOutMs = player.currentPosition
        maybeSavePendingRange()
    }

    private fun maybeSavePendingRange() {
        val row = ensureEditableVideo() ?: return
        val start = pendingInMs
        val end = pendingOutMs
        if (start == null || end == null) {
            updateStatus()
            timelineView.invalidate()
            return
        }
        if (abs(end - start) < 1L) {
            toast("In and Out are the same")
            return
        }
        ranges.add(EdlRange(min(start, end), max(start, end)))
        normalizeRanges()
        pendingInMs = null
        pendingOutMs = null
        saveRanges(row)
    }

    private fun clearInOut() {
        ensureEditableVideo() ?: return
        pendingInMs = null
        pendingOutMs = null
        updateStatus()
        timelineView.invalidate()
    }

    private fun jumpBoundary(direction: Int) {
        currentVideoOrToast() ?: return
        val boundaries = ranges.flatMap { listOf(it.startMs, it.endMs) }.sorted()
        if (boundaries.isEmpty()) return
        val now = player.currentPosition
        val target = if (direction < 0) {
            boundaries.lastOrNull { it < now - 250 } ?: boundaries.first()
        } else {
            boundaries.firstOrNull { it > now + 250 } ?: boundaries.last()
        }
        player.seekTo(target)
    }

    private fun removeCurrentRange() {
        val row = ensureEditableVideo() ?: return
        val now = player.currentPosition
        val index = ranges.indexOfFirst { now in it.startMs..it.endMs }
            .takeIf { it >= 0 }
            ?: ranges.indexOfFirst { abs(it.startMs - now) <= 1000L || abs(it.endMs - now) <= 1000L }
        if (index < 0) {
            toast("No EDL range here")
            return
        }
        ranges.removeAt(index)
        saveRanges(row)
    }

    private fun openNextUnmarkedVideo() {
        if (videos.isEmpty()) return
        val startIndex = currentVideo?.let { videos.indexOf(it) }?.takeIf { it >= 0 } ?: -1
        val next = (1..videos.size)
            .map { videos[Math.floorMod(startIndex + it, videos.size)] }
            .firstOrNull { it.selectedMs <= 0L && !it.locked }
        if (next == null) {
            toast("No unlocked unmarked videos")
            return
        }
        openVideo(next)
    }

    private fun saveRanges(row: VideoRow) {
        val edlFile = row.edlFile ?: createEdlFile(row) ?: return
        val savedRanges = ranges.toList()
        val text = buildString {
            appendLine("# QuickEDL v1")
            appendLine("# start_ms end_ms start_time end_time")
            savedRanges.forEach { range ->
                appendLine("${range.startMs} ${range.endMs} ${formatTime(range.startMs)} ${formatTime(range.endMs)}")
            }
        }
        row.edlFile = edlFile
        row.selectedMs = savedRanges.sumOf { it.durationMs }
        updateHeader()
        updateStatus()
        updateCurrentVideoRow()
        timelineView.invalidate()
        Thread {
            runCatching {
                contentResolver.openOutputStream(edlFile.uri, "wt")?.bufferedWriter()?.use { it.write(text) }
            }.onFailure {
                handler.post { toast("Could not save ${edlFile.name}") }
            }
        }.start()
    }

    private fun createEdlFile(row: VideoRow): DocumentFile? {
        val directory = currentDirectory ?: return null
        val name = "${baseName(row.videoFile.name.orEmpty())}.edl"
        return directory.findFile(name) ?: directory.createFile("application/octet-stream", name).also {
            if (it == null) toast("Could not create $name")
        }
    }

    private fun readRanges(file: DocumentFile?): List<EdlRange> {
        if (file == null || !file.canRead()) return emptyList()
        return runCatching {
            contentResolver.openInputStream(file.uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).lineSequence().mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                    val parts = trimmed.split(Regex("\\s+"))
                    val start = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                    val end = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                    if (end > start) EdlRange(start, end) else null
                }.toList()
            }.orEmpty()
        }.getOrElse {
            toast("Could not read ${file.name}")
            emptyList()
        }
    }

    private fun normalizeRanges() {
        val sorted = ranges.sortedBy { it.startMs }
        ranges.clear()
        for (range in sorted) {
            val last = ranges.lastOrNull()
            if (last != null && range.startMs <= last.endMs) {
                ranges[ranges.lastIndex] = last.copy(endMs = max(last.endMs, range.endMs))
            } else {
                ranges.add(range)
            }
        }
    }

    private fun updateHeader() {
        val total = videos.sumOf { it.selectedMs }
        val dirName = currentDirectory?.name ?: "No folder"
        headerText.text = "$dirName  |  ${videos.size} videos  |  In total ${formatTime(total)}"
    }

    private fun updateCurrentVideoRow() {
        currentVideo?.let { updateVideoRow(it) }
    }

    private fun updateVideoRow(row: VideoRow) {
        val position = videos.indexOf(row)
        if (position < 0 || !::listView.isInitialized) return
        val first = listView.firstVisiblePosition
        val child = listView.getChildAt(position - first) ?: return
        adapter.getView(position, child, listView)
    }

    private fun updateStatus() {
        val row = currentVideo
        statusText.text = if (row == null) {
            "No video selected"
        } else {
            val pendingIn = pendingInMs?.let { " | pending In ${formatTime(it)}" }.orEmpty()
            val pendingOut = pendingOutMs?.let { " | pending Out ${formatTime(it)}" }.orEmpty()
            val locked = if (row.locked) " | locked" else ""
            "${row.videoFile.name} | ${ranges.size} ranges | ${formatTime(ranges.sumOf { it.durationMs })}$pendingIn$pendingOut$locked"
        }
    }

    private fun updateScrubbersFromPlayer() {
        if (!::fastSeek.isInitialized) return
        updateTransport()
        val duration = player.duration.takeIf { it > 0 } ?: return
        if (!isUserFastScrubbing) {
            fastSeek.progress = ((player.currentPosition.toDouble() / duration.toDouble()) * SEEK_MAX).toInt().coerceIn(0, SEEK_MAX)
        }
        if (!isUserSlowScrubbing) slowSeek.progress = SLOW_CENTER
        timelineView.invalidate()
    }

    private fun seekToFastProgress(progress: Int) {
        val duration = player.duration.takeIf { it > 0 } ?: return
        player.seekTo(((progress.toDouble() / SEEK_MAX.toDouble()) * duration).toLong())
        updateTransport()
        timelineView.invalidate()
    }

    private fun togglePlayback() {
        currentVideoOrToast() ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
        updateTransport()
    }

    private fun seekToStart() {
        currentVideoOrToast() ?: return
        player.seekTo(0L)
        updateScrubbersFromPlayer()
    }

    private fun seekToEnd() {
        currentVideoOrToast() ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        player.seekTo(duration)
        updateScrubbersFromPlayer()
    }

    private fun seekRelative(deltaMs: Long) {
        currentVideoOrToast() ?: return
        player.seekTo(clampPosition(player.currentPosition + deltaMs))
        updateScrubbersFromPlayer()
    }

    private fun updateTransport() {
        if (!::transportTimeText.isInitialized || !::playPauseButton.isInitialized) return
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        transportTimeText.text = "${formatTransportTime(player.currentPosition)} / ${formatTransportTime(duration)}"
        playPauseButton.text = if (player.isPlaying) "Pause" else "Play"
    }

    private fun nextUiRefreshDelayMs(): Long =
        if (player.isPlaying || isUserFastScrubbing || isUserSlowScrubbing) ACTIVE_UI_REFRESH_MS else IDLE_UI_REFRESH_MS

    private fun stepOneFrame(direction: Int) {
        currentVideoOrToast() ?: return
        player.seekTo(clampPosition(player.currentPosition + direction * frameStepMs()))
        slowAnchorMs = player.currentPosition
        if (::slowSeek.isInitialized) slowSeek.progress = SLOW_CENTER
        updateScrubbersFromPlayer()
    }

    private fun frameStepMs(): Long {
        val frameRate = player.videoFormat?.frameRate?.takeIf { it > 0f }
        return frameRate?.let { (1000f / it).toLong().coerceAtLeast(1L) } ?: DEFAULT_FRAME_STEP_MS
    }

    private fun clampPosition(positionMs: Long): Long {
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        return positionMs.coerceIn(0L, duration)
    }

    private fun currentVideoOrToast(): VideoRow? {
        val row = currentVideo
        if (row == null) toast("Select a video first")
        return row
    }

    private fun ensureEditableVideo(): VideoRow? {
        val row = currentVideoOrToast()
        if (row?.locked == true) {
            toast("Clip is locked")
            return null
        }
        return row
    }

    private fun button(text: String, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            textSize = 11f
            setPadding(4, 0, 4, 0)
            setOnClickListener { action() }
        }

    private fun transportButton(text: String, action: () -> Unit): Button =
        button(text, action).apply {
            textSize = 11f
            setPadding(2, 0, 2, 0)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(38)).apply {
                marginStart = 2
                marginEnd = 2
            }
        }

    private fun scrubRow(text: String, seekBar: SeekBar): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                this.text = text
                textSize = 11f
                setPadding(2, 0, 4, 0)
            }, LinearLayout.LayoutParams(dp(38), dp(28)))
            addView(seekBar, LinearLayout.LayoutParams(0, dp(28), 1f))
        }

    private fun slowScrubRow(seekBar: SeekBar): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Slow"
                textSize = 11f
                setPadding(2, 0, 4, 0)
            }, LinearLayout.LayoutParams(dp(38), dp(32)))
            addView(seekBar, LinearLayout.LayoutParams(0, dp(32), 1f))
        }

    private fun controlRow(vararg buttons: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 2, 0, 0)
            buttons.forEach { button ->
                addView(button, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                    marginStart = 4
                    marginEnd = 4
                })
            }
        }

    private fun label(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            setPadding(2, 6, 2, 0)
        }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private inner class EdlTimelineView(context: android.content.Context) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5A5A5A.toInt() }
        private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8E8E8.toInt() }
        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF8A00.toInt() }
        private val rect = RectF()

        init {
            isClickable = true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_DOWN && event.action != MotionEvent.ACTION_MOVE) {
                return true
            }
            val duration = player.duration.takeIf { it > 0 } ?: return true
            val ratio = (event.x / width.toFloat()).coerceIn(0f, 1f)
            player.seekTo((duration * ratio).toLong())
            updateTransport()
            invalidate()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val duration = player.duration.takeIf { it > 0 } ?: return
            val centerY = height / 2f
            val trackHeight = max(4f, height * 0.28f)
            rect.set(0f, centerY - trackHeight / 2f, width.toFloat(), centerY + trackHeight / 2f)
            canvas.drawRoundRect(rect, trackHeight / 2f, trackHeight / 2f, trackPaint)

            val now = player.currentPosition
            ranges.forEach { range ->
                val left = (range.startMs.toFloat() / duration.toFloat()) * width
                val right = (range.endMs.toFloat() / duration.toFloat()) * width
                val paint = if (now in range.startMs..range.endMs) activePaint else selectedPaint
                rect.set(left, centerY - trackHeight / 2f, right.coerceAtLeast(left + 2f), centerY + trackHeight / 2f)
                canvas.drawRoundRect(rect, trackHeight / 2f, trackHeight / 2f, paint)
            }

            val pendingIn = pendingInMs
            val pendingOut = pendingOutMs
            if (pendingIn != null && pendingOut != null) {
                val start = min(pendingIn, pendingOut)
                val end = max(pendingIn, pendingOut)
                val left = (start.toFloat() / duration.toFloat()) * width
                val right = (end.toFloat() / duration.toFloat()) * width
                rect.set(left, centerY - trackHeight / 2f, right.coerceAtLeast(left + 2f), centerY + trackHeight / 2f)
                canvas.drawRoundRect(rect, trackHeight / 2f, trackHeight / 2f, playheadPaint)
            }

            pendingInMs?.let { pending ->
                val x = (pending.toFloat() / duration.toFloat()) * width
                canvas.drawCircle(x.coerceIn(0f, width.toFloat()), centerY, trackHeight, playheadPaint)
            }
            pendingOutMs?.let { pending ->
                val x = (pending.toFloat() / duration.toFloat()) * width
                canvas.drawCircle(x.coerceIn(0f, width.toFloat()), centerY, trackHeight, playheadPaint)
            }

            val playheadX = ((now.toFloat() / duration.toFloat()) * width).coerceIn(0f, width.toFloat())
            canvas.drawRect(playheadX - 2f, 0f, playheadX + 2f, height.toFloat(), playheadPaint)
        }
    }

    private inner class VideoAdapter : ArrayAdapter<VideoRow>(this@MainActivity, android.R.layout.simple_list_item_1, videos) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val rowView = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(4, 6, 8, 6)
                addView(Button(context).apply {
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    textSize = 16f
                    setPadding(0, 0, 0, 0)
                    isFocusable = false
                }, LinearLayout.LayoutParams(dp(42), dp(42)))
                addView(TextView(context).apply {
                    textSize = 14f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            val row = videos[position]
            val lockButton = rowView.getChildAt(0) as Button
            val textView = rowView.getChildAt(1) as TextView
            lockButton.text = if (row.locked) "🔒" else "🔓"
            lockButton.contentDescription = if (row.locked) "Unlock clip" else "Lock clip"
            lockButton.setOnClickListener { toggleLocked(row) }
            val marker = if (row.edlFile != null) "EDL ${formatTime(row.selectedMs)}" else "No EDL"
            val selected = if (row == currentVideo) "> " else ""
            val locked = if (row.locked) " | Locked" else ""
            textView.text = "$selected${row.videoFile.name}\n$marker$locked"
            rowView.setBackgroundColor(if (row == currentVideo) 0xFFE2F0FF.toInt() else 0x00000000)
            textView.setTextColor(if (row == currentVideo) 0xFF111111.toInt() else 0xFF707070.toInt())
            return rowView
        }
    }

    private data class VideoRow(
        val videoFile: DocumentFile,
        var edlFile: DocumentFile?,
        var selectedMs: Long,
        var locked: Boolean
    )

    private data class EdlRange(val startMs: Long, val endMs: Long) {
        val durationMs: Long get() = endMs - startMs
    }

    companion object {
        private const val REQUEST_OPEN_DIRECTORY = 3001
        private const val KEY_LAST_TREE = "last_tree_uri"
        private const val SEEK_MAX = 10_000
        private const val SLOW_CENTER = 1_000
        private const val SLOW_MAX = 2_000
        private const val SLOW_HALF_WINDOW_MS = 2_000L
        private const val DEFAULT_FRAME_STEP_MS = 33L
        private const val TRANSPORT_JUMP_MS = 5_000L
        private const val ACTIVE_UI_REFRESH_MS = 100L
        private const val IDLE_UI_REFRESH_MS = 500L
        private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "mkv", "avi", "webm", "m4v", "ts", "mts", "m2ts", "3gp")

        private fun isVideoName(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
            return ext in VIDEO_EXTENSIONS
        }

        private fun baseName(name: String): String =
            name.substringBeforeLast('.', name)

        private fun formatTime(ms: Long): String {
            val totalSeconds = ms / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            val millis = ms % 1000L
            return "%02d:%02d:%02d.%03d".format(Locale.US, hours, minutes, seconds, millis)
        }

        private fun formatTransportTime(ms: Long): String {
            val safeMs = ms.coerceAtLeast(0L)
            val totalSeconds = safeMs / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0) {
                "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
            } else {
                "%02d:%02d".format(Locale.US, minutes, seconds)
            }
        }
    }
}
