package com.sample.edgedetection.scan

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sample.edgedetection.R
import com.sample.edgedetection.EdgeDetectionPlugin
import com.sample.edgedetection.SourceManager
import com.sample.edgedetection.view.PaperRectangle

class ScanActivity : AppCompatActivity(), IScanView.Proxy {
    private val TAG = "ScanActivity"
    
    var isTorchOn = false
    private val presenter: ScanPresenter by lazy {
        ScanPresenter(
            this,
            this,
            intent.getBundleExtra(EdgeDetectionPlugin.INITIAL_BUNDLE) ?: Bundle()
        )
    }

    private lateinit var shutterButton: ImageButton
    private lateinit var torchButton: ImageButton
    private lateinit var closeButton: ImageButton
    private lateinit var surfaceView: View
    private lateinit var paperRect: PaperRectangle
    private lateinit var overlay: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        
        initializeViews()
        setListeners()
    }

    private fun initializeViews() {
        // Inizializza le view
        shutterButton = findViewById(R.id.shutterButton)
        torchButton = findViewById(R.id.torchButton)
        closeButton = findViewById(R.id.closeButton)
        surfaceView = findViewById(R.id.surfaceView)
        paperRect = findViewById(R.id.paperRect)
        overlay = findViewById(R.id.overlay)
    }

    private fun setListeners() {
        // Imposta i listener per i pulsanti
        shutterButton.setOnClickListener {
            if (presenter.canShut) {
                shutterButton.isEnabled = false // Disabilita il pulsante per evitare doppi clic
                Log.d(TAG, "Shutter button clicked, taking picture")
                presenter.shut()
            } else {
                Log.d(TAG, "Shutter button clicked but cannot shut")
            }
        }
        
        torchButton.setOnClickListener {
            isTorchOn = !isTorchOn
            torchButton.isSelected = isTorchOn
            Log.d(TAG, "Torch button clicked, toggling flash")
            presenter.toggleFlash()
        }
        
        closeButton.setOnClickListener {
            Log.d(TAG, "Close button clicked")
            finishWithError()
        }
    }

    private fun finishWithError() {
        Log.d(TAG, "Scan canceled by user")
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun finishWithSuccess(imagePath: String) {
        Log.d(TAG, "Scan successful: $imagePath")
        val intent = Intent()
        intent.putExtra(EdgeDetectionPlugin.SCANNED_RESULT, imagePath)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    override fun onResume() {
        super.onResume()
        presenter.start()
    }

    override fun onPause() {
        super.onPause()
        presenter.stop()
    }

    override fun getSurfaceView() = surfaceView

    override fun getPaperRect() = paperRect

    override fun getOverlayView() = overlay

    override fun getScanButtonView() = shutterButton

    override fun getTorchButtonView() = torchButton

    override fun getCurrentDisplay() = windowManager.defaultDisplay

    override fun getActivityContext() = this
}