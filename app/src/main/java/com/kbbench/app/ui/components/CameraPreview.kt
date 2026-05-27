package com.kbbench.app.ui.components

import android.view.Surface
import android.view.SurfaceHolder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kbbench.utils.AutoFitSurfaceView

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onSurfaceCreated: (Surface) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            AutoFitSurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurfaceCreated(holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                    }
                })
            }
        }
    )
}
