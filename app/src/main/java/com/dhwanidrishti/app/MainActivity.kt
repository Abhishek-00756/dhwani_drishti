package com.dhwanidrishti.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.dhwanidrishti.app.audio.VoiceCommandManager
import com.dhwanidrishti.app.camera.CameraController
import com.dhwanidrishti.app.pipeline.AppMode
import com.dhwanidrishti.app.pipeline.DhwaniPipeline
import com.dhwanidrishti.app.pipeline.PipelineStats
import java.util.Locale
import android.util.Log

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var hintView: TextView
    private lateinit var statsView: TextView
    private lateinit var modeToggle: Button

    private lateinit var calibrationPanel: LinearLayout
    private lateinit var calibrationStepHint: TextView
    private lateinit var calibrationStatus: TextView

    private var cameraController:
            CameraController? = null

    private var pipeline:
            DhwaniPipeline? = null

    private var voiceCommandManager:
            VoiceCommandManager? = null

    @Volatile
    private var pipelineStats:
            PipelineStats? = null

    private val uiHandler =
        Handler(
            Looper.getMainLooper()
        )

    // =========================================================
    // CAMERA PERMISSION
    // =========================================================

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                requestAudioPermission()

            } else {

                showPermissionDeniedDialog()
            }
        }

    // =========================================================
    // AUDIO PERMISSION
    // =========================================================

    private val audioPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                startVisionPipeline()

            } else {

                /*
                 * Camera can still work without voice commands.
                 */
                startVisionPipeline()
            }
        }

    // =========================================================
    // ON CREATE
    // =========================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_main
        )

        previewView =
            findViewById(
                R.id.previewView
            )

        hintView =
            findViewById(
                R.id.hintView
            )

        statsView =
            findViewById(
                R.id.statsView
            )

        previewView.contentDescription =
            getString(
                R.string.preview_content_description
            )

        calibrationPanel =
            findViewById(
                R.id.calibrationPanel
            )

        calibrationStepHint =
            findViewById(
                R.id.calibrationStepHint
            )

        calibrationStatus =
            findViewById(
                R.id.calibrationStatus
            )

        modeToggle =
            findViewById(
                R.id.modeToggle
            )

        findViewById<Button>(
            R.id.btnRecordNear
        ).setOnClickListener {

            recordCalibrationNear()
        }

        findViewById<Button>(
            R.id.btnRecordFar
        ).setOnClickListener {

            recordCalibrationFar()
        }

        findViewById<Button>(
            R.id.btnCalibrationDone
        ).setOnClickListener {

            finishCalibration()
        }

        findViewById<Button>(
            R.id.btnCalibrationSkip
        ).setOnClickListener {

            skipCalibration()
        }

        modeToggle.setOnClickListener {

            cycleMode()
        }

        requestCameraPermission()
    }

    // =========================================================
    // CAMERA PERMISSION
    // =========================================================

    private fun requestCameraPermission() {

        when {

            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) ==
                    PackageManager.PERMISSION_GRANTED -> {

                requestAudioPermission()
            }

            shouldShowRequestPermissionRationale(
                Manifest.permission.CAMERA
            ) -> {

                showPermissionRationaleDialog()
            }

            else -> {

                cameraPermissionLauncher.launch(
                    Manifest.permission.CAMERA
                )
            }
        }
    }

    // =========================================================
    // AUDIO PERMISSION
    // =========================================================

    private fun requestAudioPermission() {

        when {

            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) ==
                    PackageManager.PERMISSION_GRANTED -> {

                startVisionPipeline()
            }

            else -> {

                audioPermissionLauncher.launch(
                    Manifest.permission.RECORD_AUDIO
                )
            }
        }
    }

    // =========================================================
    // PERMISSION DIALOG
    // =========================================================

    private fun showPermissionRationaleDialog() {

        AlertDialog.Builder(this)
            .setTitle(
                R.string.permission_rationale_title
            )
            .setMessage(
                R.string.permission_rationale_message
            )
            .setPositiveButton(
                R.string.permission_rationale_continue
            ) { _, _ ->

                cameraPermissionLauncher.launch(
                    Manifest.permission.CAMERA
                )
            }
            .setNegativeButton(
                android.R.string.cancel,
                null
            )
            .show()
    }

    private fun showPermissionDeniedDialog() {

        AlertDialog.Builder(this)
            .setTitle(
                R.string.permission_denied_title
            )
            .setMessage(
                R.string.permission_denied_message
            )
            .setPositiveButton(
                R.string.permission_retry
            ) { _, _ ->

                cameraPermissionLauncher.launch(
                    Manifest.permission.CAMERA
                )
            }
            .setNegativeButton(
                android.R.string.cancel,
                null
            )
            .show()
    }

    // =========================================================
    // START PIPELINE
    // =========================================================

    private fun startVisionPipeline() {

        if (pipeline != null) {
            return
        }

        val p =
            DhwaniPipeline(

                applicationContext,

                onStats = {
                    pipelineStats = it
                },

                onCalibrationSample = {

                    uiHandler.post {
                        refreshCalibrationStatus()
                    }
                }
            )

        pipeline = p

        // -----------------------------------------------------
        // CAMERA
        // -----------------------------------------------------

        cameraController =
            CameraController(
                this,
                this,
                previewView,
                p::submitFrame
            ).also {
                it.start()
            }

        hintView.visibility =
            View.GONE

        // -----------------------------------------------------
        // CALIBRATION
        // -----------------------------------------------------

        if (!p.calibration.isCalibrated) {

            showCalibrationPanel()
        }

        // -----------------------------------------------------
        // MODE
        // -----------------------------------------------------

        refreshModeLabel()

        // -----------------------------------------------------
        // VOICE COMMANDS
        // -----------------------------------------------------

        startVoiceCommands()

        // -----------------------------------------------------
        // STATS
        // -----------------------------------------------------

        uiHandler.post(
            uiStatsRunnable
        )
    }

    // =========================================================
    // VOICE COMMANDS
    // =========================================================

    private fun startVoiceCommands() {

        val permission =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            )

        Log.d(
            "DHWANI_VOICE",
            "startVoiceCommands() permission = $permission"
        )

        if (permission != PackageManager.PERMISSION_GRANTED) {

            Log.e(
                "DHWANI_VOICE",
                "RECORD_AUDIO permission NOT GRANTED"
            )

            return
        }

        Log.d(
            "DHWANI_VOICE",
            "Starting VoiceCommandManager"
        )

        voiceCommandManager =
            VoiceCommandManager(
                context = this,

                onWhatIsInFront = {

                    Log.d(
                        "DHWANI_VOICE",
                        "VOICE CALLBACK -> answerWhatIsInFront()"
                    )

                    pipeline?.answerWhatIsInFront()
                }

            ).also {

                it.start()
            }
    }
    // =========================================================
    // MODE
    // =========================================================

    private fun cycleMode() {

        val p =
            pipeline
                ?: return

        p.mode =
            when (p.mode) {

                AppMode.SOUNDSCAPE ->
                    AppMode.NARRATED

                AppMode.NARRATED ->
                    AppMode.HYBRID

                AppMode.HYBRID ->
                    AppMode.SOUNDSCAPE
            }

        refreshModeLabel()

        modeToggle.announceForAccessibility(

            when (p.mode) {

                AppMode.SOUNDSCAPE ->
                    getString(
                        R.string.mode_soundscape_announcement
                    )

                AppMode.NARRATED ->
                    getString(
                        R.string.mode_narrated_announcement
                    )

                AppMode.HYBRID ->
                    getString(
                        R.string.mode_hybrid_announcement
                    )
            }
        )
    }

    private fun refreshModeLabel() {

        val p =
            pipeline
                ?: return

        val label =
            when (p.mode) {

                AppMode.SOUNDSCAPE ->
                    getString(
                        R.string.mode_soundscape
                    )

                AppMode.NARRATED ->
                    getString(
                        R.string.mode_narrated
                    )

                AppMode.HYBRID ->
                    getString(
                        R.string.mode_hybrid
                    )
            }

        modeToggle.text =
            getString(
                R.string.mode_toggle_format,
                label
            )
    }

    // =========================================================
    // CALIBRATION
    // =========================================================

    private fun showCalibrationPanel() {

        calibrationPanel.visibility =
            View.VISIBLE

        refreshCalibrationStatus()

        calibrationPanel.announceForAccessibility(

            getString(
                R.string.calibration_intro
            ) +
                    " " +
                    calibrationStepHint.text
        )
    }

    private fun hideCalibrationPanel() {

        calibrationPanel.visibility =
            View.GONE
    }

    private fun refreshCalibrationStatus() {

        val c =
            pipeline?.calibration
                ?: return

        val near =
            if (c.nearRaw.isFinite()) {

                getString(
                    R.string.calibration_recorded
                )

            } else {

                getString(
                    R.string.calibration_pending
                )
            }

        val far =
            if (c.farRaw.isFinite()) {

                getString(
                    R.string.calibration_recorded
                )

            } else {

                getString(
                    R.string.calibration_pending
                )
            }

        calibrationStatus.text =
            getString(
                R.string.calibration_status,
                near,
                far
            )

        calibrationStepHint.text =
            if (!c.nearRaw.isFinite()) {

                getString(
                    R.string.calibration_step_near
                )

            } else {

                getString(
                    R.string.calibration_step_far
                )
            }
    }

    private fun recordCalibrationNear() {

        pipeline?.recordCalibrationNear()

        refreshCalibrationStatus()

        calibrationPanel.announceForAccessibility(
            getString(
                R.string.calibration_record_near_announcement
            )
        )
    }

    private fun recordCalibrationFar() {

        pipeline?.recordCalibrationFar()

        refreshCalibrationStatus()

        calibrationPanel.announceForAccessibility(
            getString(
                R.string.calibration_record_far_announcement
            )
        )
    }

    private fun finishCalibration() {

        hideCalibrationPanel()

        calibrationPanel.announceForAccessibility(
            getString(
                R.string.calibration_done_announcement
            )
        )
    }

    private fun skipCalibration() {

        hideCalibrationPanel()

        calibrationPanel.announceForAccessibility(
            getString(
                R.string.calibration_skip_announcement
            )
        )
    }

    // =========================================================
    // UI STATS
    // =========================================================

    private var lastFrames =
        0L

    private var lastUpdateNs =
        System.nanoTime()

    private val uiStatsRunnable =
        object : Runnable {

            override fun run() {

                val now =
                    System.nanoTime()

                val dtSeconds =
                    (
                            now - lastUpdateNs
                            ) / 1_000_000_000.0

                val stats =
                    pipelineStats

                if (
                    dtSeconds > 0 &&
                    stats != null
                ) {

                    val fps =
                        (
                                stats.frames -
                                        lastFrames
                                ) / dtSeconds

                    statsView.text =
                        getString(

                            R.string.stats_format,

                            String.format(
                                Locale.US,
                                "%.1f",
                                fps
                            ),

                            String.format(
                                Locale.US,
                                "%.1f",
                                stats.inferenceMs
                            ),

                            String.format(
                                Locale.US,
                                "%.1f",
                                stats.totalMs
                            ),

                            stats.objectsTracked.toString()
                        )

                    lastFrames =
                        stats.frames
                }

                lastUpdateNs =
                    now

                uiHandler.postDelayed(
                    this,
                    500
                )
            }
        }

    // =========================================================
    // DESTROY
    // =========================================================

    override fun onDestroy() {

        uiHandler.removeCallbacks(
            uiStatsRunnable
        )

        voiceCommandManager?.stop()

        cameraController?.stop()

        pipeline?.stop()

        cameraController = null

        pipeline = null

        voiceCommandManager = null

        super.onDestroy()
    }
}