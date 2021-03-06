/*
 *  Copyright 2016 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package com.bylijian.cameralibrary.webrtc;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import com.bylijian.cameralibrary.webrtc.CameraEnumerationAndroid.CaptureFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;


@SuppressWarnings("deprecation")
class Camera1Session implements CameraSession {
    private static final String TAG = "Camera1Session";
    private static final int NUMBER_OF_CAPTURE_BUFFERS = 3;

    private static final Histogram camera1StartTimeMsHistogram =
            Histogram.createCounts("WebRTC.Android.Camera1.StartTimeMs", 1, 10000, 50);
    private static final Histogram camera1StopTimeMsHistogram =
            Histogram.createCounts("WebRTC.Android.Camera1.StopTimeMs", 1, 10000, 50);
    private static final Histogram camera1ResolutionHistogram = Histogram.createEnumeration(
            "WebRTC.Android.Camera1.Resolution", CameraEnumerationAndroid.COMMON_RESOLUTIONS.size());
    private static Size previewSize;

    private static enum SessionState {RUNNING, STOPPED}

    private final Handler cameraThreadHandler;
    private final Events events;
    private final boolean captureToTexture;
    private final Context applicationContext;
    private final SurfaceTextureHelper surfaceTextureHelper;
    private final int cameraId;
    private final android.hardware.Camera camera;
    private final android.hardware.Camera.CameraInfo info;
    private final CaptureFormat captureFormat;
    // Used only for stats. Only used on the camera thread.
    private final long constructionTimeNs; // Construction time of this class.

    private SessionState state;
    private boolean firstFrameReported;

    // TODO(titovartem) make correct fix during webrtc:9175
    @SuppressWarnings("ByteBufferBackingArray")
    public static void create(final CreateSessionCallback callback, final Events events,
                              final boolean captureToTexture, final Context applicationContext,
                              final SurfaceTextureHelper surfaceTextureHelper, final int cameraId, final int width,
                              final int height, final int framerate) {
        final long constructionTimeNs = System.nanoTime();
        Log.d(TAG, "Open camera " + cameraId);
        events.onCameraOpening();

        final android.hardware.Camera camera;
        try {
            camera = android.hardware.Camera.open(cameraId);
        } catch (RuntimeException e) {
            callback.onFailure(FailureType.ERROR, e.getMessage());
            return;
        }

        if (camera == null) {
            callback.onFailure(FailureType.ERROR,
                    "android.hardware.Camera.open returned null for camera id = " + cameraId);
            return;
        }

        try {
            camera.setPreviewTexture(surfaceTextureHelper.getSurfaceTexture());
        } catch (IOException | RuntimeException e) {
            camera.release();
            callback.onFailure(FailureType.ERROR, e.getMessage());
            return;
        }

        final android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
        android.hardware.Camera.getCameraInfo(cameraId, info);

        final CaptureFormat captureFormat;
        try {
            final android.hardware.Camera.Parameters parameters = camera.getParameters();
            captureFormat = findClosestCaptureFormat(parameters, width, height, framerate);
            final Size pictureSize = findClosestPictureSize(parameters, width, height);
            updateCameraParameters(camera, parameters, captureFormat, pictureSize, captureToTexture);
        } catch (RuntimeException e) {
            camera.release();
            callback.onFailure(FailureType.ERROR, e.getMessage());
            return;
        }

        if (!captureToTexture) {
            final int frameSize = captureFormat.frameSize();
            for (int i = 0; i < NUMBER_OF_CAPTURE_BUFFERS; ++i) {
                final ByteBuffer buffer = ByteBuffer.allocateDirect(frameSize);
                camera.addCallbackBuffer(buffer.array());
            }
        }

        // Calculate orientation manually and send it as CVO insted.
        camera.setDisplayOrientation(0 /* degrees */);

        callback.onDone(new Camera1Session(events, captureToTexture, applicationContext,
                surfaceTextureHelper, cameraId, camera, info, captureFormat, constructionTimeNs));
    }

    private static void updateCameraParameters(android.hardware.Camera camera,
                                               android.hardware.Camera.Parameters parameters, CaptureFormat captureFormat, Size pictureSize,
                                               boolean captureToTexture) {
        final List<String> focusModes = parameters.getSupportedFocusModes();

        parameters.setPreviewFpsRange(captureFormat.framerate.min, captureFormat.framerate.max);
        parameters.setPreviewSize(captureFormat.width, captureFormat.height);
        Log.d(TAG, "setPreviewSize() width=" + captureFormat.width + "height=" + captureFormat.height);
        parameters.setPictureSize(pictureSize.width, pictureSize.height);
        Log.d(TAG, "setPictureSize() width=" + pictureSize.width + "height=" + pictureSize.height);
        if (!captureToTexture) {
            parameters.setPreviewFormat(captureFormat.imageFormat);
        }

        if (parameters.isVideoStabilizationSupported()) {
            parameters.setVideoStabilization(true);
        }
        if (focusModes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            parameters.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        }
        camera.setParameters(parameters);
    }

    private static CaptureFormat findClosestCaptureFormat(
            android.hardware.Camera.Parameters parameters, int width, int height, int framerate) {
        // Find closest supported format for |width| x |height| @ |framerate|.
        final List<CaptureFormat.FramerateRange> supportedFramerates =
                Camera1Enumerator.convertFramerates(parameters.getSupportedPreviewFpsRange());
        Log.d(TAG, "Available fps ranges: " + supportedFramerates);

        final CaptureFormat.FramerateRange fpsRange =
                CameraEnumerationAndroid.getClosestSupportedFramerateRange(supportedFramerates, framerate);

        previewSize = CameraEnumerationAndroid.getClosestSupportedSize(
                Camera1Enumerator.convertSizes(parameters.getSupportedPreviewSizes()), width, height);
        CameraEnumerationAndroid.reportCameraResolution(camera1ResolutionHistogram, previewSize);

        return new CaptureFormat(previewSize.width, previewSize.height, fpsRange);
    }

    private static Size findClosestPictureSize(
            android.hardware.Camera.Parameters parameters, int width, int height) {
        return CameraEnumerationAndroid.getClosestSupportedSize(
                Camera1Enumerator.convertSizes(parameters.getSupportedPictureSizes()), width, height);
    }

    private Camera1Session(Events events, boolean captureToTexture, Context applicationContext,
                           SurfaceTextureHelper surfaceTextureHelper, int cameraId, android.hardware.Camera camera,
                           android.hardware.Camera.CameraInfo info, CaptureFormat captureFormat,
                           long constructionTimeNs) {
        Log.d(TAG, "Create new camera1 session on camera " + cameraId);

        this.cameraThreadHandler = new Handler();
        this.events = events;
        this.captureToTexture = captureToTexture;
        this.applicationContext = applicationContext;
        this.surfaceTextureHelper = surfaceTextureHelper;
        this.cameraId = cameraId;
        this.camera = camera;
        this.info = info;
        this.captureFormat = captureFormat;
        this.constructionTimeNs = constructionTimeNs;

        surfaceTextureHelper.setTextureSize(captureFormat.width, captureFormat.height);

        startCapturing();
    }

    @Override
    public void focus(Rect ae, Rect af) {
        Log.d(TAG, "focus");
        if (camera != null) {
            final String focusMode = camera.getParameters().getFocusMode();
            Camera.Parameters parameters = camera.getParameters(); // 先获取当前相机的参数配置对象
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO); // 设置聚焦模式
            if (parameters.getMaxNumFocusAreas() > 0) {
                List<Camera.Area> focusAreas = new ArrayList<Camera.Area>();
                focusAreas.add(new Camera.Area(ae, 100));
                // 设置聚焦区域
                if (parameters.getMaxNumFocusAreas() > 0) {
                    parameters.setFocusAreas(focusAreas);
                }
                // 设置计量区域
                if (parameters.getMaxNumMeteringAreas() > 0) {
                    parameters.setMeteringAreas(focusAreas);
                }
                // 取消掉进程中所有的聚焦功能
                camera.cancelAutoFocus();
                camera.setParameters(parameters);
                camera.autoFocus(new Camera.AutoFocusCallback() {
                    @Override
                    public void onAutoFocus(boolean success, Camera camera) {
                        Log.d(TAG, "onAutoFocus()");
                        Camera.Parameters parame = camera.getParameters();
                        parame.setFocusMode(focusMode);
                        camera.setParameters(parame);
                    }
                });
            }
        }
    }

    @Override
    public void setZoom(int zoom) {
        Log.d(TAG, "setZoom() zoom=" + zoom);
        if (zoom < 1) {
            zoom = 1;
        } else if (zoom > 100) {
            zoom = 100;
        }
        if (camera != null) {
            Camera.Parameters parameters = camera.getParameters();
            if (parameters.isZoomSupported()) {
                List<Integer> ratios = parameters.getZoomRatios();
                int index = (int) (zoom * 1.0f / 100 * ratios.size());
                index = Math.min(index, ratios.size() - 1);
//                parameters.setZoom(ratios.get(index));
                parameters.setZoom(index);
                camera.setParameters(parameters);
            }
        }
    }

    @Override
    public Size getPreviewSize() {
        return previewSize == null ? new Size(0, 0) : previewSize;
    }

    @Override
    public CameraCharacteristics getCameraCharacteristics() {
        return null;
    }


    @Override
    public void stop() {
        Log.d(TAG, "Stop camera1 session on camera " + cameraId);
        checkIsOnCameraThread();
        if (state != SessionState.STOPPED) {
            final long stopStartTime = System.nanoTime();
            stopInternal();
            final int stopTimeMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStartTime);
            camera1StopTimeMsHistogram.addSample(stopTimeMs);
        }
    }

    private void startCapturing() {
        Log.d(TAG, "Start capturing");
        checkIsOnCameraThread();

        state = SessionState.RUNNING;

        camera.setErrorCallback(new android.hardware.Camera.ErrorCallback() {
            @Override
            public void onError(int error, android.hardware.Camera camera) {
                String errorMessage;
                if (error == android.hardware.Camera.CAMERA_ERROR_SERVER_DIED) {
                    errorMessage = "Camera server died!";
                } else {
                    errorMessage = "Camera error: " + error;
                }
                Log.e(TAG, errorMessage);
                stopInternal();
                if (error == android.hardware.Camera.CAMERA_ERROR_EVICTED) {
                    events.onCameraDisconnected(Camera1Session.this);
                } else {
                    events.onCameraError(Camera1Session.this, errorMessage);
                }
            }
        });

        if (captureToTexture) {
            listenForTextureFrames();
        } else {
            listenForBytebufferFrames();
        }
        try {
            camera.startPreview();
        } catch (RuntimeException e) {
            stopInternal();
            events.onCameraError(this, e.getMessage());
        }
    }

    private void stopInternal() {
        Log.d(TAG, "Stop internal");
        checkIsOnCameraThread();
        if (state == SessionState.STOPPED) {
            Log.d(TAG, "Camera is already stopped");
            return;
        }

        state = SessionState.STOPPED;
        surfaceTextureHelper.stopListening();
        // Note: stopPreview or other driver code might deadlock. Deadlock in
        // android.hardware.Camera._stopPreview(Native Method) has been observed on
        // Nexus 5 (hammerhead), OS version LMY48I.
        camera.stopPreview();
        camera.release();
        events.onCameraClosed(this);
        Log.d(TAG, "Stop done");
    }

    private void listenForTextureFrames() {
        surfaceTextureHelper.startListening((VideoFrame frame) -> {
            checkIsOnCameraThread();

            if (state != SessionState.RUNNING) {
                Log.d(TAG, "Texture frame captured but camera is no longer running.");
                return;
            }

            if (!firstFrameReported) {
                final int startTimeMs =
                        (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - constructionTimeNs);
                camera1StartTimeMsHistogram.addSample(startTimeMs);
                firstFrameReported = true;
            }

            // Undo the mirror that the OS "helps" us with.
            // http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
            final VideoFrame modifiedFrame = new VideoFrame(
                    CameraSession.createTextureBufferWithModifiedTransformMatrix(
                            (TextureBufferImpl) frame.getBuffer(),
                            /* mirror= */ info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT,
                            /* rotation= */ 0),
                    /* rotation= */ getFrameOrientation(), frame.getTimestampNs());
            events.onFrameCaptured(Camera1Session.this, modifiedFrame);
            modifiedFrame.release();
        });
    }

    private void listenForBytebufferFrames() {
        camera.setPreviewCallbackWithBuffer(new android.hardware.Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(final byte[] data, android.hardware.Camera callbackCamera) {
                checkIsOnCameraThread();

                if (callbackCamera != camera) {
                    Log.e(TAG, "Callback from a different camera. This should never happen.");
                    return;
                }

                if (state != SessionState.RUNNING) {
                    Log.d(TAG, "Bytebuffer frame captured but camera is no longer running.");
                    return;
                }

                final long captureTimeNs = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime());

                if (!firstFrameReported) {
                    final int startTimeMs =
                            (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - constructionTimeNs);
                    camera1StartTimeMsHistogram.addSample(startTimeMs);
                    firstFrameReported = true;
                }

                VideoFrame.Buffer frameBuffer = new NV21Buffer(
                        data, captureFormat.width, captureFormat.height, () -> cameraThreadHandler.post(() -> {
                    if (state == SessionState.RUNNING) {
                        camera.addCallbackBuffer(data);
                    }
                }));
                final VideoFrame frame = new VideoFrame(frameBuffer, getFrameOrientation(), captureTimeNs);
                events.onFrameCaptured(Camera1Session.this, frame);
                frame.release();
            }
        });
    }

    private int getFrameOrientation() {
        int rotation = CameraSession.getDeviceOrientation(applicationContext);
        if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK) {
            rotation = 360 - rotation;
        }
        return (info.orientation + rotation) % 360;
    }

    private void checkIsOnCameraThread() {
        if (Thread.currentThread() != cameraThreadHandler.getLooper().getThread()) {
            throw new IllegalStateException("Wrong thread");
        }
    }
}
