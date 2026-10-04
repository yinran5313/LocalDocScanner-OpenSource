package org.libreoffice.androidlib;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.util.UUID;

/** A cross-process, atomic receipt consumed by the main app after engine export. */
public final class OfficeExportReceipt {
    public static void retain(Context context, File source, Uri destination) {
        try {
            File dir = new File(context.getFilesDir(), "office-export-receipts");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create export directory");
            String id = UUID.randomUUID().toString();
            File output = new File(dir, id + "-" + source.getName());
            try (InputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(output)) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.getFD().sync();
            }
            JSONObject json = new JSONObject();
            json.put("file", output.getAbsolutePath());
            json.put("uri", destination.toString());
            File part = new File(dir, id + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.getFD().sync();
            }
            if (!part.renameTo(new File(dir, id + ".json"))) throw new IOException("Cannot register export");
        } catch (Exception e) { android.util.Log.e("OfficeExportReceipt", "Cannot retain export receipt", e); }
    }
}
