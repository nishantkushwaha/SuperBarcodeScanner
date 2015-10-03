package com.jorgecoca.superbarcodescanner.barcode;

import com.google.android.gms.vision.MultiProcessor;
import com.google.android.gms.vision.Tracker;
import com.google.android.gms.vision.barcode.Barcode;
import com.jorgecoca.superbarcodescanner.camera.GraphicOverlay;

public class BarcodeTrackerFactory implements MultiProcessor.Factory<Barcode> {

    private GraphicOverlay<BarcodeGraphic> graphicOverlay;

    public BarcodeTrackerFactory(GraphicOverlay<BarcodeGraphic> graphicOverlay) {
        this.graphicOverlay = graphicOverlay;
    }

    @Override
    public Tracker<Barcode> create(Barcode barcode) {
        BarcodeGraphic graphic = new BarcodeGraphic(graphicOverlay);
        return new BarcodeGraphicTracker(graphicOverlay, graphic);
    }
}
