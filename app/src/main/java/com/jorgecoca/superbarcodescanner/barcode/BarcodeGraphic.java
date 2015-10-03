package com.jorgecoca.superbarcodescanner.barcode;


import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import com.google.android.gms.vision.barcode.Barcode;
import com.jorgecoca.superbarcodescanner.camera.GraphicOverlay;

public class BarcodeGraphic extends GraphicOverlay.Graphic {

    private int ID;
    private static int currentColorIndex = 0;
    private Paint rectPaint;
    private Paint textPaint;
    private volatile Barcode barcode;

    private static final int COLOR_CHOICES[] = {
            Color.BLUE,
            Color.CYAN,
            Color.GREEN
    };

    BarcodeGraphic(GraphicOverlay overlay) {
        super(overlay);
        currentColorIndex = (currentColorIndex + 1) % COLOR_CHOICES.length;
        final int selectedColor = COLOR_CHOICES[currentColorIndex];

        rectPaint = new Paint();
        rectPaint.setColor(selectedColor);
        rectPaint.setStyle(Paint.Style.STROKE);
        rectPaint.setStrokeWidth(4.0f);

        textPaint = new Paint();
        textPaint.setColor(selectedColor);
        textPaint.setTextSize(36.0f);
    }

    public int getID() {
        return ID;
    }

    public void setID(int ID) {
        this.ID = ID;
    }

    public Barcode getBarcode() {
        return barcode;
    }

    void updateItem(Barcode barcode) {
        this.barcode = barcode;
        postInvalidate();
    }

    @Override
    public void draw(Canvas canvas) {
        Barcode tmpBarcode = barcode;
        if (tmpBarcode == null) return;

        // draws the bouding box around the barcode
        RectF rect = new RectF(barcode.getBoundingBox());
        rect.left = translateX(rect.left);
        rect.top = translateY(rect.top);
        rect.right = translateX(rect.right);
        rect.bottom = translateY(rect.bottom);
        canvas.drawRect(rect, rectPaint);

        // draws the label with the value detected
        canvas.drawText(barcode.rawValue, rect.left, rect.bottom, textPaint);
    }
}
