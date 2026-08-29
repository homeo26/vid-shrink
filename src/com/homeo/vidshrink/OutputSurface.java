/*
 * Holds the decoder's output Surface, backed by a SurfaceTexture bound to an
 * external OES texture. Each decoded frame becomes available via
 * onFrameAvailable; drawImage() renders it (through TextureRender) into
 * whatever EGL surface is current (the encoder input).
 * Adapted from Android CTS ExtractDecodeEditEncodeMux sample (Apache 2.0).
 */
package com.homeo.vidshrink;

import android.graphics.SurfaceTexture;
import android.view.Surface;

class OutputSurface implements SurfaceTexture.OnFrameAvailableListener {
    private SurfaceTexture mSurfaceTexture;
    private Surface mSurface;
    private final Object mFrameSyncObject = new Object();
    private boolean mFrameAvailable;
    private TextureRender mTextureRender;

    OutputSurface() {
        mTextureRender = new TextureRender();
        mTextureRender.surfaceCreated();
        mSurfaceTexture = new SurfaceTexture(mTextureRender.getTextureId());
        mSurfaceTexture.setOnFrameAvailableListener(this);
        mSurface = new Surface(mSurfaceTexture);
    }

    Surface getSurface() { return mSurface; }

    void awaitNewImage() {
        final int TIMEOUT_MS = 10000;
        synchronized (mFrameSyncObject) {
            while (!mFrameAvailable) {
                try {
                    mFrameSyncObject.wait(TIMEOUT_MS);
                    if (!mFrameAvailable)
                        throw new RuntimeException("frame wait timed out");
                } catch (InterruptedException ie) {
                    throw new RuntimeException(ie);
                }
            }
            mFrameAvailable = false;
        }
        mSurfaceTexture.updateTexImage();
    }

    void drawImage() {
        mTextureRender.drawFrame(mSurfaceTexture);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        synchronized (mFrameSyncObject) {
            if (mFrameAvailable)
                throw new RuntimeException("mFrameAvailable already set, frame dropped");
            mFrameAvailable = true;
            mFrameSyncObject.notifyAll();
        }
    }

    void release() {
        if (mSurface != null) mSurface.release();
        mSurfaceTexture = null;
        mSurface = null;
        mTextureRender = null;
    }
}
