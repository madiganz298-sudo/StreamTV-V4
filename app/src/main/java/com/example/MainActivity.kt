package com.example

import android.app.Dialog
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.C
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private val playlistUrl = "https://rizkyevory.github.io/index.m3u"
    private val okHttpClient = OkHttpClient()

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var isFullscreen = false
    private var isMuted = false
    private var playerRetryCount = 0
    private var isLeftPanelVisible = true
    private var isRightPanelVisible = false

    private var allChannels = listOf<Channel>()
    private var allGroups = listOf<String>()
    private var activeGroup = ""
    private var activeGroupChannels = listOf<Channel>()
    private var activeChannel: Channel? = null
    private var currentChannelIndex = -1

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var channelAdapter: ChannelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_main)

        setupUI()
        fetchPlaylistWithRetry()
    }

    private fun setupUI() {
        // Set up Category Group adapter
        val rvGroups = findViewById<RecyclerView>(R.id.rvGroups)
        rvGroups.layoutManager = LinearLayoutManager(this)
        groupAdapter = GroupAdapter(emptyList()) { selectedGroup, index ->
            selectGroup(selectedGroup)
            // Open the channel overlay list panel
            setRightPanelVisibility(true)
        }
        rvGroups.adapter = groupAdapter

        // Set up Channel List adapter for the right-side overlay
        val rvChannelsOverlay = findViewById<RecyclerView>(R.id.rvChannelsOverlay)
        rvChannelsOverlay.layoutManager = LinearLayoutManager(this)
        channelAdapter = ChannelAdapter(emptyList()) { channel, index ->
            playChannelAt(index)
        }
        rvChannelsOverlay.adapter = channelAdapter

        // Set up search bar
        val etSearch = findViewById<EditText>(R.id.etSearch)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterChannels(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Reconnect Button click listener
        findViewById<Button>(R.id.btnReconnect).setOnClickListener {
            findViewById<TextView>(R.id.tvErrorStatus).visibility = View.GONE
            findViewById<Button>(R.id.btnReconnect).visibility = View.GONE
            findViewById<TextView>(R.id.tvLoadingStatus).text = getString(R.string.loading_channels)
            findViewById<TextView>(R.id.tvLoadingStatus).visibility = View.VISIBLE
            fetchPlaylistWithRetry()
        }

        // Toggle buttons click listeners
        findViewById<View>(R.id.btnToggleLeftPanel).setOnClickListener {
            toggleLeftPanel()
        }
        findViewById<View>(R.id.btnToggleRightPanel).setOnClickListener {
            toggleRightPanel()
        }

        // Swipe Gesture Detector for the main layout and player view
        val gestureDetector = android.view.GestureDetector(this, SwipeGestureListener())
        val mainTouchListener = View.OnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
        findViewById<View>(R.id.mainContent).setOnTouchListener(mainTouchListener)

        findViewById<View>(R.id.tvPlayer).setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun fetchPlaylistWithRetry() {
        lifecycleScope.launch(Dispatchers.IO) {
            var success = false
            var content = ""
            for (i in 1..3) {
                try {
                    val request = Request.Builder()
                        .url(playlistUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36")
                        .build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            content = response.body?.string() ?: ""
                            if (content.isNotEmpty()) {
                                success = true
                                break
                            }
                        }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    // Cache to SharedPreferences
                    val prefs = getSharedPreferences("m4di_tv_prefs", MODE_PRIVATE)
                    prefs.edit().putString("cached_m3u", content).apply()
                    
                    setupChannels(content)
                } else {
                    // Try offline cache fallback
                    val prefs = getSharedPreferences("m4di_tv_prefs", MODE_PRIVATE)
                    val cached = prefs.getString("cached_m3u", "") ?: ""
                    if (cached.isNotEmpty()) {
                        Toast.makeText(this@MainActivity, "Memutar dari cache offline", Toast.LENGTH_SHORT).show()
                        setupChannels(cached)
                    } else {
                        showSplashError()
                    }
                }
            }
        }
    }

    private fun showSplashError() {
        findViewById<TextView>(R.id.tvLoadingStatus).visibility = View.GONE
        findViewById<TextView>(R.id.tvErrorStatus).visibility = View.VISIBLE
        findViewById<Button>(R.id.btnReconnect).visibility = View.VISIBLE
    }

    private fun setupChannels(m3uContent: String) {
        allChannels = M3UParser.parse(m3uContent)
        if (allChannels.isEmpty()) {
            showSplashError()
            return
        }

        // Group channels by group attribute
        allGroups = allChannels.map { it.group }.distinct().sorted()
        groupAdapter.updateData(allGroups)

        // Hide splash screen, show main content
        findViewById<View>(R.id.splashScreen).visibility = View.GONE
        findViewById<View>(R.id.mainContent).visibility = View.VISIBLE

        setupPlayer()

        // Select and play the first category group
        if (allGroups.isNotEmpty()) {
            selectGroup(allGroups[0])
        }
    }

    private fun selectGroup(groupName: String) {
        activeGroup = groupName
        activeGroupChannels = allChannels.filter { it.group == groupName }

        // Update category list visual selection
        val groupIndex = allGroups.indexOf(groupName)
        if (groupIndex != -1) {
            groupAdapter.setSelection(groupIndex)
        }

        // Refresh overlay channel list
        channelAdapter.updateData(activeGroupChannels)

        // Clear search text
        findViewById<EditText>(R.id.etSearch).setText("")

        // Play first channel in group
        if (activeGroupChannels.isNotEmpty()) {
            playChannelAt(0)
        }
    }

    private fun playChannelAt(index: Int) {
        if (index in activeGroupChannels.indices) {
            currentChannelIndex = index
            channelAdapter.setSelection(index)

            val channel = activeGroupChannels[index]
            playChannel(channel)

            // Scroll overlay list to position
            val rvOverlay = findViewById<RecyclerView>(R.id.rvChannelsOverlay)
            rvOverlay?.scrollToPosition(index)
        }
    }

    private fun playChannel(channel: Channel) {
        activeChannel = channel

        // Update control bar titles
        val tvChannelTop = findViewById<TextView>(R.id.exo_channel_top)
        val tvChannelBottom = findViewById<TextView>(R.id.exo_channel)
        tvChannelTop?.text = channel.name
        tvChannelBottom?.text = channel.name

        player?.let { p ->
            val mediaItem = MediaItem.fromUri(channel.streamUrl)
            p.setMediaItem(mediaItem)
            p.prepare()
            p.playWhenReady = true
        }
    }

    private fun playNextChannel() {
        if (activeGroupChannels.isEmpty()) return
        var nextIndex = currentChannelIndex + 1
        if (nextIndex >= activeGroupChannels.size) {
            nextIndex = 0
        }
        playChannelAt(nextIndex)
    }

    private fun playPrevChannel() {
        if (activeGroupChannels.isEmpty()) return
        var prevIndex = currentChannelIndex - 1
        if (prevIndex < 0) {
            prevIndex = activeGroupChannels.size - 1
        }
        playChannelAt(prevIndex)
    }

    private fun filterChannels(query: String) {
        val filtered = if (query.isEmpty()) {
            activeGroupChannels
        } else {
            activeGroupChannels.filter { it.name.contains(query, ignoreCase = true) }
        }
        channelAdapter.updateData(filtered)
    }

    private fun setupPlayer() {
        val ts = DefaultTrackSelector(this)
        trackSelector = ts
        player = ExoPlayer.Builder(this)
            .setTrackSelector(ts)
            .setMediaSourceFactory(DefaultMediaSourceFactory(
                OkHttpDataSource.Factory(okHttpClient).setDefaultRequestProperties(mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36"
                ))
            ))
            .build()

        val playerView = findViewById<PlayerView>(R.id.tvPlayer)
        playerView.player = player

        // Hook listener to display buffering and trigger retries
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val pbLoading = findViewById<ProgressBar>(R.id.pbPlayerLoading)
                when (state) {
                    Player.STATE_BUFFERING -> {
                        pbLoading.visibility = View.VISIBLE
                    }
                    Player.STATE_READY -> {
                        pbLoading.visibility = View.GONE
                        playerRetryCount = 0
                        updatePlayerControls()
                    }
                    else -> {
                        pbLoading.visibility = View.GONE
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val pbLoading = findViewById<ProgressBar>(R.id.pbPlayerLoading)
                pbLoading.visibility = View.GONE
                handlePlayerError()
            }
        })

        // Bind control buttons
        bindControlListeners()
    }

    private fun bindControlListeners() {
        val playerView = findViewById<PlayerView>(R.id.tvPlayer)

        // Wait for player view controls to inflate, then bind listeners
        playerView.post {
            val btnFullscreen = playerView.findViewById<ImageView>(R.id.exo_fullscreen_icon)
            btnFullscreen?.setOnClickListener {
                toggleFullscreen()
            }

            val btnSound = playerView.findViewById<ImageView>(R.id.exo_sound)
            btnSound?.setOnClickListener {
                toggleMute()
            }

            val btnTrack = playerView.findViewById<ImageView>(R.id.exo_track_selector)
            btnTrack?.setOnClickListener {
                showTrackSelector()
            }

            val btnMenu = playerView.findViewById<ImageView>(R.id.exo_tg)
            btnMenu?.setOnClickListener {
                showOptionsDialog()
            }
        }
    }

    private fun updatePlayerControls() {
        val playerView = findViewById<PlayerView>(R.id.tvPlayer)
        val tvChannelBottom = playerView.findViewById<TextView>(R.id.exo_channel)
        tvChannelBottom?.text = activeChannel?.name ?: "M4DI~UciH4-TV"

        val ivSound = playerView.findViewById<ImageView>(R.id.exo_sound)
        if (isMuted) {
            ivSound?.setImageResource(R.drawable.ic_volume_off)
        } else {
            ivSound?.setImageResource(R.drawable.ic_volume_up)
        }

        val ivFullscreen = playerView.findViewById<ImageView>(R.id.exo_fullscreen_icon)
        if (isFullscreen) {
            ivFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
        } else {
            ivFullscreen?.setImageResource(R.drawable.ic_fullscreen)
        }
    }

    private fun handlePlayerError() {
        if (playerRetryCount < 3) {
            playerRetryCount++
            Toast.makeText(this, "Koneksi terputus, mencoba memutar kembali ($playerRetryCount/3)...", Toast.LENGTH_SHORT).show()
            player?.prepare()
            player?.playWhenReady = true
        } else {
            playerRetryCount = 0
            Toast.makeText(this, "Gagal memutar channel: Hubungan server terputus!", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val leftPanel = findViewById<View>(R.id.leftPanel)
        val rightPlayerArea = findViewById<View>(R.id.rightPlayerArea)
        val playerView = findViewById<PlayerView>(R.id.tvPlayer)
        val ivFullscreen = playerView.findViewById<ImageView>(R.id.exo_fullscreen_icon)

        val params = rightPlayerArea.layoutParams as ConstraintLayout.LayoutParams
        if (isFullscreen) {
            leftPanel.visibility = View.GONE
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            ivFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)

            // Hide status bar and system UI for immersive view
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                val controller = window.insetsController
                controller?.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
        } else {
            leftPanel.visibility = View.VISIBLE
            params.startToStart = ConstraintLayout.LayoutParams.UNSET
            params.startToEnd = R.id.leftPanel
            ivFullscreen?.setImageResource(R.drawable.ic_fullscreen)

            // Show status bar and system UI
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(true)
                val controller = window.insetsController
                controller?.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
        rightPlayerArea.layoutParams = params
    }

    private fun toggleMute() {
        isMuted = !isMuted
        val playerView = findViewById<PlayerView>(R.id.tvPlayer)
        val ivSound = playerView.findViewById<ImageView>(R.id.exo_sound)
        if (isMuted) {
            player?.volume = 0f
            ivSound?.setImageResource(R.drawable.ic_volume_off)
        } else {
            player?.volume = 1f
            ivSound?.setImageResource(R.drawable.ic_volume_up)
        }
    }

    private fun showTrackSelector() {
        player?.let { p ->
            val trackGroups = p.currentTracks
            val videoTracks = mutableListOf<String>()
            val trackIndices = mutableListOf<Pair<Int, Int>>() // Pair of GroupIndex, TrackIndex

            var idx = 0
            for (group in trackGroups.groups) {
                if (group.type == C.TRACK_TYPE_VIDEO) {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        val resolution = "${format.width}x${format.height}"
                        val bitrate = if (format.bitrate != androidx.media3.common.Format.NO_VALUE) "${format.bitrate / 1000}kbps" else ""
                        val label = "Resolution: $resolution $bitrate".trim()
                        videoTracks.add(label)
                        trackIndices.add(Pair(idx, i))
                    }
                }
                idx++
            }

            if (videoTracks.isEmpty()) {
                Toast.makeText(this, "Tidak ada pilihan track resolusi", Toast.LENGTH_SHORT).show()
                return
            }

            val builder = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            builder.setTitle("Ganti Resolusi")
            builder.setItems(videoTracks.toTypedArray()) { dialog, which ->
                val selection = trackIndices[which]
                val parameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(
                            trackGroups.groups[selection.first].mediaTrackGroup,
                            selection.second
                        )
                    )
                    .build()
                p.trackSelectionParameters = parameters
                Toast.makeText(this, "Memutar track: ${videoTracks[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            builder.show()
        }
    }

    private fun showOptionsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_menu)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val lvOptions = dialog.findViewById<ListView>(R.id.lvMenuOptions)
        val optionsList = listOf(
            "Ganti Room / Country",
            "Ganti Resolusi / Track",
            if (isFullscreen) "Keluar Fullscreen" else "Masuk Fullscreen",
            "Dukung Developer",
            "Keluar"
        )

        val customAdapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
            optionsList
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val tv = view.findViewById<TextView>(android.R.id.text1)
                tv.setTextColor(getColor(R.color.text_primary))
                tv.textSize = 16f
                tv.setPadding(16, 16, 16, 16)
                return view
            }
        }

        lvOptions.adapter = customAdapter

        lvOptions.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            when (position) {
                0 -> showGroupSelectionDialog()
                1 -> showTrackSelector()
                2 -> toggleFullscreen()
                3 -> showDeveloperSupportDialog()
                4 -> confirmExit()
            }
        }

        dialog.show()
    }

    private fun showGroupSelectionDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_group)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val rvDialogGroups = dialog.findViewById<RecyclerView>(R.id.rvDialogGroups)
        rvDialogGroups.layoutManager = LinearLayoutManager(this)

        val dialogGroupAdapter = GroupAdapter(allGroups) { selectedGroup, index ->
            dialog.dismiss()
            selectGroup(selectedGroup)
        }
        rvDialogGroups.adapter = dialogGroupAdapter

        val currentGroupIndex = allGroups.indexOf(activeGroup)
        if (currentGroupIndex != -1) {
            dialogGroupAdapter.setSelection(currentGroupIndex)
        }

        dialog.show()
    }

    private fun showDeveloperSupportDialog() {
        val builder = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
        builder.setTitle("Dukung Developer")
        builder.setMessage("Terima kasih telah menggunakan M4DI~UciH4-TV!\nAplikasi ini dikembangkan oleh M4DI UciH4 untuk memberikan pengalaman nonton streaming TV gratis dan lancar.\n\nKlik OK untuk memberi bintang 5!")
        builder.setPositiveButton("OK") { d, _ ->
            d.dismiss()
        }
        builder.setNegativeButton("Kembali") { d, _ -> d.dismiss() }
        builder.show()
    }

    private fun confirmExit() {
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Keluar Aplikasi")
            .setMessage("Apakah Anda yakin ingin keluar dari M4DI~UciH4-TV?")
            .setPositiveButton("Ya") { _, _ ->
                finishAndRemoveTask()
            }
            .setNegativeButton("Tidak", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (isRightPanelVisible) {
                    setRightPanelVisibility(false)
                    return true
                } else {
                    confirmExit()
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                showOptionsDialog()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                playPrevChannel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                playNextChannel()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onUserLeaveHint() {
        @Suppress("DEPRECATION")
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val isPlaying = player?.isPlaying ?: false
            if (isPlaying) {
                val aspectRatio = android.util.Rational(16, 9)
                val params = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .build()
                enterPictureInPictureMode(params)
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val tvPlayer = findViewById<PlayerView>(R.id.tvPlayer)

        if (isInPictureInPictureMode) {
            setLeftPanelVisibility(false)
            setRightPanelVisibility(false)
            tvPlayer.useController = false
        } else {
            if (!isFullscreen) {
                setLeftPanelVisibility(true)
            }
            tvPlayer.useController = true
        }
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    private fun toggleLeftPanel() {
        setLeftPanelVisibility(!isLeftPanelVisible)
    }

    private fun setLeftPanelVisibility(visible: Boolean) {
        isLeftPanelVisible = visible
        val leftPanel = findViewById<View>(R.id.leftPanel)
        val rightPlayerArea = findViewById<View>(R.id.rightPlayerArea)
        val btnToggleLeft = findViewById<ImageView>(R.id.btnToggleLeftPanel)

        val mainContent = findViewById<ViewGroup>(R.id.mainContent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            android.transition.TransitionManager.beginDelayedTransition(mainContent)
        }

        val params = rightPlayerArea.layoutParams as ConstraintLayout.LayoutParams
        if (visible) {
            leftPanel.visibility = View.VISIBLE
            params.startToStart = ConstraintLayout.LayoutParams.UNSET
            params.startToEnd = R.id.leftPanel
            btnToggleLeft?.setImageResource(R.drawable.ic_chevron_left)
        } else {
            leftPanel.visibility = View.GONE
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            btnToggleLeft?.setImageResource(R.drawable.ic_chevron_right)
        }
        rightPlayerArea.layoutParams = params
    }

    private fun toggleRightPanel() {
        setRightPanelVisibility(!isRightPanelVisible)
    }

    private fun setRightPanelVisibility(visible: Boolean) {
        isRightPanelVisible = visible
        val rightOverlay = findViewById<View>(R.id.channelsOverlayLayout)
        val btnToggleRight = findViewById<ImageView>(R.id.btnToggleRightPanel)

        val mainContent = findViewById<ViewGroup>(R.id.mainContent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            android.transition.TransitionManager.beginDelayedTransition(mainContent)
        }

        if (visible) {
            rightOverlay.visibility = View.VISIBLE
            btnToggleRight?.setImageResource(R.drawable.ic_chevron_right)
        } else {
            rightOverlay.visibility = View.GONE
            btnToggleRight?.setImageResource(R.drawable.ic_chevron_left)
        }
    }

    private inner class SwipeGestureListener : android.view.GestureDetector.SimpleOnGestureListener() {
        private val SWIPE_THRESHOLD = 100
        private val SWIPE_VELOCITY_THRESHOLD = 100

        override fun onDown(e: android.view.MotionEvent): Boolean {
            return true
        }

        override fun onFling(
            e1: android.view.MotionEvent?,
            e2: android.view.MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 == null) return false
            val diffX = e2.x - e1.x
            val diffY = e2.y - e1.y
            if (Math.abs(diffX) > Math.abs(diffY)) {
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        onSwipeRight()
                    } else {
                        onSwipeLeft()
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun onSwipeRight() {
        if (isRightPanelVisible) {
            setRightPanelVisibility(false)
        } else if (!isLeftPanelVisible) {
            setLeftPanelVisibility(true)
        }
    }

    private fun onSwipeLeft() {
        if (isLeftPanelVisible) {
            setLeftPanelVisibility(false)
        } else if (!isRightPanelVisible) {
            setRightPanelVisibility(true)
        }
    }
}
