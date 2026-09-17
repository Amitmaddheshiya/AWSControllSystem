package com.tvadsplayer;

import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.annotation.NonNull;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native, streaming PDF-to-JPEG converter for nvsign sections. */
public class PdfConversionModule extends ReactContextBaseJavaModule {
    private static final String TAG = "PdfConversionModule";
    private static final int MAX_RENDER_DIMENSION = 2048;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    PdfConversionModule(ReactApplicationContext context) { super(context); }
    @NonNull @Override public String getName() { return "PdfConversionModule"; }

    @ReactMethod public void convertSectionPdfs(String nvsignPath, Promise promise) {
        executor.execute(() -> {
            try { promise.resolve(convertPdfs(new File(nvsignPath))); }
            catch (Exception error) { Log.e(TAG, "PDF conversion scan failed", error); promise.reject("PDF_CONVERSION_SCAN_FAILED", error); }
        });
    }
    @ReactMethod public void addListener(String eventName) { }
    @ReactMethod public void removeListeners(double count) { }

    private int convertPdfs(File root) {
        if (!root.isDirectory()) return 0;
        File[] sections = root.listFiles(file -> file.isDirectory() && file.getName().toLowerCase(Locale.ROOT).matches("section[123]"));
        if (sections == null) return 0;
        Arrays.sort(sections, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        int count = 0;
        boolean foundPdf = false;
        boolean allSuccessful = true;
        for (File section : sections) {
            File[] pdfs = section.listFiles(file -> file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".pdf"));
            if (pdfs == null) continue;
            Arrays.sort(pdfs, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File pdf : pdfs) { foundPdf = true; if (convertPdf(pdf)) count++; else allSuccessful = false; }
        }
        if (foundPdf) emitProgress(allSuccessful ? "complete" : "failed", "", allSuccessful ? 100 : 0, 0, 0);
        return count;
    }

    private boolean convertPdf(File pdf) {
        String base = pdf.getName().substring(0, pdf.getName().length() - 4);
        File section = pdf.getParentFile();
        File staging = new File(section, "." + base + ".pdf-rendering");
        deleteRecursively(staging); // An interrupted attempt always leaves the original PDF intact.
        if (!staging.mkdirs()) return false;
        ParcelFileDescriptor descriptor = null;
        PdfRenderer renderer = null;
        List<File> pages = new ArrayList<>();
        try {
            descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(descriptor);
            int total = renderer.getPageCount();
            if (total < 1) throw new IOException("PDF has no pages");
            emitProgress("rendering", pdf.getName(), 0, 0, total);
            int digits = Math.max(3, String.valueOf(total).length());
            for (int i = 0; i < total; i++) {
                PdfRenderer.Page page = renderer.openPage(i);
                Bitmap bitmap = null;
                try {
                    float scale = Math.min(1f, (float) MAX_RENDER_DIMENSION / Math.max(page.getWidth(), page.getHeight()));
                    bitmap = Bitmap.createBitmap(Math.max(1, Math.round(page.getWidth() * scale)), Math.max(1, Math.round(page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(0xFFFFFFFF);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    File out = new File(staging, String.format(Locale.ROOT, "%s_%0" + digits + "d.jpg", base, i + 1));
                    try (FileOutputStream stream = new FileOutputStream(out)) {
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) throw new IOException("Could not encode page " + (i + 1));
                    }
                    if (out.length() == 0) throw new IOException("Empty rendered page");
                    pages.add(out);
                    emitProgress("rendering", pdf.getName(), Math.round(((i + 1) * 100f) / total), i + 1, total);
                } finally { if (bitmap != null) bitmap.recycle(); page.close(); }
            }
            if (pages.size() != total) throw new IOException("Rendered page count mismatch");
            for (File page : pages) if (!page.isFile() || page.length() == 0) throw new IOException("Missing rendered page");
            for (File page : pages) {
                File target = new File(section, page.getName());
                if (target.exists() && !target.delete()) throw new IOException("Cannot replace " + target.getName());
                if (!page.renameTo(target)) throw new IOException("Cannot publish " + target.getName());
            }
            if (!pdf.delete()) throw new IOException("Rendered pages but could not delete source PDF");
            Log.i(TAG, "Converted " + pdf + " into " + total + " JPG pages");
            return true;
        } catch (Exception error) {
            Log.e(TAG, "Keeping PDF after conversion failure: " + pdf, error);
            return false;
        } finally {
            if (renderer != null) renderer.close();
            if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
            deleteRecursively(staging);
        }
    }

    private void deleteRecursively(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) deleteRecursively(child); }
        if (!file.delete()) Log.w(TAG, "Could not clean temporary file " + file);
    }

    private void emitProgress(String status, String file, int percent, int currentPage, int totalPages) {
        WritableMap event = Arguments.createMap();
        event.putString("status", status); event.putString("file", file);
        event.putInt("percent", percent); event.putInt("currentPage", currentPage); event.putInt("totalPages", totalPages);
        getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit("pdfConversionProgress", event);
    }
}
