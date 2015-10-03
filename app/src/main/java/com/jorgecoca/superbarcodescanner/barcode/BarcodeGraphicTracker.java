package com.jorgecoca.superbarcodescanner.barcode;

import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.Tracker;
import com.google.android.gms.vision.barcode.Barcode;
import com.jorgecoca.superbarcodescanner.camera.GraphicOverlay;

public class BarcodeGraphicTracker extends Tracker<Barcode> {
    private GraphicOverlay<BarcodeGraphic> overlay;
    private BarcodeGraphic graphic;

    BarcodeGraphicTracker(GraphicOverlay<BarcodeGraphic> overlay, BarcodeGraphic graphic) {
        this.overlay = overlay;
        this.graphic = graphic;
    }

    @Override
    public void onNewItem(int id, Barcode item) {
        graphic.setID(id);
    }

    @Override
    public void onUpdate(Detector.Detections<Barcode> detections, Barcode item) {
        overlay.add(graphic);
        graphic.updateItem(item);
    }

    @Override
    public void onMissing(Detector.Detections<Barcode> detections) {
        overlay.remove(graphic);
    }

    @Override
    public void onDone() {
        overlay.remove(graphic);
    }
}
