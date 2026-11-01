package com.kozeki.mediaforge

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import coil.load
import coil.decode.VideoFrameDecoder
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.kozeki.mediaforge.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedMediaUri: Uri? = null
    private var cachedInputFile: File? = null
    private var convertedOutputFile: File? = null
    private var isVideo = false
    private var durationMs = 0

    private var trimStartSec = 0f
    private var trimEndSec = 0f

    private val handler = Handler(Looper.getMainLooper())
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (binding.videoView.isPlaying) {
                val current = binding.videoView.currentPosition
                binding.seekBarVideo.progress = current
                binding.txtCurrentTime.text = formatSec(current / 1000f)
            }
            handler.postDelayed(this, 100)
        }
    }

    private val mediaPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            handleSelectedMedia(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        binding.cardPickMedia.setOnClickListener { openPicker() }
        binding.btnPick.setOnClickListener { openPicker() }

        binding.chipGroupPresets.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.contains(R.id.chipAvatar)) {
                binding.cropOverlay.setBackgroundResource(R.drawable.crop_circle_guide)
                binding.cropOverlay.layoutParams.width = dpToPx(200)
                binding.cropOverlay.layoutParams.height = dpToPx(200)
                binding.cropOverlay.visibility = View.VISIBLE
            } else if (checkedIds.contains(R.id.chipBackground)) {
                binding.cropOverlay.setBackgroundResource(R.drawable.crop_circle_guide)
                binding.cropOverlay.layoutParams.width = dpToPx(135)
                binding.cropOverlay.layoutParams.height = dpToPx(240)
                binding.cropOverlay.visibility = View.VISIBLE
            } else {
                binding.cropOverlay.visibility = View.GONE
            }
            binding.cropOverlay.requestLayout()
        }

        binding.seekBarVideo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.videoView.seekTo(progress)
                    binding.txtCurrentTime.text = formatSec(progress / 1000f)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.btnMarkStart.setOnClickListener {
            val curr = binding.videoView.currentPosition / 1000f
            trimStartSec = curr
            binding.btnMarkStart.text = "Start [${formatSec(trimStartSec)}]"
        }

        binding.btnMarkEnd.setOnClickListener {
            val curr = binding.videoView.currentPosition / 1000f
            trimEndSec = curr
            binding.btnMarkEnd.text = "End [${formatSec(trimEndSec)}]"
        }

        binding.btnResetTrim.setOnClickListener {
            trimStartSec = 0f
            trimEndSec = durationMs / 1000f
            binding.btnMarkStart.text = "Start [00:00.0]"
            binding.btnMarkEnd.text = "End [${formatSec(trimEndSec)}]"
        }

        binding.btnConvert.setOnClickListener {
            startConversion()
        }

        binding.btnSaveGallery.setOnClickListener {
            saveToDownloads()
        }

        binding.btnShare.setOnClickListener {
            shareOutput()
        }
    }

    private fun openPicker() {
        mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }

    private fun handleSelectedMedia(uri: Uri) {
        selectedMediaUri = uri
        val mime = contentResolver.getType(uri) ?: ""
        isVideo = mime.startsWith("video/") || uri.toString().contains("video")

        binding.cardResult.visibility = View.GONE
        binding.txtMediaTitle.text = uri.lastPathSegment ?: "Selected Media"
        binding.txtMediaSubtitle.text = if (isVideo) "Video File (${mime})" else "Image File (${mime})"

        // Copy to cache file
        Thread {
            try {
                val cacheFile = File(cacheDir, "input_media_${System.currentTimeMillis()}.${if (isVideo) "mp4" else "jpg"}")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
                cachedInputFile = cacheFile

                runOnUiThread {
                    binding.cardPreview.visibility = View.VISIBLE
                    if (isVideo) {
                        binding.videoView.visibility = View.VISIBLE
                        binding.imageView.visibility = View.GONE
                        binding.layoutTrim.visibility = View.VISIBLE

                        binding.videoView.setVideoPath(cacheFile.absolutePath)
                        binding.videoView.setOnPreparedListener { mp ->
                            durationMs = mp.duration
                            trimStartSec = 0f
                            trimEndSec = (durationMs / 1000f).coerceAtMost(6f)
                            binding.seekBarVideo.max = durationMs
                            binding.txtDuration.text = formatSec(durationMs / 1000f)
                            binding.btnMarkEnd.text = "End [${formatSec(trimEndSec)}]"
                            mp.isLooping = true
                            binding.videoView.start()
                            handler.post(updateProgressRunnable)
                        }
                    } else {
                        binding.videoView.visibility = View.GONE
                        binding.imageView.visibility = View.VISIBLE
                        binding.layoutTrim.visibility = View.GONE
                        binding.imageView.load(cacheFile)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to load file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun startConversion() {
        val input = cachedInputFile
        if (input == null || !input.exists()) {
            Toast.makeText(this, "Please select a media file first", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnConvert.isEnabled = false
        binding.layoutProgress.visibility = View.VISIBLE
        binding.cardResult.visibility = View.GONE

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val isAvatar = binding.chipAvatar.isChecked
        val isBackground = binding.chipBackground.isChecked
        val isGif = binding.chipGif.isChecked
        val isMp4 = binding.chipMp4.isChecked

        val format = when {
            isGif -> "gif"
            isMp4 -> "mp4"
            else -> "webp"
        }

        val outputFile = File(cacheDir, "forge_${timeStamp}.$format")
        convertedOutputFile = outputFile

        // Build FFmpeg command
        val cmdList = mutableListOf<String>()
        cmdList.add("-y")

        if (isVideo && trimEndSec > 0f) {
            cmdList.add("-ss")
            cmdList.add(trimStartSec.toString())
            cmdList.add("-to")
            cmdList.add(trimEndSec.toString())
        }

        cmdList.add("-i")
        cmdList.add(input.absolutePath)

        val vf = when {
            isAvatar -> "crop=min(iw\\,ih):min(iw\\,ih),scale=400:400:flags=lanczos,fps=30"
            isBackground -> "crop=min(iw\\,ih*9/16):min(ih\\,iw*16/9),scale=720:1280:flags=lanczos,fps=30"
            isGif -> "crop=min(iw\\,ih):min(iw\\,ih),scale=360:360:flags=lanczos,fps=24,split[s0][s1];[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=5"
            isMp4 -> "scale=-2:720,fps=30"
            else -> "scale=-2:720"
        }

        cmdList.add("-vf")
        cmdList.add(vf)

        if (format == "webp") {
            cmdList.add("-vcodec")
            cmdList.add("libwebp")
            cmdList.add("-lossless")
            cmdList.add("0")
            cmdList.add("-compression_level")
            cmdList.add("4")
            cmdList.add("-q:v")
            cmdList.add("75")
            cmdList.add("-loop")
            cmdList.add("0")
        } else if (format == "gif") {
            cmdList.add("-loop")
            cmdList.add("0")
        } else if (format == "mp4") {
            cmdList.add("-c:v")
            cmdList.add("libx264")
            cmdList.add("-preset")
            cmdList.add("fast")
            cmdList.add("-crf")
            cmdList.add("24")
            cmdList.add("-pix_fmt")
            cmdList.add("yuv420p")
            cmdList.add("-c:a")
            cmdList.add("aac")
            cmdList.add("-b:a")
            cmdList.add("128k")
            cmdList.add("-movflags")
            cmdList.add("+faststart")
        }

        cmdList.add(outputFile.absolutePath)

        val cmdStr = cmdList.toTypedArray().joinToString(" ") { if (it.contains(" ") || it.contains(";")) "\"$it\"" else it }
        val startTime = System.currentTimeMillis()

        FFmpegKit.executeAsync(cmdStr) { session ->
            val returnCode = session.returnCode
            val elapsed = (System.currentTimeMillis() - startTime) / 1000f

            runOnUiThread {
                binding.btnConvert.isEnabled = true
                binding.layoutProgress.visibility = View.GONE

                if (ReturnCode.isSuccess(returnCode)) {
                    val sizeKb = outputFile.length() / 1024
                    binding.txtResultDetails.text = "Generated ${outputFile.name} (${sizeKb} KB in ${String.format(Locale.US, "%.1f", elapsed)}s)"
                    binding.cardResult.visibility = View.VISIBLE
                    Toast.makeText(this, "Success! Ready to save to gallery.", Toast.LENGTH_SHORT).show()
                } else {
                    val failLog = session.failStackTrace ?: "Unknown error"
                    Toast.makeText(this, "Conversion failed: $failLog", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveToDownloads() {
        val file = convertedOutputFile ?: return
        if (!file.exists()) return

        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (file.name.endsWith(".mp4")) "video/mp4" else "image/${file.extension}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val collection = if (file.name.endsWith(".mp4")) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val uri = contentResolver.insert(collection, contentValues)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, contentValues, null, null)
                }
                Toast.makeText(this, "Saved to Downloads! Tavo can pick it now.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Save error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareOutput() {
        val file = convertedOutputFile ?: return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.name.endsWith(".mp4")) "video/mp4" else "image/*"
            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
        }
        startActivity(Intent.createChooser(shareIntent, "Share Media"))
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun formatSec(seconds: Float): String {
        val mins = (seconds / 60).toInt()
        val secs = seconds % 60
        return String.format(Locale.US, "%02d:%04.1f", mins, secs)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateProgressRunnable)
    }
}
