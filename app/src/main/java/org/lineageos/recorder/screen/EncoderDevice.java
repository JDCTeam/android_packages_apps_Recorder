/*
 * Copyright (C) 2013 The CyanogenMod Project
 * Copyright (C) 2017 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lineageos.recorder.screen;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.EncoderCapabilities;
import android.media.EncoderCapabilities.VideoEncoderCap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import org.lineageos.recorder.R;

import java.io.IOException;
import java.util.List;

abstract class EncoderDevice {
    final Context context;
    private final String LOGTAG = getClass().getSimpleName();

    private static final List<VideoEncoderCap> videoEncoders =
            EncoderCapabilities.getVideoEncoders();

    // Standard resolution tables, removed values that aren't multiples of 8
    private final int[][] validResolutions = {
            {720, 1080}
    };
    private MediaCodec venc;
    private int width=720;
    private int height=1080;
    private VirtualDisplay virtualDisplay;

    EncoderDevice(Context context, int width, int height) {
        this.context = context;
        this.width = 720;
        this.height = 1080;
    }

    VirtualDisplay registerVirtualDisplay(Context context) {
        assert virtualDisplay == null;
        DisplayManager dm = context.getSystemService(DisplayManager.class);
        Surface surface = createDisplaySurface();
        if (surface == null || dm == null)
            return null;
        return virtualDisplay = dm.createVirtualDisplay(ScreencastService.SCREENCASTER_NAME,
                720, 1080, 1,
                surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC |
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE);
    }

    void stop() {
        if (venc != null) {
            try {
                venc.signalEndOfInputStream();
            } catch (Exception ignored) {
            }
            venc = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
    }

    private void destroyDisplaySurface(MediaCodec venc) {
        if (venc == null) {
            return;
        }
        // release this surface
        try {
            venc.stop();
            venc.release();
        } catch (Exception ignored) {
        }
        // see if this device is still in use
        if (this.venc != venc) {
            return;
        }
        // display is done, kill it
        this.venc = null;

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
    }

    protected abstract EncoderRunnable onSurfaceCreated(MediaCodec venc);

    private Surface createDisplaySurface() {
        if (venc != null) {
            // signal any old crap to end
            try {
                venc.signalEndOfInputStream();
            } catch (Exception ignored) {
            }
            venc = null;
        }

        int maxWidth = 720;
        int maxHeight = 1080;
        int bitrate = 2000000;

        for (VideoEncoderCap cap : videoEncoders) {
            if (cap.mCodec == MediaRecorder.VideoEncoder.H264) {
                maxWidth = cap.mMaxFrameWidth;
                maxHeight = cap.mMaxFrameHeight;
                bitrate = cap.mMaxBitRate;
            }
        }

        int max = Math.max(maxWidth, maxHeight);
        int min = Math.min(maxWidth, maxHeight);
        int resConstraint = context.getResources().getInteger(
                R.integer.config_maxDimension);

        double ratio;
        boolean landscape = false;
        boolean resizeNeeded = false;

        // see if we need to resize

        // Figure orientation and ratio first
        if (width > height) {
            // landscape
            landscape = true;
            ratio = (double) width / (double) height;
            if (resConstraint >= 0 && height > resConstraint) {
                min = resConstraint;
            }
            if (width > max || height > min) {
                resizeNeeded = true;
            }
        } else {
            // portrait
            ratio = (double) height / (double) width;
            if (resConstraint >= 0 && width > resConstraint) {
                min = resConstraint;
            }
            if (height > max || width > min) {
                resizeNeeded = true;
            }
        }

        MediaFormat video = MediaFormat.createVideoFormat("video/avc", width, height);

        video.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        video.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        video.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        video.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3);

        // create a surface from the encoder
        Log.i(LOGTAG, "Starting encoder at " + width + "x" + height);
        try {
            venc = MediaCodec.createEncoderByType("video/avc");
        } catch (IOException e) {
            Log.wtf(LOGTAG, "Can't create AVC encoder!", e);
        }

        venc.configure(video, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface surface = venc.createInputSurface();
        venc.start();

        EncoderRunnable runnable = onSurfaceCreated(venc);
        new Thread(runnable, "Encoder").start();
        return surface;
    }

    abstract class EncoderRunnable implements Runnable {
        MediaCodec venc;

        EncoderRunnable(MediaCodec venc) {
            this.venc = venc;
        }

        abstract void encode() throws Exception;

        void cleanup() {
            destroyDisplaySurface(venc);
            venc = null;
        }

        @Override
        final public void run() {
            try {
                encode();
            } catch (Exception e) {
                Log.e(LOGTAG, "EncoderDevice error", e);
            } finally {
                cleanup();
                Log.i(LOGTAG, "=======ENCODING COMPLETE=======");
            }
        }
    }
}
