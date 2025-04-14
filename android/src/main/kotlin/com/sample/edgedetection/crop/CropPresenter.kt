package com.sample.edgedetection.crop

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.View
import com.sample.edgedetection.EdgeDetectionHandler
import com.sample.edgedetection.SourceManager
import com.sample.edgedetection.processor.Corners
import com.sample.edgedetection.processor.TAG
import com.sample.edgedetection.processor.cropPicture
import com.sample.edgedetection.processor.enhancePicture
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.File
import java.io.FileOutputStream
import org.opencv.core.Core
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc


class CropPresenter(
    private val iCropView: ICropView.Proxy,
    private val initialBundle: Bundle
) {
    private val picture: Mat? = SourceManager.pic
    private val corners: Corners? = SourceManager.corners
    private var croppedPicture: Mat? = null
    private var enhancedPicture: Bitmap? = null
    private var croppedBitmap: Bitmap? = null
    private var rotateBitmap: Bitmap? = null
    private var rotateBitmapDegree: Int = -90

    fun onViewsReady(paperWidth: Int, paperHeight: Int) {
        iCropView.getPaperRect().onCorners2Crop(corners, picture?.size(), paperWidth, paperHeight)
        val bitmap = Bitmap.createBitmap(
            picture?.width() ?: 1080, picture?.height() ?: 1920, Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(picture, bitmap, true)
        iCropView.getPaper().setImageBitmap(bitmap)
    }

    fun crop() {
        if (picture == null) {
            Log.i(TAG, "picture null?")
            return
        }

        if (croppedBitmap != null) {
            Log.i(TAG, "already cropped")
            return
        }

        Observable.create<Mat> {
            it.onNext(cropPicture(picture, iCropView.getPaperRect().getCorners2Crop()))
        }
            .subscribeOn(Schedulers.computation())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { pc ->
                Log.i(TAG, "cropped picture: $pc")
                croppedPicture = pc
                croppedBitmap =
                    Bitmap.createBitmap(pc.width(), pc.height(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(pc, croppedBitmap)
                iCropView.getCroppedPaper().setImageBitmap(croppedBitmap)
                iCropView.getPaper().visibility = View.GONE
                iCropView.getPaperRect().visibility = View.GONE

                // Automatically apply black-and-white filter after cropping
                enhance() 
            }
    }

 fun enhance() {
    if (croppedBitmap == null) {
        Log.i(TAG, "picture null?")
        return
    }

    val imgToEnhance: Bitmap? = when {
        enhancedPicture != null -> {
            enhancedPicture
        }
        rotateBitmap != null -> {
            rotateBitmap
        }
        else -> {
            croppedBitmap
        }
    }

    // Apply color enhancement (keeping the original colors)
    Observable.create<Bitmap> {
        it.onNext(enhanceWithColor(imgToEnhance))
    }
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe { enhancedBitmap ->
            enhancedPicture = enhancedBitmap
            rotateBitmap = enhancedBitmap
            iCropView.getCroppedPaper().setImageBitmap(enhancedBitmap)
        }
}

private fun enhanceWithColor(inputBitmap: Bitmap?): Bitmap {
    if (inputBitmap == null) return inputBitmap!!

    // Convert Bitmap to Mat (OpenCV format)
    val mat = Mat()
    Utils.bitmapToMat(inputBitmap, mat)

    // Apply contrast and brightness adjustment
    val alpha: Double = 1.25  // Increase contrast (1.0 = no change, > 1.0 = increase contrast)
    val beta: Double = 40.0  // Increase brightness (0 = no change, > 0 = increase brightness)
    mat.convertTo(mat, -1, alpha, beta)

    // Convert the image to HSV color space
    val hsvMat = Mat()
    Imgproc.cvtColor(mat, hsvMat, Imgproc.COLOR_BGR2HSV)

    // Split the image into three channels (Hue, Saturation, and Value)
    val hsvChannels = ArrayList<Mat>(3)
    Core.split(hsvMat, hsvChannels)

    // Increase the saturation (saturation is in the second channel, index 1)
    val saturationFactor: Double = 1.2  // Increase saturation by 50%
    Core.multiply(hsvChannels[1], Scalar(saturationFactor), hsvChannels[1])

    // Merge the channels back together
    Core.merge(hsvChannels, hsvMat)

    // Convert back to RGB color space
    Imgproc.cvtColor(hsvMat, mat, Imgproc.COLOR_HSV2BGR)

    // Convert back to Bitmap
    val enhancedBitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(mat, enhancedBitmap)

    // Recycle Mat and return enhanced Bitmap
    mat.release()
    hsvMat.release()
    return enhancedBitmap
}



    fun reset() {
        if (croppedBitmap == null) {
            Log.i(TAG, "picture null?")
            return
        }
        rotateBitmap = croppedBitmap
        enhancedPicture = croppedBitmap

        iCropView.getCroppedPaper().setImageBitmap(croppedBitmap)
    }

    fun rotate() {
        if (croppedBitmap == null && enhancedPicture == null) {
            Log.i(TAG, "picture null?")
            return
        }

        if (enhancedPicture != null && rotateBitmap == null) {
            rotateBitmap = enhancedPicture
        }

        if (rotateBitmap == null) {
            rotateBitmap = croppedBitmap
        }

        rotateBitmap = rotateBitmap?.rotateInt(rotateBitmapDegree)

        iCropView.getCroppedPaper().setImageBitmap(rotateBitmap)

        enhancedPicture = rotateBitmap
        croppedBitmap = croppedBitmap?.rotateInt(rotateBitmapDegree)
    }

    fun save() {
        val file = File(initialBundle.getString(EdgeDetectionHandler.SAVE_TO) as String)

        val rotatePic = rotateBitmap
        if (rotatePic != null) {
            val outStream = FileOutputStream(file)
            rotatePic.compress(Bitmap.CompressFormat.JPEG, 100, outStream)
            outStream.flush()
            outStream.close()
            rotatePic.recycle()
            Log.i(TAG, "RotateBitmap Saved")
        } else {
            val pic = enhancedPicture

            if (pic != null) {
                val outStream = FileOutputStream(file)
                pic.compress(Bitmap.CompressFormat.JPEG, 100, outStream)
                outStream.flush()
                outStream.close()
                pic.recycle()
                Log.i(TAG, "EnhancedPicture Saved")
            } else {
                val cropPic = croppedBitmap
                if (cropPic != null) {
                    val outStream = FileOutputStream(file)
                    cropPic.compress(Bitmap.CompressFormat.JPEG, 100, outStream)
                    outStream.flush()
                    outStream.close()
                    cropPic.recycle()
                    Log.i(TAG, "CroppedBitmap Saved")
                }
            }
        }
    }

    // Extension function to rotate a bitmap
    private fun Bitmap.rotateInt(degree: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degree.toFloat())
        val scaledBitmap = Bitmap.createScaledBitmap(this, width, height, true)
        return Bitmap.createBitmap(scaledBitmap, 0, 0, scaledBitmap.width, scaledBitmap.height, matrix, true)
    }
}
