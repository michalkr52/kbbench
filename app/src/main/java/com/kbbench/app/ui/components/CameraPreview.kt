package com.kbbench.app.ui.components

import android.view.Surface
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kbbench.utils.AutoFitTextureView

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    previewWidth: Int = 0,
    previewHeight: Int = 0,
    onSurfaceCreated: (Surface) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            AutoFitTextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        onSurfaceCreated(Surface(surface))
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    }
                }
            }
        },
        update = { view ->
            if (previewWidth > 0 && previewHeight > 0) {
                view.setAspectRatio(previewWidth, previewHeight)
            }
        }
    )
}
