package com.android.example.cameraxbasic.fragments

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.Metadata
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.Navigation
import androidx.window.WindowManager
import com.android.example.cameraxbasic.CameraActivity
import com.android.example.cameraxbasic.MainActivity
import com.android.example.cameraxbasic.R
import com.android.example.cameraxbasic.databinding.CameraUiContainerBinding
import com.android.example.cameraxbasic.databinding.FragmentCameraBinding
import com.android.example.cameraxbasic.utils.*
import com.bumptech.glide.load.resource.bitmap.TransformationUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Milliseconds used for UI animations */
const val ANIMATION_FAST_MILLIS = 50L
const val ANIMATION_SLOW_MILLIS = 100L

class CameraFragment : Fragment() {

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private var cameraUiContainerBinding: CameraUiContainerBinding? = null

    private lateinit var outputDirectory: File

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newFixedThreadPool(2)
        windowManager = WindowManager(view.context)
        outputDirectory = CameraActivity.getOutputDirectory(requireContext())
        fragmentCameraBinding.viewFinder.post {
            displayId = fragmentCameraBinding.viewFinder.display.displayId
            updateCameraUi()
            setUpCamera()
        }
//        fragmentCameraBinding.arrowLeft.text = "<<<<<<<<<<<<<<\n<<<<<<<<<<<<<<<<"
        val cameraActivity = activity as CameraActivity
        fragmentCameraBinding.cameraTitle.text = cameraActivity.cameraTitle
        fragmentCameraBinding.cameraDesc.text = cameraActivity.cameraDesc

    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(Runnable {
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases() {
        val metrics = windowManager.getCurrentWindowMetrics().bounds
        val rotation = fragmentCameraBinding.viewFinder.display.rotation
        // CameraProvider
        val cameraProvider = cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")
        val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        val targetSize = Size(metrics.width(), metrics.height())
        preview = Preview.Builder()
            .setTargetResolution(targetSize)
                .setTargetRotation(rotation)
                .build()

        // ImageCapture
        imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(targetSize)
                .setTargetRotation(rotation)
                .build()
        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture)
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
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
        cameraExecutor.execute {
            val bitmap = BitmapFactory.decodeFile(uri.path)
            val bitmapRotate = TransformationUtils.rotateImage(bitmap, 90)
            val previewWidth = fragmentCameraBinding.viewFinder.width
            val previewHeight = fragmentCameraBinding.viewFinder.height

            /* 扫描框区域位置 */
            val letfCropIv = fragmentCameraBinding.cropContainer.left
            val topCropIv =  fragmentCameraBinding.header.height
            val rightCropIv = fragmentCameraBinding.cropContainer.left + fragmentCameraBinding.cropContainer.width
            val bottomCropIv = fragmentCameraBinding.header.height + fragmentCameraBinding.cropContainer.height

            /* 计算扫描框坐标点占原图坐标点的比例 */
            val leftProportion = letfCropIv / previewWidth.toFloat()
            val topProportion = topCropIv / previewHeight.toFloat()
            val rightProportion = rightCropIv / previewWidth.toFloat()
            val bottomProportion = bottomCropIv / previewHeight.toFloat()

            val x = (leftProportion * bitmapRotate.width).toInt()
            val y = (topProportion * bitmapRotate.height).toInt()
            val cropWidth = ((rightProportion - leftProportion) * bitmapRotate.width).toInt()
            val cropHeight = ((bottomProportion - topProportion) * bitmapRotate.height).toInt()

            val mCropBitmap = Bitmap.createBitmap(bitmapRotate, x, y, cropWidth, cropHeight)

            // Create output file to hold the image
            val cropFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)
            Log.d(TAG,"裁剪图片保存地址= ${cropFile.absolutePath}")
            val success =
                ImageUtils.save(mCropBitmap, cropFile.absolutePath, Bitmap.CompressFormat.JPEG)
            Log.d(TAG,"裁剪图片保存成功 ? ${success}")
            activity?.runOnUiThread {
                fragmentCameraBinding.cropIv.setImageBitmap(mCropBitmap)
//                val intent = Intent()
//                intent.putExtra("file_path", cropFile.absolutePath)
//                requireActivity().setResult(Activity.RESULT_OK, intent)
//                requireActivity().finish()
            }
        }
    }

    companion object {
        const val TAG = "CameraXBasic"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"

        /** Helper function used to create a timestamped file */
        private fun createFile(baseFolder: File, format: String, extension: String) =
                File(baseFolder, SimpleDateFormat(format, Locale.US)
                        .format(System.currentTimeMillis()) + extension)
    }
}
