package com.spektralab.android;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Test-APK SAF destination: no Kotlin runtime is available in this separate provider process. */
public class DiagnosticsFixtureProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.spektralab.android.test.diagnostics";
    private static final String[] DOC_COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};
    private volatile String failure = "";
    private File file() { return new File(getContext().getCacheDir(), "r05-report.zip"); }
    private String name() { return getContext().getSharedPreferences("r05-fixture", 0).getString("name", "report.zip"); }
    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES};
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            switch (columns[index]) {
                case Root.COLUMN_ROOT_ID: values[index] = "r05"; break;
                case Root.COLUMN_DOCUMENT_ID: values[index] = "root"; break;
                case Root.COLUMN_TITLE: values[index] = "Theia Audit Documents"; break;
                case Root.COLUMN_FLAGS: values[index] = Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY; break;
                case Root.COLUMN_MIME_TYPES: values[index] = "*/*"; break;
                case Root.COLUMN_AVAILABLE_BYTES: values[index] = 1024L * 1024 * 1024; break;
            }
        }
        cursor.addRow(values);
        return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOC_COLUMNS);
        row(cursor, id);
        return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) {
        if (!parent.equals("root")) throw new IllegalArgumentException();
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOC_COLUMNS);
        if (file().exists()) row(cursor, "report");
        return cursor;
    }
    private void row(MatrixCursor cursor, String id) {
        boolean root = id.equals("root");
        if (!root && !id.equals("report")) throw new IllegalArgumentException();
        String[] columns = cursor.getColumnNames();
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            switch (columns[index]) {
                case Document.COLUMN_DOCUMENT_ID: values[index] = id; break;
                case Document.COLUMN_DISPLAY_NAME: values[index] = root ? "Theia Audit Documents" : name(); break;
                case Document.COLUMN_MIME_TYPE: values[index] = root ? Document.MIME_TYPE_DIR : "application/zip"; break;
                case Document.COLUMN_FLAGS: values[index] = root ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE; break;
                case Document.COLUMN_SIZE: values[index] = root ? 0L : file().length(); break;
                case Document.COLUMN_LAST_MODIFIED: values[index] = file().lastModified(); break;
            }
        }
        cursor.addRow(values);
    }
    @Override public String createDocument(String parent, String mime, String displayName) throws FileNotFoundException {
        if (!parent.equals("root") || !mime.equals("application/zip") || !displayName.startsWith("Theia-diagnostics-")) throw new FileNotFoundException();
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file())) { stream.flush(); }
        catch (IOException failure) { throw new FileNotFoundException(failure.getMessage()); }
        getContext().getSharedPreferences("r05-fixture", 0).edit().putString("name", displayName).commit();
        return "report";
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!id.equals("report")) throw new FileNotFoundException();
        if (mode.contains("w")) {
            if (failure.equals("fail")) throw new FileNotFoundException("ENOSPC: audit destination unavailable");
            if (failure.equals("slow")) try { Thread.sleep(3000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        return ParcelFileDescriptor.open(file(), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public void deleteDocument(String id) { if (!id.equals("report")) throw new IllegalArgumentException(); file().delete(); }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (method.equals("audit-status")) {
            Bundle result = new Bundle();
            result.putBoolean("exists", file().exists());
            result.putLong("bytes", file().length());
            return result;
        }
        if (method.equals("audit-mode")) {
            if (arg == null || !(arg.isEmpty() || arg.equals("fail") || arg.equals("slow"))) throw new IllegalArgumentException();
            failure = arg;
            return Bundle.EMPTY;
        }
        return super.call(method, arg, extras);
    }
}
