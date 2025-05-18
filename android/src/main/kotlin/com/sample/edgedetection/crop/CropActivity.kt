package com.sample.edgedetection.crop

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import com.sample.edgedetection.EdgeDetectionPlugin
import com.sample.edgedetection.R
import com.sample.edgedetection.SourceManager
import java.io.File

class CropActivity : AppCompatActivity(), ICropView.Proxy {
    private val TAG = "CropActivity"
    
    private lateinit var doneButton: Button
    private lateinit var rotateButton: ImageButton
    private lateinit var closeButton: ImageButton
    private lateinit var enhanceButton: ImageButton
    private lateinit var restoreButton: ImageButton
    private lateinit var cropImageView: View
    private lateinit var titleTextView: TextView
    
    private val initialBundle: Bundle by lazy {
        intent.getBundleExtra(EdgeDetectionPlugin.INITIAL_BUNDLE) ?: Bundle()
    }
    
    private val presenter by lazy {
        CropPresenter(this, initialBundle)
    }

    private var imagePath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)
        
        initializeViews()
        setListeners()
        setupFromBundle()
    }

    private fun initializeViews() {
        doneButton = findViewById(R.id.doneButton)
        rotateButton = findViewById(R.id.rotateButton)
        closeButton = findViewById(R.id.closeButton)
        enhanceButton = findViewById(R.id.enhanceButton)
        restoreButton = findViewById(R.id.restoreButton)
        cropImageView = findViewById(R.id.cropImageView)
        titleTextView = findViewById(R.id.titleTextView)
    }

    private fun setListeners() {
        doneButton.setOnClickListener { 
            Log.d(TAG, "Done button clicked")
            presenter.crop()
        }
        
        rotateButton.setOnClickListener { 
            Log.d(TAG, "Rotate button clicked")
            presenter.rotate()
        }
        
        closeButton.setOnClickListener { 
            Log.d(TAG, "Close button clicked")
            finishWithError()
        }
        
        enhanceButton.setOnClickListener { 
            Log.d(TAG, "Enhance button clicked")
            presenter.enhance()
            enhanceButton.isSelected = true
            restoreButton.isSelected = false
        }
        
        restoreButton.setOnClickListener { 
            Log.d(TAG, "Restore button clicked")
            presenter.restore()
            enhanceButton.isSelected = false
            restoreButton.isSelected = true
        }
    }

    private fun setupFromBundle() {
        val cropTitle = initialBundle.getString("cropper_title", "Adjust corners")
        val blackAndWhiteTitle = initialBundle.getString("cropper_bw_title", "Black & White")
        val resetTitle = initialBundle.getString("cropper_reset_title", "Reset")
        
        titleTextView.text = cropTitle
        enhanceButton.contentDescription = blackAndWhiteTitle
        restoreButton.contentDescription = resetTitle
    }

    override fun onStart() {
        super.onStart()
        presenter.start()
    }

    override fun onStop() {
        super.onStop()
        presenter.stop()
    }

    private fun finishWithError() {
        Log.d(TAG, "Crop canceled by user")
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun getPaper() = cropImageView

    override fun getCroppedPaper(imagePath: String) {
        this.imagePath = imagePath
        Log.d(TAG, "Cropped path: $imagePath")
        
        if (File(imagePath).exists()) {
            val intent = Intent()
            intent.putExtra(EdgeDetectionPlugin.SCANNED_RESULT, imagePath)
            setResult(Activity.RESULT_OK, intent)
        } else {
            Log.e(TAG, "Cropped file does not exist: $imagePath")
            setResult(Activity.RESULT_CANCELED)
        }
        
        finish()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        finishWithError()
    }
}