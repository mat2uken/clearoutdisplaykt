package com.google.codelab.android.camera

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.view.PreviewView

class ExternalDisplayPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display) {

    private lateinit var previewView: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frameLayout = FrameLayout(context)
        previewView = PreviewView(context)
        previewView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // Ensure preview scales correctly on the external display
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        frameLayout.addView(previewView)
        setContentView(frameLayout)
    }

    fun getPreviewView(): PreviewView {
        return previewView
    }
}
