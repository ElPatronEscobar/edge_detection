package com.sample.edgedetection

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry
import java.io.File
import com.sample.edgedetection.scan.ScanActivity

class EdgeDetectionPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, PluginRegistry.ActivityResultListener {
    private lateinit var channel: MethodChannel
    private var activity: Activity? = null
    private var result: MethodChannel.Result? = null
    private var filePath: String? = null

    companion object {
        const val REQUEST_CODE = 99
        const val SCANNED_RESULT = "scanned_result"
        const val INITIAL_BUNDLE = "initial_bundle"
        private const val TAG = "EdgeDetectionPlugin"
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "edge_detection")
        channel.setMethodCallHandler(this)
        Log.d(TAG, "Plugin registrato manualmente")
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "detect_edge" -> {
                if (this.result != null) {
                    result.error("ALREADY_ACTIVE", "Edge detection is already active", null)
                    return
                }

                this.result = result
                
                try {
                    // Estrae il percorso del file dall'argomento
                    filePath = call.argument<String>("path")
                    if (filePath.isNullOrEmpty()) {
                        Log.e(TAG, "File path is null or empty")
                        this.result?.error("INVALID_PATH", "File path is invalid", null)
                        this.result = null
                        return
                    }
                    
                    Log.d(TAG, "Starting edge detection with path: $filePath")
                    
                    // Prepara i parametri per l'attività di scansione
                    val scannerOptions = Bundle()
                    val canUseGallery = call.argument<Boolean>("canUseGallery") ?: false
                    val scannerTitle = call.argument<String>("androidScanTitle") ?: "Scanning"
                    val cropperTitle = call.argument<String>("androidCropTitle") ?: "Adjust corners"
                    val cropperBWTitle = call.argument<String>("androidCropBlackWhiteTitle") ?: "Black & White"
                    val cropperResetTitle = call.argument<String>("androidCropReset") ?: "Reset"
                    
                    scannerOptions.putString("file_path", filePath)
                    scannerOptions.putBoolean("can_use_gallery", canUseGallery)
                    scannerOptions.putString("scanner_title", scannerTitle)
                    scannerOptions.putString("cropper_title", cropperTitle)
                    scannerOptions.putString("cropper_bw_title", cropperBWTitle)
                    scannerOptions.putString("cropper_reset_title", cropperResetTitle)
                    
                    // Avvia l'attività di scansione
                    val intent = Intent(activity, ScanActivity::class.java)
                    intent.putExtra(INITIAL_BUNDLE, scannerOptions)
                    activity?.startActivityForResult(intent, REQUEST_CODE)
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting edge detection", e)
                    this.result?.error("EDGE_DETECTION_ERROR", e.message, null)
                    this.result = null
                }
            }
            else -> result.notImplemented()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQUEST_CODE) {
            Log.d(TAG, "Activity result received: resultCode=$resultCode")
            
            if (resultCode == Activity.RESULT_OK) {
                // Ottieni il risultato dell'attività
                val resultPath = data?.getStringExtra(SCANNED_RESULT)
                
                if (resultPath != null && File(resultPath).exists()) {
                    Log.d(TAG, "Scan successful: $resultPath")
                    result?.success(true)
                } else {
                    Log.e(TAG, "Result path is null or file doesn't exist: $resultPath")
                    result?.success(false)
                }
            } else {
                Log.d(TAG, "Scan cancelled or failed")
                result?.success(false)
            }
            
            // Pulisci lo stato
            result = null
            filePath = null
            
            return true
        }
        return false
    }
}