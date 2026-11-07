package tn.eaba.dronedetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Detection(
    val box: RectF,
    val score: Float,
    val classId: Int,
    val label: String
)

class Detector(
    context: Context,
    modelFile: String = "best_int8.tflite",
    labelsFile: String = "labels.txt"
) {
    companion object {
        private const val TAG = "Detector"
        const val INPUT_SIZE = 640
        const val CONF_THRESHOLD = 0.35f
        const val IOU_THRESHOLD = 0.45f
        private const val PAD_GRAY = 114
    }

    private val interpreter: Interpreter
    val labels: List<String>
    private val numClasses: Int
    private val channelsFirst: Boolean
    private val numBoxes: Int

    private val inputIsQuantized: Boolean
    private val inputScale: Float
    private val inputZeroPoint: Int
    private val outputIsQuantized: Boolean
    private val outputScale: Float
    private val outputZeroPoint: Int

    var debugInfo: String = ""
        private set

    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer

    init {
        labels = context.assets.open(labelsFile)
            .bufferedReader()
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val fd = context.assets.openFd(modelFile)
        val model: MappedByteBuffer = fd.createInputStream().channel.map(
            FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
        )
        interpreter = Interpreter(model, Interpreter.Options().apply { numThreads = 4 })

        val inT = interpreter.getInputTensor(0)
        val outT = interpreter.getOutputTensor(0)
        val outShape = outT.shape()
        Log.i(TAG, "Entree : ${inT.shape().joinToString()} ${inT.dataType()}")
        Log.i(TAG, "Sortie : ${outShape.joinToString()} ${outT.dataType()}")

        val a = outShape[1]
        val b = outShape[2]
        channelsFirst = a < b
        numClasses = (if (channelsFirst) a else b) - 4
        numBoxes = if (channelsFirst) b else a

        require(numClasses == labels.size) {
            "Incoherence modele/labels : le modele sort $numClasses classes, " +
                    "labels.txt en contient ${labels.size}."
        }

        val inQ = inT.quantizationParams()
        inputIsQuantized = inT.dataType() != DataType.FLOAT32
        inputScale = if (inQ.scale != 0f) inQ.scale else 1f
        inputZeroPoint = inQ.zeroPoint

        val outQ = outT.quantizationParams()
        outputIsQuantized = outT.dataType() != DataType.FLOAT32
        outputScale = if (outQ.scale != 0f) outQ.scale else 1f
        outputZeroPoint = outQ.zeroPoint

        val inBytes = if (inputIsQuantized) 1 else 4
        inputBuffer = ByteBuffer
            .allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * inBytes)
            .order(ByteOrder.nativeOrder())

        val outBytes = if (outputIsQuantized) 1 else 4
        outputBuffer = ByteBuffer
            .allocateDirect(outShape[1] * outShape[2] * outBytes)
            .order(ByteOrder.nativeOrder())
    }

    private data class Letterbox(val bitmap: Bitmap, val scale: Float, val dx: Float, val dy: Float)

    private fun letterbox(src: Bitmap): Letterbox {
        val scale = min(
            INPUT_SIZE.toFloat() / src.width,
            INPUT_SIZE.toFloat() / src.height
        )
        val newW = (src.width * scale).roundToInt()
        val newH = (src.height * scale).roundToInt()
        val dx = (INPUT_SIZE - newW) / 2f
        val dy = (INPUT_SIZE - newH) / 2f

        val out = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawARGB(255, PAD_GRAY, PAD_GRAY, PAD_GRAY)
        val m = Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return Letterbox(out, scale, dx, dy)
    }

    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)

    private fun fillInput(bitmap: Bitmap) {
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        inputBuffer.rewind()
        val n = INPUT_SIZE * INPUT_SIZE
        // NCHW : plan R complet, puis plan G, puis plan B
        for (plane in 0..2) {
            val shift = 16 - plane * 8          // 16=R, 8=G, 0=B
            for (i in 0 until n) {
                val v = (pixels[i] shr shift and 0xFF) / 255f
                if (inputIsQuantized) {
                    inputBuffer.put(((v / inputScale) + inputZeroPoint).roundToInt().toByte())
                } else {
                    inputBuffer.putFloat(v)
                }
            }
        }
    }

    private fun out(index: Int): Float =
        if (outputIsQuantized) {
            val q = outputBuffer.get(index).toInt()
            (q - outputZeroPoint) * outputScale
        } else {
            outputBuffer.getFloat(index * 4)
        }

    fun detect(source: Bitmap): List<Detection> {
        val lb = letterbox(source)
        fillInput(lb.bitmap)
        lb.bitmap.recycle()

        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)

        var vmax = -Float.MAX_VALUE
        var scoreMax = 0f
        for (i in 0 until numBoxes) {
            for (c in 0 until 4 + numClasses) {
                val v = if (channelsFirst) out(c * numBoxes + i)
                else out(i * (4 + numClasses) + c)
                if (v > vmax) vmax = v
                if (c >= 4 && v > scoreMax) scoreMax = v
            }
        }
        debugInfo = "max=%.2f sMax=%.3f sc=%.5f zp=%d q=%b nc=%d nb=%d cf=%b"
            .format(vmax, scoreMax, outputScale, outputZeroPoint,
                outputIsQuantized, numClasses, numBoxes, channelsFirst)

        val raw = ArrayList<Detection>()
        val stride = if (channelsFirst) numBoxes else (4 + numClasses)

        for (i in 0 until numBoxes) {
            fun v(c: Int) = if (channelsFirst) out(c * stride + i) else out(i * stride + c)

            var bestScore = 0f
            var bestClass = -1
            for (c in 0 until numClasses) {
                val s = v(4 + c)
                if (s > bestScore) { bestScore = s; bestClass = c }
            }
            if (bestClass < 0 || bestScore < CONF_THRESHOLD) continue

            var cx = v(0); var cy = v(1); var w = v(2); var h = v(3)
            if (cx <= 1.5f && w <= 1.5f) {
                cx *= INPUT_SIZE; cy *= INPUT_SIZE; w *= INPUT_SIZE; h *= INPUT_SIZE
            }

            val x1 = ((cx - w / 2f) - lb.dx) / lb.scale
            val y1 = ((cy - h / 2f) - lb.dy) / lb.scale
            val x2 = ((cx + w / 2f) - lb.dx) / lb.scale
            val y2 = ((cy + h / 2f) - lb.dy) / lb.scale

            raw.add(
                Detection(
                    RectF(
                        x1.coerceIn(0f, source.width.toFloat()),
                        y1.coerceIn(0f, source.height.toFloat()),
                        x2.coerceIn(0f, source.width.toFloat()),
                        y2.coerceIn(0f, source.height.toFloat())
                    ),
                    bestScore,
                    bestClass,
                    labels[bestClass]
                )
            )
        }
        return nms(raw)
    }

    private fun nms(input: List<Detection>): List<Detection> {
        val sorted = input.sortedByDescending { it.score }.toMutableList()
        val kept = ArrayList<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            sorted.removeAll { iou(best.box, it.box) > IOU_THRESHOLD }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val l = max(a.left, b.left); val t = max(a.top, b.top)
        val r = min(a.right, b.right); val bt = min(a.bottom, b.bottom)
        val inter = max(0f, r - l) * max(0f, bt - t)
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun close() = interpreter.close()
}