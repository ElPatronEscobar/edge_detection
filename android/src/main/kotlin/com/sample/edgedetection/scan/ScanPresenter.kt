package com.sample.edgedetection.scan
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.widget.Toast
import com.sample.edgedetection.EdgeDetectionPlugin
import com.sample.edgedetection.REQUEST_CODE
import com.sample.edgedetection.SourceManager
import com.sample.edgedetection.crop.CropActivity
import com.sample.edgedetection.processor.Corners
import com.sample.edgedetection.processor.processPicture
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Core.ROTATE_90_CLOCKWISE
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import android.util.Size as SizeB

class ScanPresenter constructor(
    private val context: Context,
    private val iView: IScanView.Proxy,
    private val initialBundle: Bundle
) :
    SurfaceHolder.Callback, Camera.PictureCallback, Camera.PreviewCallback {
    private val TAG: String = "ScanPresenter"
    private var mCamera: Camera? = null
    private val mSurfaceHolder: SurfaceHolder = iView.getSurfaceView().holder
    private val executor: ExecutorService
    private val proxySchedule: Scheduler
    private var busy: Boolean = false
    private var mCameraLensFacing: String? = null
    private var flashEnabled: Boolean = false

    private var mLastClickTime = 0L
    private var shutted: Boolean = true
    
    // Aggiungiamo un handler e runnable per il timeout dell'acquisizione dell'immagine
    private val pictureTimeoutHandler = Handler(Looper.getMainLooper())
    private val pictureTimeoutRunnable = Runnable {
        Log.e(TAG, "Picture taking timed out")
        showError("Timeout durante l'acquisizione dell'immagine")
        releaseCamera()
        shutted = true
        busy = false
    }

    init {
        mSurfaceHolder.addCallback(this)
        executor = Executors.newSingleThreadExecutor()
        proxySchedule = Schedulers.from(executor)
    }

    private fun isOpenRecently(): Boolean {
        if (SystemClock.elapsedRealtime() - mLastClickTime < 3000) {
            return true
        }
        mLastClickTime = SystemClock.elapsedRealtime()
        return false
    }

    fun start() {
        mCamera?.startPreview() ?:
        Log.i(TAG, "mCamera startPreview")
    }

    fun stop() {
        mCamera?.stopPreview() ?:
        Log.i(TAG, "mCamera stopPreview")
    }

    val canShut: Boolean get() = shutted

    fun shut() {
        if (isOpenRecently()) {
            Log.i(TAG, "NOT Taking click")
            return
        }
        busy = true
        shutted = false
        Log.i(TAG, "try to focus")

        try {
            mCamera?.autoFocus { b, _ ->
                Log.i(TAG, "focus result: $b")
                mCamera?.enableShutterSound(false)
                
                // Impostiamo un timeout di 10 secondi per l'acquisizione dell'immagine
                pictureTimeoutHandler.postDelayed(pictureTimeoutRunnable, 10000)
                
                mCamera?.takePicture(null, null, this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error taking picture: ${e.message}", e)
            showError("Impossibile acquisire l'immagine")
            shutted = true
            busy = false
        }
    }

    fun toggleFlash() {
        try {
            flashEnabled = !flashEnabled
            val parameters = mCamera?.parameters
            parameters?.flashMode =
                if (flashEnabled) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
            mCamera?.parameters = parameters
            mCamera?.startPreview()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun updateCamera() {
        if (null == mCamera) {
            return
        }
        mCamera?.stopPreview()
        try {
            mCamera?.setPreviewDisplay(mSurfaceHolder)
        } catch (e: IOException) {
            e.printStackTrace()
            return
        }
        mCamera?.setPreviewCallback(this)
        mCamera?.startPreview()
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun getCameraCharacteristics(id: String): CameraCharacteristics {
        return cameraManager.getCameraCharacteristics(id)
    }

    private fun getBackFacingCameraId(): String? {
        for (camID in cameraManager.cameraIdList) {
            val lensFacing =
                getCameraCharacteristics(camID)?.get(CameraCharacteristics.LENS_FACING)!!
            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                mCameraLensFacing = camID
                break
            }
        }
        return mCameraLensFacing
    }

    private fun initCamera() {
        // Tentiamo prima la fotocamera posteriore, se fallisce proviamo quella frontale
        try {
            Log.i(TAG, "Tentativo di aprire la fotocamera posteriore")
            mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Errore nell'apertura della fotocamera posteriore: ${e.message}")
            
            try {
                // Prova con la fotocamera frontale come fallback
                Log.i(TAG, "Tentativo di aprire la fotocamera frontale")
                mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT)
            } catch (e2: RuntimeException) {
                Log.e(TAG, "Errore nell'apertura della fotocamera frontale: ${e2.message}")
                e2.stackTrace
                Toast.makeText(context, "cannot open camera, please grant camera", Toast.LENGTH_SHORT)
                    .show()
                return
            }
        }

        try {
            val cameraCharacteristics =
                cameraManager.getCameraCharacteristics(getBackFacingCameraId()!!)

            val size = iView.getCurrentDisplay()?.let {
                getPreviewOutputSize(
                    it, cameraCharacteristics, SurfaceHolder::class.java
                )
            }

            Log.i(TAG, "Selected preview size: ${size?.width}${size?.height}")

            size?.width?.toString()?.let { Log.i(TAG, it) }
            val param = mCamera?.parameters
            param?.setPreviewSize(size?.width ?: 1920, size?.height ?: 1080)
            val display = iView.getCurrentDisplay()
            val point = Point()

            display?.getRealSize(point)

            val displayWidth = minOf(point.x, point.y)
            val displayHeight = maxOf(point.x, point.y)
            val displayRatio = displayWidth.div(displayHeight.toFloat())
            val previewRatio = size?.height?.toFloat()?.div(size.width.toFloat()) ?: displayRatio
            if (displayRatio > previewRatio) {
                val surfaceParams = iView.getSurfaceView().layoutParams
                surfaceParams.height = (displayHeight / displayRatio * previewRatio).toInt()
                iView.getSurfaceView().layoutParams = surfaceParams
            }

            val supportPicSize = mCamera?.parameters?.supportedPictureSizes
            supportPicSize?.sortByDescending { it.width.times(it.height) }
            var pictureSize = supportPicSize?.find {
                it.height.toFloat().div(it.width.toFloat()) - previewRatio < 0.01
            }

            if (null == pictureSize) {
                pictureSize = supportPicSize?.get(0)
            }

            if (null == pictureSize) {
                Log.e(TAG, "can not get picture size")
            } else {
                param?.setPictureSize(pictureSize.width, pictureSize.height)
            }
            val pm = context.packageManager
            if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_AUTOFOCUS) && mCamera!!.parameters.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
            {
                param?.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                Log.i(TAG, "enabling autofocus")
            } else {
                Log.i(TAG, "autofocus not available")
            }

            param?.flashMode = Camera.Parameters.FLASH_MODE_OFF

            mCamera?.parameters = param
            mCamera?.setDisplayOrientation(90)
            mCamera?.enableShutterSound(false)
        } catch (e: Exception) {
            Log.e(TAG, "Errore nella configurazione della fotocamera: ${e.message}")
            e.printStackTrace()
            
            // Assicuriamoci di rilasciare la fotocamera in caso di errore
            releaseCamera()
        }
    }

    private fun matrixResizer(sourceMatrix: Mat): Mat {
        val sourceSize: Size = sourceMatrix.size()
        var copied = Mat()
        if (sourceSize.height < sourceSize.width) {
            Core.rotate(sourceMatrix, copied, ROTATE_90_CLOCKWISE)
        } else {
            copied = sourceMatrix
        }
        val copiedSize: Size = copied.size()
        return if (copiedSize.width > ScanConstants.MAX_SIZE.width || copiedSize.height > ScanConstants.MAX_SIZE.height) {
            var useRatio = 0.0
            val widthRatio: Double = ScanConstants.MAX_SIZE.width / copiedSize.width
            val heightRatio: Double = ScanConstants.MAX_SIZE.height / copiedSize.height
            useRatio = if(widthRatio > heightRatio)  widthRatio else heightRatio
            val resizedImage = Mat()
            val newSize = Size(copiedSize.width * useRatio, copiedSize.height * useRatio)
            Imgproc.resize(copied, resizedImage, newSize)
            resizedImage
        } else {
            copied
        }
    }
    
    fun detectEdge(pic: Mat) {
        Log.i("height", pic.size().height.toString())
        Log.i("width", pic.size().width.toString())
        val resizedMat = matrixResizer(pic)
        SourceManager.corners = processPicture(resizedMat)
        Imgproc.cvtColor(resizedMat, resizedMat, Imgproc.COLOR_RGB2BGRA)
        SourceManager.pic = resizedMat
        val cropIntent = Intent(context, CropActivity::class.java)
        cropIntent.putExtra(EdgeDetectionPlugin.INITIAL_BUNDLE, this.initialBundle)
        (context as Activity).startActivityForResult(cropIntent, REQUEST_CODE)
    }

    override fun surfaceCreated(p0: SurfaceHolder) {
        initCamera()
    }

    override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
        updateCamera()
    }

    override fun surfaceDestroyed(p0: SurfaceHolder) {
        synchronized(this) {
            releaseCamera()
        }
    }
    
    // Metodo di supporto per mostrare errori all'utente
    private fun showError(message: String) {
        Log.e(TAG, "Errore: $message")
        (context as? Activity)?.runOnUiThread {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    
    // Metodo per rilasciare le risorse della fotocamera in modo sicuro
    private fun releaseCamera() {
        try {
            mCamera?.stopPreview()
            mCamera?.setPreviewCallback(null)
            mCamera?.release()
            mCamera = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing camera: ${e.message}", e)
        }
    }
    
    // Metodo helper per creare un file temporaneo
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_$timeStamp"
        val storageDir = context.getExternalFilesDir("Pictures")
        return File.createTempFile(imageFileName, ".jpg", storageDir)
    }

    /**
     * Approccio radicale che bypassa completamente il problema di OpenCV
     * Usando le API Android native per salvare e processare l'immagine
     */
    override fun onPictureTaken(p0: ByteArray?, p1: Camera?) {
        // Rimuovi il timeout
        pictureTimeoutHandler.removeCallbacks(pictureTimeoutRunnable)
        
        Log.i(TAG, "##### USANDO LA VERSIONE RADICALE DEL METODO ONPICTURETAKEN #####")
        
        if (p0 == null || p0.isEmpty()) {
            Log.e(TAG, "Immagine acquisita vuota o nulla")
            showError("Immagine non valida, riprovare")
            shutted = true
            busy = false
            return
        }
        
        Log.d(TAG, "Dati immagine ricevuti: ${p0.size} bytes")
        
        Observable.create<Unit> { emitter ->
            try {
                // Salva direttamente i dati dell'immagine in un file JPEG
                val pictureFile = createImageFile()
                try {
                    FileOutputStream(pictureFile).use { fos ->
                        fos.write(p0)
                        fos.flush()
                    }
                    
                    Log.i(TAG, "Immagine salvata direttamente su file: ${pictureFile.absolutePath}")
                    
                    // Verifica che il file sia stato creato e abbia una dimensione valida
                    if (!pictureFile.exists() || pictureFile.length() < 100) {
                        throw IOException("File non valido o troppo piccolo: ${pictureFile.length()} bytes")
                    }
                    
                    // Carica l'immagine come bitmap usando le API Android (evitando OpenCV)
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val bitmap = BitmapFactory.decodeFile(pictureFile.absolutePath, options)
                    
                    if (bitmap == null) {
                        throw IOException("Impossibile decodificare l'immagine come bitmap")
                    }
                    
                    Log.i(TAG, "Bitmap creato con successo: ${bitmap.width}x${bitmap.height}")
                    
                    // Converti il bitmap in Mat per il processing
                    val mat = Mat()
                    Utils.bitmapToMat(bitmap, mat)
                    bitmap.recycle()
                    
                    if (mat.empty()) {
                        throw IOException("Conversione da bitmap a Mat fallita")
                    }
                    
                    // Ruota l'immagine se necessario (su alcuni dispositivi potrebbe essere già ruotata)
                    Core.rotate(mat, mat, Core.ROTATE_90_CLOCKWISE)
                    
                    // Rileva i bordi e procedi con il crop
                    detectEdge(mat)
                    emitter.onNext(Unit)
                    emitter.onComplete()
                } catch (e: Exception) {
                    Log.e(TAG, "Errore durante il salvataggio o l'elaborazione dell'immagine: ${e.message}", e)
                    emitter.onError(e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore generico: ${e.message}", e)
                emitter.onError(e)
            }
        }
        .subscribeOn(proxySchedule)
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(
            // Successo
            {
                Log.d(TAG, "Immagine elaborata con successo")
                shutted = true
                busy = false
            },
            // Errore
            { error ->
                Log.e(TAG, "Errore nell'elaborazione dell'immagine: ${error.message}", error)
                showError("Impossibile elaborare l'immagine: ${error.localizedMessage ?: "Errore sconosciuto"}")
                shutted = true
                busy = false
            }
        )
    }

    override fun onPreviewFrame(p0: ByteArray?, p1: Camera?) {
        if (busy) {
            return
        }
        busy = true
        try {
            Observable.just(p0)
                .observeOn(proxySchedule)
                .doOnError {}
                .subscribe({
                    val parameters = p1?.parameters
                    val width = parameters?.previewSize?.width
                    val height = parameters?.previewSize?.height
                    val yuv = YuvImage(
                        p0, parameters?.previewFormat ?: 0, width ?: 1080, height
                            ?: 1920, null
                    )
                    val out = ByteArrayOutputStream()
                    yuv.compressToJpeg(Rect(0, 0, width ?: 1080, height ?: 1920), 100, out)
                    val bytes = out.toByteArray()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val img = Mat()
                    Utils.bitmapToMat(bitmap, img)
                    bitmap.recycle()
                    Core.rotate(img, img, Core.ROTATE_90_CLOCKWISE)
                    try {
                        out.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }

                    Observable.create<Corners> {
                        val corner = processPicture(img)
                        busy = false
                        if (null != corner && corner.corners.size == 4) {
                            it.onNext(corner)
                        } else {
                            it.onError(Throwable("paper not detected"))
                        }
                    }.observeOn(AndroidSchedulers.mainThread())
                        .subscribe({
                            iView.getPaperRect().onCornersDetected(it)

                        }, {
                            iView.getPaperRect().onCornersNotDetected()
                        })
                }, { throwable -> Log.e(TAG, throwable.message!!) })
        } catch (e: Exception) {
            print(e.message)
        }
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */

    class SmartSize(width: Int, height: Int) {
        var size = SizeB(width, height)
        var long = max(size.width, size.height)
        var short = min(size.width, size.height)
        override fun toString() = "SmartSize(${long}x${short})"
    }

    /** Standard High Definition size for pictures and video */
    private val SIZE_1080P: SmartSize = SmartSize(1920, 1080)

    /** Returns a [SmartSize] object for the given [Display] */
    private fun getDisplaySmartSize(display: Display): SmartSize {
        val outPoint = Point()
        display.getRealSize(outPoint)
        return SmartSize(outPoint.x, outPoint.y)
    }

    /**
     * Returns the largest available PREVIEW size. For more information, see:
     * https://d.android.com/reference/android/hardware/camera2/CameraDevice and
     * https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap
     */
    private fun <T> getPreviewOutputSize(
        display: Display,
        characteristics: CameraCharacteristics,
        targetClass: Class<T>,
        format: Int? = null
    ): SizeB {

        // Find which is smaller: screen or 1080p
        val screenSize = getDisplaySmartSize(display)
        val hdScreen = screenSize.long >= SIZE_1080P.long || screenSize.short >= SIZE_1080P.short
        val maxSize = if (hdScreen) SIZE_1080P else screenSize

        // If image format is provided, use it to determine supported sizes; else use target class
        val config = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )!!
        if (format == null)
            assert(StreamConfigurationMap.isOutputSupportedFor(targetClass))
        else
            assert(config.isOutputSupportedFor(format))
        val allSizes = if (format == null)
            config.getOutputSizes(targetClass) else config.getOutputSizes(format)

        // Get available sizes and sort them by area from largest to smallest
        val validSizes = allSizes
            .sortedWith(compareBy { it.height * it.width })
            .map { SmartSize(it.width, it.height) }.reversed()

        // Then, get the largest output size that is smaller or equal than our max size
        return validSizes.first { it.long <= maxSize.long && it.short <= maxSize.short }.size
    }
}