package com.jorgecoca.superbarcodescanner.camera;


import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;

import java.util.HashSet;
import java.util.Set;

public class GraphicOverlay<T extends GraphicOverlay.Graphic> extends View {

    private final Object lock = new Object();
    private int previewWidth;
    private float widthScaleFactor = 1.0f;
    private int previewHeight;
    private float heightScaleFactor = 1.0f;
    private int facing = CameraSource.CAMERA_FACING_BACK;
    private Set<T> graphics = new HashSet<>();
    private T firstGraphic;

    public GraphicOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void clear() {
        synchronized (lock) {
            graphics.clear();
            firstGraphic = null;
        }
        postInvalidate();
    }

    public void add(T graphic) {
        synchronized (lock) {
            graphics.add(graphic);
            if (firstGraphic == null) firstGraphic = graphic;
        }
        postInvalidate();
    }

    public void remove(T graphic) {
        synchronized (lock) {
            graphics.remove(graphic);
            if ((firstGraphic != null) && (firstGraphic.equals(graphic))) firstGraphic = null;
        }
        postInvalidate();
    }

    public T getFirstGraphic() {
        synchronized (lock) {
            return firstGraphic;
        }
    }

    public void setCameraInfo(int previewWidth, int previewHeight, int facing) {
        synchronized (lock) {
            this.previewWidth = previewWidth;
            this.previewHeight = previewHeight;
            this.facing = facing;
        }
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        synchronized (lock) {
            if ((previewWidth != 0) && (previewHeight != 0)) {
                widthScaleFactor = (float) canvas.getWidth() / (float) previewWidth;
                heightScaleFactor = (float) canvas.getHeight() / (float) previewHeight;
            }
            for (Graphic graphic : graphics) {
                graphic.draw(canvas);
            }
        }
    }

    public static abstract class Graphic {
        private GraphicOverlay overlay;

        public Graphic(GraphicOverlay overlay) {
            this.overlay = overlay;
        }

        public abstract void draw(Canvas canvas);

        public float scaleX(float horizontal) {
            return horizontal * overlay.widthScaleFactor;
        }

        public float scaleY(float vertical) {
            return vertical * overlay.heightScaleFactor;
        }

        public float translateX(float x) {
            if (overlay.facing == CameraSource.CAMERA_FACING_FRONT) {
                return overlay.getWidth() - scaleX(x);
            } else {
                return scaleX(x);
            }
        }

        public float translateY(float y) {
            return scaleX(y);
        }

        public void postInvalidate() {
            overlay.postInvalidate();
        }
    }
}
