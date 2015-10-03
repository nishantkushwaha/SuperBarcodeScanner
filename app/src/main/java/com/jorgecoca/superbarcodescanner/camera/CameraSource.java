package com.jorgecoca.superbarcodescanner.camera;


import android.Manifest;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Build;
import android.os.SystemClock;
import android.support.annotation.RequiresPermission;
import android.support.annotation.StringDef;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import com.google.android.gms.common.images.Size;
import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.Frame;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CameraSource {

    public static final int CAMERA_FACING_BACK = Camera.CameraInfo.CAMERA_FACING_BACK;
    public static final int CAMERA_FACING_FRONT = Camera.CameraInfo.CAMERA_FACING_FRONT;

    private static final int DUMMY_TEXTURE_NAME = 100;
    private static final float ASPECT_RATIO_TOLERANCE = 0.01f;

    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
            Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE,
            Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO,
            Camera.Parameters.FOCUS_MODE_AUTO,
            Camera.Parameters.FOCUS_MODE_EDOF,
            Camera.Parameters.FOCUS_MODE_FIXED,
            Camera.Parameters.FOCUS_MODE_INFINITY,
            Camera.Parameters.FOCUS_MODE_MACRO
    })
    private @interface FocusMode {}

    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
            Camera.Parameters.FLASH_MODE_ON,
            Camera.Parameters.FLASH_MODE_OFF,
            Camera.Parameters.FLASH_MODE_AUTO,
            Camera.Parameters.FLASH_MODE_RED_EYE,
            Camera.Parameters.FLASH_MODE_TORCH
    })
    private @interface FlashMode {}

    private Context context;
    private final Object cameraLock = new Object();
    private Camera camera;
    private int facing = CAMERA_FACING_BACK;
    private int rotation;
    private Size previewSize;
    private float requestedFps = 30.0f;
    private int requestedPreviewWidth = 1024;
    private int requestedPreviewHeight = 768;
    private String focusMode = null;
    private String flashMode = null;

    private SurfaceView dummySurfaceView;
    private SurfaceTexture dummySurfaceTexture;
    private Thread processingThread;
    private FrameProcessingRunnable frameProcessor;

    private Map<byte[], ByteBuffer> bytesToByteBuffer = new HashMap<>();

    // allow only creation via builder class
    private CameraSource() { }

    public static class Builder {
        private final Detector<?> detector;
        private CameraSource cameraSource = new CameraSource();

        public Builder(Context context, Detector<?> detector) {
            if (context == null) throw new IllegalArgumentException("No context supplied");
            if (detector == null) throw new IllegalArgumentException("No detector supplied");

            this.detector = detector;
            cameraSource.context = context;
        }

        public Builder setRequestedFps(float fps) {
            if (fps <= 0) throw new IllegalArgumentException("Invalid fps: " + fps);
            cameraSource.requestedFps = fps;
            return this;
        }

        public Builder setFocusMode(@FocusMode String mode) {
            cameraSource.focusMode = mode;
            return this;
        }

        public Builder setFlashMode(@FocusMode String mode) {
            cameraSource.flashMode = mode;
            return this;
        }

        public Builder setRequestedPreviewSize(int width, int height) {
            final int MAX = 1000000;
            if ((width <= 0) || (width > MAX) || (height <= 0) || (height > MAX)) {
                throw  new IllegalArgumentException("Invalid preview size: " + width + "x" + height);
            }
            cameraSource.requestedPreviewWidth = width;
            cameraSource.requestedPreviewHeight = height;
            return this;
        }

        public Builder setFacing(int facing) {
            if ((facing != CAMERA_FACING_BACK) && (facing != CAMERA_FACING_FRONT)) {
                throw  new IllegalArgumentException("Invalid facing camera: " + facing);
            }
            cameraSource.facing = facing;
            return this;
        }

        public CameraSource build() {
            cameraSource.frameProcessor = cameraSource.new FrameProcessingRunnable(detector);
            return cameraSource;
        }

    }

    public void release() {
        synchronized (cameraLock) {
            stop();
            frameProcessor.release();
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    public CameraSource start() throws IOException {
        synchronized (cameraLock) {
            if (camera != null) return this;

            camera = createCamera();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                dummySurfaceTexture = new SurfaceTexture(DUMMY_TEXTURE_NAME);
                camera.setPreviewTexture(dummySurfaceTexture);
            } else {
                dummySurfaceView = new SurfaceView(context);
                camera.setPreviewDisplay(dummySurfaceView.getHolder());
            }

            camera.startPreview();
            processingThread = new Thread(frameProcessor);
            frameProcessor.setActive(true);
            processingThread.start();
        }
        return this;
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    public CameraSource start(SurfaceHolder surfaceHolder) throws IOException {
        synchronized (cameraLock) {
            if (camera != null)  return this;

            camera = createCamera();
            camera.setPreviewDisplay(surfaceHolder);
            camera.startPreview();

            processingThread = new Thread(frameProcessor);
            frameProcessor.setActive(true);
            processingThread.start();
        }
        return this;
    }

    public void stop() {
        synchronized (cameraLock) {
            frameProcessor.setActive(false);
            if (processingThread != null) {
                try {
                    processingThread.join();
                } catch (InterruptedException e) {
                    Log.d("BARCODER", "Frame processing thread interrupted on release.");
                }
                processingThread = null;
            }
            if (camera != null) {
                camera.stopPreview();
                camera.setPreviewCallbackWithBuffer(null);
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                        camera.setPreviewTexture(null);
                    } else {
                        camera.setPreviewDisplay(null);
                    }
                } catch (Exception e) {
                    Log.e("BARCODER", "Failed to clear camera preview: " + e);
                }
                camera.release();
                camera = null;
            }
        }
    }

    public int getCameraFacing() {
        return facing;
    }

    public Size getPreviewSize() {
        return previewSize;
    }


    public int doZoom(float scale) {
        synchronized (cameraLock) {
            if (camera == null) {
                return 0;
            }
            int currentZoom = 0;
            int maxZoom;
            Camera.Parameters parameters = camera.getParameters();
            if (!parameters.isZoomSupported()) {
                Log.w("BARCODER", "Zoom is not supported on this device");
                return currentZoom;
            }
            maxZoom = parameters.getMaxZoom();

            currentZoom = parameters.getZoom() + 1;
            float newZoom;
            if (scale > 1) {
                newZoom = currentZoom + scale * (maxZoom / 10);
            } else {
                newZoom = currentZoom * scale;
            }
            currentZoom = Math.round(newZoom) - 1;
            if (currentZoom < 0) {
                currentZoom = 0;
            } else if (currentZoom > maxZoom) {
                currentZoom = maxZoom;
            }
            parameters.setZoom(currentZoom);
            camera.setParameters(parameters);
            return currentZoom;
        }
    }

    private Camera createCamera() {
        int requestedCameraID = getIDForRequestedCamera(facing);
        if (requestedCameraID == -1) throw new RuntimeException("Could not find request camera");

        Camera camera = Camera.open(requestedCameraID);
        SizePair sizePair = selectSizePair(camera, requestedPreviewWidth, requestedPreviewHeight);
        if (sizePair == null) throw new RuntimeException("Could not find suitable preview size");

        Size pictureSize = sizePair.pictureSize();
        previewSize = sizePair.previewSize();

        int[] previewFpsRange = selectPreviewFpsRange(camera, requestedFps);
        if (previewFpsRange == null) throw new RuntimeException("Could not find suitable preview frames per second range");

        Camera.Parameters parameters = camera.getParameters();
        parameters.setPictureSize(pictureSize.getWidth(), pictureSize.getHeight());
        parameters.setPreviewSize(previewSize.getWidth(), previewSize.getHeight());
        parameters.setPreviewFpsRange(
                previewFpsRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                previewFpsRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);
        parameters.setPreviewFormat(ImageFormat.NV21);

        setRotation(camera, parameters, requestedCameraID);

        if (focusMode != null) {
            if (parameters.getSupportedFlashModes().contains(focusMode)) {
                parameters.setFlashMode(focusMode);
            } else {
                Log.d("BARCODER", "Camera focus mode: " + focusMode + " is not supported");
            }
        }

        if (flashMode != null) {
            if (parameters.getSupportedFlashModes().contains(flashMode)) {
                parameters.setFlashMode(flashMode);
            } else {
                Log.i("BARCODER", "Camera flash mode: " + flashMode + " is not supported on this device.");
            }
        }

        camera.setParameters(parameters);

        // Four frame buffers are needed for working with the camera:
        //
        //   one for the frame that is currently being executed upon in doing detection
        //   one for the next pending frame to process immediately upon completing detection
        //   two for the frames that the camera uses to populate future preview images
        camera.setPreviewCallbackWithBuffer(new CameraPreviewCallback());
        camera.addCallbackBuffer(createPreviewBuffer(previewSize));
        camera.addCallbackBuffer(createPreviewBuffer(previewSize));
        camera.addCallbackBuffer(createPreviewBuffer(previewSize));
        camera.addCallbackBuffer(createPreviewBuffer(previewSize));

        return camera;
    }

    private static int getIDForRequestedCamera(int facing) {
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == facing) {
                return i;
            }
        }
        return -1;
    }

    private static SizePair selectSizePair(Camera camera, int desiredWidth, int desiredHeight) {
        List<SizePair> validPreviewSizes = generateValidPreviewSizeList(camera);

        // The method for selecting the best size is to minimize the sum of the differences between
        // the desired values and the actual values for width and height.  This is certainly not the
        // only way to select the best size, but it provides a decent tradeoff between using the
        // closest aspect ratio vs. using the closest pixel area.
        SizePair selectedPair = null;
        int minDiff = Integer.MAX_VALUE;
        for (SizePair sizePair : validPreviewSizes) {
            Size size = sizePair.previewSize();
            int diff = Math.abs(size.getWidth() - desiredWidth) +
                    Math.abs(size.getHeight() - desiredHeight);
            if (diff < minDiff) {
                selectedPair = sizePair;
                minDiff = diff;
            }
        }

        return selectedPair;
    }

    private static List<SizePair> generateValidPreviewSizeList(Camera camera) {
        Camera.Parameters parameters = camera.getParameters();
        List<android.hardware.Camera.Size> supportedPreviewSizes = parameters.getSupportedPreviewSizes();
        List<android.hardware.Camera.Size> supportedPictureSizes = parameters.getSupportedPictureSizes();
        List<SizePair> validPreviewSizes = new ArrayList<>();
        for (android.hardware.Camera.Size previewSize : supportedPreviewSizes) {
            float previewAspectRatio = (float) previewSize.width / (float) previewSize.height;
            // By looping through the picture sizes in order, we favor the higher resolutions.
            // We choose the highest resolution in order to support taking the full resolution
            // picture later.
            for (android.hardware.Camera.Size pictureSize : supportedPictureSizes) {
                float pictureAspectRatio = (float) pictureSize.width / (float) pictureSize.height;
                if (Math.abs(previewAspectRatio - pictureAspectRatio) < ASPECT_RATIO_TOLERANCE) {
                    validPreviewSizes.add(new SizePair(previewSize, pictureSize));
                    break;
                }
            }
        }

        // If there are no picture sizes with the same aspect ratio as any preview sizes, allow all
        // of the preview sizes and hope that the camera can handle it.  Probably unlikely, but we
        // still account for it.
        if (validPreviewSizes.size() == 0) {
            Log.w("BARCODER", "No preview sizes have a corresponding same-aspect-ratio picture size");
            for (android.hardware.Camera.Size previewSize : supportedPreviewSizes) {
                // The null picture size will let us know that we shouldn't set a picture size.
                validPreviewSizes.add(new SizePair(previewSize, null));
            }
        }

        return validPreviewSizes;
    }

    private int[] selectPreviewFpsRange(Camera camera, float desiredPreviewFps) {
        // The camera API uses integers scaled by a factor of 1000 instead of floating-point frame
        // rates.
        int desiredPreviewFpsScaled = (int) (desiredPreviewFps * 1000.0f);

        // The method for selecting the best range is to minimize the sum of the differences between
        // the desired value and the upper and lower bounds of the range.  This may select a range
        // that the desired value is outside of, but this is often preferred.  For example, if the
        // desired frame rate is 29.97, the range (30, 30) is probably more desirable than the
        // range (15, 30).
        int[] selectedFpsRange = null;
        int minDiff = Integer.MAX_VALUE;
        List<int[]> previewFpsRangeList = camera.getParameters().getSupportedPreviewFpsRange();
        for (int[] range : previewFpsRangeList) {
            int deltaMin = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MIN_INDEX];
            int deltaMax = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MAX_INDEX];
            int diff = Math.abs(deltaMin) + Math.abs(deltaMax);
            if (diff < minDiff) {
                selectedFpsRange = range;
                minDiff = diff;
            }
        }
        return selectedFpsRange;
    }

    private void setRotation(Camera camera, Camera.Parameters parameters, int cameraId) {
        WindowManager windowManager =
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int degrees = 0;
        int rotation = windowManager.getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                Log.e("BARCODER", "Bad rotation value: " + rotation);
        }

        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, cameraInfo);

        int angle;
        int displayAngle;
        if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            angle = (cameraInfo.orientation + degrees) % 360;
            displayAngle = (360 - angle); // compensate for it being mirrored
        } else {  // back-facing
            angle = (cameraInfo.orientation - degrees + 360) % 360;
            displayAngle = angle;
        }

        // This corresponds to the rotation constants in {@link Frame}.
        this.rotation = angle / 90;

        camera.setDisplayOrientation(displayAngle);
        parameters.setRotation(angle);
    }

    private byte[] createPreviewBuffer(Size previewSize) {
        int bitsPerPixel = ImageFormat.getBitsPerPixel(ImageFormat.NV21);
        long sizeInBits = previewSize.getHeight() * previewSize.getWidth() * bitsPerPixel;
        int bufferSize = (int) Math.ceil(sizeInBits / 8.0d) + 1;

        //
        // NOTICE: This code only works when using play services v. 8.1 or higher.
        //

        // Creating the byte array this way and wrapping it, as opposed to using .allocate(),
        // should guarantee that there will be an array to work with.
        byte[] byteArray = new byte[bufferSize];
        ByteBuffer buffer = ByteBuffer.wrap(byteArray);
        if (!buffer.hasArray() || (buffer.array() != byteArray)) {
            // I don't think that this will ever happen.  But if it does, then we wouldn't be
            // passing the preview content to the underlying detector later.
            throw new IllegalStateException("Failed to create valid buffer for camera source.");
        }

        bytesToByteBuffer.put(byteArray, buffer);
        return byteArray;
    }


    public interface ShutterCallback {
        void onShutter();
    }

    public interface PictureCallback {
        void onPictureTaken(byte[] data);
    }

    private class CameraPreviewCallback implements Camera.PreviewCallback {
        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            frameProcessor.setNextFrame(data, camera);
        }
    }

    private static class SizePair {
        private Size preview;
        private Size picture;

        public SizePair(android.hardware.Camera.Size previewSize, android.hardware.Camera.Size pictureSize) {
            preview = new Size(previewSize.width, previewSize.height);
            picture = new Size(pictureSize.width, pictureSize.height);
        }

        public Size previewSize() {
            return preview;
        }

        public Size pictureSize() {
            return picture;
        }
    }

    private class FrameProcessingRunnable implements Runnable {
        private Detector<?> detector;
        private long startTimeMillis = SystemClock.elapsedRealtime();
        private final Object lock = new Object();
        private boolean active = true;

        private long pendingTimeMillis;
        private int pendingFrameID = 0;
        private ByteBuffer pendingFrameData;

        FrameProcessingRunnable(Detector<?> detector) {
            this.detector = detector;
        }

        void release() {
            assert(processingThread.getState() == Thread.State.TERMINATED);
            detector.release();
            detector = null;
        }

        void setActive(boolean active) {
            synchronized (lock) {
                this.active = active;
                lock.notifyAll();
            }
        }

        void setNextFrame(byte[] data, Camera camera) {
            synchronized (lock) {
                if (pendingFrameData != null) {
                    camera.addCallbackBuffer(pendingFrameData.array());
                    pendingFrameData = null;
                }
                pendingTimeMillis = SystemClock.elapsedRealtime() - startTimeMillis;
                pendingFrameID++;
                pendingFrameData = bytesToByteBuffer.get(data);
                lock.notifyAll();
            }
        }

        @Override
        public void run() {
            Frame outputFrame;
            ByteBuffer data;

            while (true) {
                synchronized (lock) {
                    if (active && (pendingFrameData == null)) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            Log.d("BARCODER", e.getMessage());
                            return;
                        }
                    }
                    if (! active) {
                        return;
                    }

                    outputFrame = new Frame.Builder()
                            .setImageData(pendingFrameData, previewSize.getWidth(), previewSize.getHeight(), ImageFormat.NV21)
                            .setId(pendingFrameID)
                            .setTimestampMillis(pendingTimeMillis)
                            .setRotation(rotation)
                            .build();

                    data = pendingFrameData;
                    pendingFrameData = null;
                }

                try {
                    detector.receiveFrame(outputFrame);
                } catch (Throwable t) {
                    Log.e("BARCODER", "Exception thrown from receiver", t);
                } finally {
                    camera.addCallbackBuffer(data.array());
                }
            }
        }
    }
}
