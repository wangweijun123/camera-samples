package com.android.example.cameraxbasic.fragments

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.Metadata
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.Navigation
import androidx.window.WindowManager
import com.android.example.cameraxbasic.MainActivity
import com.android.example.cameraxbasic.R
import com.android.example.cameraxbasic.databinding.CameraUiContainerBinding
import com.android.example.cameraxbasic.databinding.FragmentCameraBinding
import com.android.example.cameraxbasic.utils.*
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.TransformationUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CameraFragment : Fragment() {

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private var cameraUiContainerBinding: CameraUiContainerBinding? = null

    private lateinit var outputDirectory: File
    private lateinit var broadcastManager: LocalBroadcastManager

    private var displayId: Int = -1

    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null

    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var windowManager: WindowManager

    private lateinit var cameraExecutor: ExecutorService


    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }


    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                R.id.action_camera_to_permissions
            )
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
        // Shut down our background executor
        cameraExecutor.shutdown()
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        broadcastManager = LocalBroadcastManager.getInstance(view.context)

        //Initialize WindowManager to retrieve display metrics
        windowManager = WindowManager(view.context)

        // Determine the output directory
        outputDirectory = MainActivity.getOutputDirectory(requireContext())

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {

            // Keep track of the display in which this view is attached
            displayId = fragmentCameraBinding.viewFinder.display.displayId

            // Build UI controls
            updateCameraUi()

            // Set up the camera and its use cases
            setUpCamera()
        }
    }


    /** Initialize CameraX, and prepare to bind the camera use cases  */
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(Runnable {

            // CameraProvider
            cameraProvider = cameraProviderFuture.get()

            // Build and bind the camera use cases
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /** Declare and bind preview, capture and analysis use cases */
    private fun bindCameraUseCases() {
        // Get screen metrics used to setup camera for full screen resolution
        val metrics = windowManager.getCurrentWindowMetrics().bounds
        Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

        val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        val rotation = fragmentCameraBinding.viewFinder.display.rotation

        // CameraProvider
        val cameraProvider = cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector
        val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        val targetSize = Size(metrics.width(), metrics.height())
        // Preview
        preview = Preview.Builder()
                // We request aspect ratio but no resolution
//                .setTargetAspectRatio(screenAspectRatio)
            .setTargetResolution(targetSize)
                // Set initial target rotation
                .setTargetRotation(rotation)
                .build()

        // ImageCapture
        imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                // We request aspect ratio but no resolution to match preview config, but letting
                // CameraX optimize for whatever specific resolution best fits our use cases
//                .setTargetAspectRatio(screenAspectRatio)
            .setTargetResolution(targetSize)
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                .setTargetRotation(rotation)
                .build()


        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture)

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    private fun updateCameraUi() {
        cameraUiContainerBinding?.root?.let {
            fragmentCameraBinding.root.removeView(it)
        }
        cameraUiContainerBinding = CameraUiContainerBinding.inflate(
                LayoutInflater.from(requireContext()),
                fragmentCameraBinding.root,
                true
        )
        cameraUiContainerBinding?.cameraCaptureButton?.setOnClickListener {
            imageCapture?.let { imageCapture ->
                // Create output file to hold the image
                val photoFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)
                val metadata = Metadata().apply {
                    isReversedHorizontal = false
                }
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                        .setMetadata(metadata)
                        .build()
                imageCapture.takePicture(
                        outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                        Log.d(TAG, "Photo capture succeeded: $savedUri")
                        cropImage(savedUri)
                    }
                })

                // We can only change the foreground Drawable using API level 23+ API
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    fragmentCameraBinding.root.postDelayed({
                        fragmentCameraBinding.root.foreground = ColorDrawable(Color.WHITE)
                        fragmentCameraBinding.root.postDelayed(
                                { fragmentCameraBinding.root.foreground = null }, ANIMATION_FAST_MILLIS)
                    }, ANIMATION_SLOW_MILLIS)
                }
            }
        }

        cameraUiContainerBinding?.photoViewButton?.setOnClickListener {
            Log.d(TAG,"click photoViewButton")
        }
    }

    fun cropImage(uri: Uri) {
        Thread {
//            Log.d(TAG,"cropImage filePath = $filePath")
            val bitmap = BitmapFactory.decodeFile(uri.path)
            val bitmapRotate = TransformationUtils.rotateImage(bitmap, 90)
            Log.d(TAG,"cropImage 旋转前图片宽高 bitmap.width=${bitmap.width}, bitmap.height=${bitmap.height}")
            Log.d(TAG,"cropImage 旋转后图片宽高 bitmapRotate.width=${bitmapRotate.width}, bitmapRotate.height=${bitmapRotate.height}")
            val screenWidth: Int = ScreenUtils.getScreenWidth(activity)
            val screenHeight: Int = ScreenUtils.getScreenHeight(activity)
            Log.d(TAG, "屏幕的宽高 screenWidth=$screenWidth, screenHeight:$screenHeight")
            val displayManager = ScreenUtils.getDisplayMetrics(activity)
            Log.d(TAG, "屏幕的宽高density=${displayManager.density}, densityDpi=${displayManager.densityDpi}")

            val previewWidth = fragmentCameraBinding.viewFinder.width
            val previewHeight = fragmentCameraBinding.viewFinder.height
            Log.d(TAG,"相机预览大小 previewWidth = ${previewWidth},previewHeight=${previewHeight}")
            val previewLeft = fragmentCameraBinding.viewFinder.left
            val previewTop = fragmentCameraBinding.viewFinder.top
            val previewRight = fragmentCameraBinding.viewFinder.right
            val previewBottom = fragmentCameraBinding.viewFinder.bottom
            Log.d(TAG,"相机预览位置 previewLeft = ${previewLeft},previewTop=${previewTop}, " +
                    " previewRight = ${previewRight},previewBottom=${previewBottom}")
            // view 的位置 比上 预览大小， 按照比例从旋转之后的图片裁剪

            val letfCropIv = fragmentCameraBinding.cropIv.left

            val topCropIv =  fragmentCameraBinding.header.height
            val rightCropIv = fragmentCameraBinding.cropIv.left + fragmentCameraBinding.cropIv.width
            val bottomCropIv = fragmentCameraBinding.header.height + fragmentCameraBinding.cropIv.height
            Log.d(TAG,"高亮图位置 letfCropIv = ${letfCropIv},rightCropIv=${rightCropIv}" +
                    ", topCropIv=${topCropIv}, bottomCropIv=${bottomCropIv}")
            /*计算扫描框坐标点占原图坐标点的比例*/
            val leftProportion = letfCropIv / previewWidth.toFloat()
            val topProportion = topCropIv / previewHeight.toFloat()
            val rightProportion = rightCropIv / previewWidth.toFloat()
            val bottomProportion = bottomCropIv / previewHeight.toFloat()
            Log.d(TAG,"leftProportion = ${leftProportion},rightProportion=${rightProportion}")
            val x = (leftProportion * bitmapRotate.width).toInt()
            val y = (topProportion * bitmapRotate.height).toInt()
            val scropWidth = ((rightProportion - leftProportion) * bitmapRotate.width).toInt()
            val scropHeight = ((bottomProportion - topProportion) * bitmapRotate.height).toInt()
            Log.d(TAG,"x = ${x},y=${y}, scropWidth=${scropWidth}, scropHeight=${scropHeight}")
            val mCropBitmap = Bitmap.createBitmap(bitmapRotate, x,
                y, scropWidth, scropHeight)

            // Create output file to hold the image
            val cropFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)
            Log.d(TAG,"裁剪图片保存地址= ${cropFile.absolutePath}")
            val success =
                ImageUtils.save(mCropBitmap, cropFile.absolutePath, Bitmap.CompressFormat.JPEG)
            Log.d(TAG,"裁剪图片保存成功 ? ${success}")
            activity?.runOnUiThread {
                fragmentCameraBinding.cropIv.setImageBitmap(mCropBitmap)

//                Glide.with(fragmentCameraBinding.cropIv)
//                    .load(uri)
//                    .into(fragmentCameraBinding.cropIv)
            }
        }.start()
    }


    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0

        /** Helper function used to create a timestamped file */
        private fun createFile(baseFolder: File, format: String, extension: String) =
                File(baseFolder, SimpleDateFormat(format, Locale.US)
                        .format(System.currentTimeMillis()) + extension)
    }
}
