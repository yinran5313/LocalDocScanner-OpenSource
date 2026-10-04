"""One-time, checked source transformation for Office writeback failure propagation."""
from pathlib import Path
p = Path(__file__).resolve().parents[1] / 'office-engine/src/main/java/org/libreoffice/androidlib/LOActivity.java'
s = p.read_text(encoding='utf-8')
a = s.index('    private void copyTempBackToIntent() {')
b = s.index('    /** Tell the user that the document still holds', a)
s = s[:a] + '''    private boolean copyTempBackToIntent() {
        if (!isDocEditable || mTempFile == null)
            return true;
        if (mResolvedFile != null)
            return copyTempBackToFile();
        Uri uri = getIntent().getData();
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()))
            return true;
        try {
            if (mTempFile.length() <= 0) throw new IOException("Empty saved document");
            OutputStream target;
            try { target = getContentResolver().openOutputStream(uri, "wt"); }
            catch (FileNotFoundException e) { target = getContentResolver().openOutputStream(uri); }
            if (target == null) throw new IOException("Cannot open output URI");
            try (InputStream input = new FileInputStream(mTempFile); OutputStream output = target) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                output.flush();
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "copyTempBackToIntent failed", e);
            reportSaveToFileFailed();
            return false;
        }
    }

    /** Atomic replacement keeps the previous work copy intact if writing fails. */
    private boolean copyTempBackToFile() {
        final File resolvedFile = mResolvedFile;
        if (resolvedFile == null) return false;
        File newContent = new File(resolvedFile.getParentFile(), "." + resolvedFile.getName() + ".part");
        try {
            if (mTempFile.length() <= 0) throw new IOException("Empty saved document");
            try (InputStream input = new FileInputStream(mTempFile);
                 FileOutputStream output = new FileOutputStream(newContent)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                output.flush();
                output.getFD().sync();
            }
            if (!newContent.renameTo(resolvedFile)) throw new IOException("Cannot replace work copy");
            MediaScannerConnection.scanFile(this, new String[]{resolvedFile.getAbsolutePath()}, null, null);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "copyTempBackToFile failed", e);
            newContent.delete();
            reportSaveToFileFailed();
            return false;
        }
    }

    /** Retain edited bytes outside the cache before the office process exits. */
    private String retainRecoveryCopy() {
        try {
            File dir = new File(getFilesDir(), "office-recovery");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create recovery directory");
            String name = mResolvedFile != null ? mResolvedFile.getName() : mTempFile.getName();
            File saved = new File(dir, System.currentTimeMillis() + "-" + name);
            try (InputStream input = new FileInputStream(mTempFile);
                 FileOutputStream output = new FileOutputStream(saved)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                output.flush();
                output.getFD().sync();
            }
            return saved.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Recovery copy could not be written", e);
            return mTempFile != null ? mTempFile.getAbsolutePath() : "";
        }
    }

''' + s[b:]
old='''                copyTempBackToIntent();
                setResult(RESULT_OK);'''
assert old in s
s=s.replace(old, '''                boolean saved = copyTempBackToIntent();
                Intent receipt = new Intent();
                receipt.putExtra("localdoc_save_failed", !saved);
                if (!saved) receipt.putExtra("localdoc_recovery_path", retainRecoveryCopy());
                setResult(saved ? RESULT_OK : RESULT_FIRST_USER, receipt);''', 1)
p.write_text(s, encoding='utf-8')
print('Office writeback now propagates failures and retains recovery bytes.')
