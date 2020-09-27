package com.example.camera_segmentation_app;

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel


public class ImageSegmenter(private val activity: Activity) {

    companion object {
        const val TAG: String = "ImageSegmenter"
        /** Dimensions of inputs.  */
        const val DIM_BATCH_SIZE = 1

        const val DIM_PIXEL_SIZE = 3
    }

    val currentModel: Model
        get() = modelList[currentModelIndex]

    private var intValues = IntArray(0)
    private var outFrame = IntArray(0)

    private val tfliteOptions = Interpreter.Options()

    private var tfliteModel: MappedByteBuffer? = null
    private val modelList = arrayOf(
            Model(
                    "shufflenetv2_basic_cityscapes_225x225",
                    Model.Dataset.CITYSCAPES,
                    225, 225, 225, 225
            ),
            Model(
                    "shufflenetv2_dpc_cityscapes_225x225", Model.Dataset.CITYSCAPES,
                    225, 225, 225, 225
            ),
            Model(
                    "voc_trainaug", Model.Dataset.PASCAL,
                    225, 225, 225, 225
            ),
            Model(
                    "dpc_voc_trainaug", Model.Dataset.PASCAL,
                    225, 225, 225, 225
            )

    )

    private var currentModelIndex = 0

    private var tflite: Interpreter? = null

    private lateinit var imgData: ByteBuffer

    private lateinit var segmentedImage: ByteBuffer

    init {
        loadModel(0)
        Log.d(TAG, "Created a Tensorflow Lite Image Segmenter.")
    }

    fun changeModel() {
        close()
        currentModelIndex = ++currentModelIndex % modelList.size
        loadModel(currentModelIndex)
    }

    private fun loadModel(index: Int) {
        if (index >= 0 && index < modelList.size) {
            currentModelIndex = index
            tfliteModel = loadModelFile(activity)
            recreateInterpreter()
        }
    }

    private fun recreateInterpreter() {
        val model = modelList[currentModelIndex]
        tflite?.close()
        tfliteModel?.let {
            tflite = Interpreter(it, tfliteOptions)
        }
        imgData = ByteBuffer
                .allocateDirect(DIM_BATCH_SIZE * model.inputWidth * model.inputHeight * DIM_PIXEL_SIZE * getNumBytesPerChannel())
                .also {
                    it.order(ByteOrder.nativeOrder())
                }

        segmentedImage = ByteBuffer
                .allocateDirect(DIM_BATCH_SIZE * model.outputWidth * model.outputHeight * getNumBytesPerChannel())
                .also {
                    it.order(ByteOrder.nativeOrder())
                }

        outFrame = IntArray(model.outputWidth * model.outputHeight)
        intValues = IntArray(model.inputWidth * model.inputHeight)
    }

    // fun setUseNNAPI(nnapi: Boolean) {
    //     tfliteOptions.setUseNNAPI(nnapi)
    //     recreateInterpreter()
    // }

    fun setNumThreads(numThreads: Int) {
        tfliteOptions.setNumThreads(numThreads)
        recreateInterpreter()
    }

    fun close() {
        tflite?.close()
        tflite = null
        tfliteModel?.clear()
        tfliteModel = null
    }

    @Throws(IOException::class)
    private fun loadModelFile(activity: Activity): MappedByteBuffer {
        val fileDescriptor = activity.assets.openFd(getModelPath())
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap) {
        imgData.rewind()

        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        // Convert the image to floating point.
        for (pixel in 0 until intValues.size) {
            val value = intValues[pixel]

            imgData.apply {
                putFloat((value shr 16 and 0xff).toFloat())
                putFloat((value shr 8 and 0xff).toFloat())
                putFloat((value and 0xff).toFloat())
            }
        }
    }


    private fun getModelPath(): String = "${modelList[currentModelIndex].path}.tflite"

    private fun getNumBytesPerChannel(): Int = 4

        fun segmentFrame(bitmap: Bitmap): IntArray {
        if (tflite == null) {
            Log.e(TAG, "Image segmenter has not been initialized; Skipped.")
        }

        convertBitmapToByteBuffer(bitmap)

        segmentedImage.rewind()
        tflite?.run(imgData, segmentedImage)

        segmentedImage.position(0)
        var i = 0
        while (segmentedImage.hasRemaining())
            outFrame[i++] = segmentedImage.int

        return outFrame
    }

    data class Model(
            val path: String,
            val colorSchema: Dataset,
            val inputWidth: Int,
            val inputHeight: Int,
            val outputWidth: Int,
            val outputHeight: Int
    ) {
        val colors: Array<Int> = when (colorSchema) {
            Dataset.PASCAL -> arrayOf(
                    0xFF3CB371.toInt(),
                    0xFFFFE4B5.toInt(),
                    0xFFFF1493.toInt(),
                    0xFF800080.toInt(),
                    0xFF87CEEB.toInt(),
                    0xFFF0FFFF.toInt(),
                    0xFFA52A2A.toInt(),
                    0xFF8A2BE2.toInt(),
                    0xFF228B22.toInt(),
                    0xFFFF00FF.toInt(),
                    0xFFFF69B4.toInt(),
                    0xFF696969.toInt(),
                    0xFF20B2AA.toInt(),
                    0xFF7FFF00.toInt(),
                    0xFF90EE90.toInt(),
                    0xFF708090.toInt(),
                    0xFFB8860B.toInt(),
                    0xFFD3D3D3.toInt(),
                    0xFF00FA9A.toInt(),
                    0xFFFFDEAD.toInt(),
                    0xFFFA8072.toInt()
            )
            Dataset.CITYSCAPES -> Array(cityscapesColors.size) {
                Color.rgb(cityscapesColors[it][0], cityscapesColors[it][1], cityscapesColors[it][2])
            }
        }

        enum class Dataset {
            PASCAL,
            CITYSCAPES
        }

        companion object {
            private val cityscapesColors = arrayOf(
                    arrayOf(128, 64, 128),
                    arrayOf(244, 35, 232),
                    arrayOf(70, 70, 70),
                    arrayOf(102, 102, 156),
                    arrayOf(190, 153, 153),
                    arrayOf(153, 153, 153),
                    arrayOf(250, 170, 30),
                    arrayOf(220, 220, 0),
                    arrayOf(107, 142, 35),
                    arrayOf(152, 251, 152),
                    arrayOf(70, 130, 180),
                    arrayOf(220, 20, 60),
                    arrayOf(255, 0, 0),
                    arrayOf(0, 0, 142),
                    arrayOf(0, 0, 70),
                    arrayOf(0, 60, 100),
                    arrayOf(0, 80, 100),
                    arrayOf(0, 0, 230),
                    arrayOf(119, 11, 32)
            )
        }
    }
}
