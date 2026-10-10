package com.punchup.timestampcam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 時間浮水印相機：呼叫手機內建相機拍照 → 加上日期時間＋GPS 地點浮水印 → 存到相簿 Pictures/TimeStamp。
 */
public class MainActivity extends Activity implements LocationListener {

    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_PERM = 2001;
    private static final String KEY_FILE = "pending_file";
    private static final String ALBUM = "TimeStamp";

    private LocationManager lm;
    private volatile Location bestLoc;
    private volatile String address;
    private volatile Location addressLoc;
    private volatile boolean geocoding;

    private File pendingFile;
    private boolean busy;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final ExecutorService geoExec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private ImageView preview;
    private Button shoot;
    private CheckBox burst;

    // ───────────────────────── 生命週期 ─────────────────────────

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null && saved.getString(KEY_FILE) != null) {
            pendingFile = new File(saved.getString(KEY_FILE));
        }
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        buildUi();
        requestNeededPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startLocation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { lm.removeUpdates(this); } catch (Exception ignored) { }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingFile != null) out.putString(KEY_FILE, pendingFile.getAbsolutePath());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdown();
        geoExec.shutdown();
    }

    // ───────────────────────── 畫面 ─────────────────────────

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        final int p = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111111);
        root.setPadding(p, p, p, p);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @SuppressWarnings("deprecation")
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                v.setPadding(p, p + in.getSystemWindowInsetTop(), p, p + in.getSystemWindowInsetBottom());
                return in;
            }
        });

        TextView title = new TextView(this);
        title.setText("時間浮水印相機");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(0xFFFFC107);
        status.setTextSize(14);
        status.setPadding(0, dp(6), 0, dp(10));
        status.setText("定位中…");
        root.addView(status);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(0xFF000000);
        root.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText("照片存到：相簿 › Pictures/" + ALBUM);
        hint.setTextColor(0xFF9E9E9E);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, dp(4));
        root.addView(hint);

        burst = new CheckBox(this);
        burst.setText("連拍模式（存好後自動再開相機）");
        burst.setTextColor(0xFFFFFFFF);
        root.addView(burst);

        shoot = new Button(this);
        shoot.setText("拍照");
        shoot.setTextSize(22);
        shoot.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { takePhoto(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(72));
        lp.topMargin = dp(8);
        root.addView(shoot, lp);

        setContentView(root);
    }

    private void setBusy(boolean b) {
        busy = b;
        shoot.setEnabled(!b);
        shoot.setText(b ? "處理中…" : "拍照");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ───────────────────────── 權限 ─────────────────────────

    private boolean granted(String perm) {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPerm() {
        return granted(Manifest.permission.ACCESS_FINE_LOCATION)
                || granted(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> need = new ArrayList<>();
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT <= 28 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_PERM) startLocation();
    }

    // ───────────────────────── 定位 ─────────────────────────

    @SuppressLint("MissingPermission")
    private void startLocation() {
        if (!hasLocationPerm()) {
            status.setText("⚠ 未允許定位權限，浮水印將沒有地點（可到手機設定開啟）");
            return;
        }
        boolean anyEnabled = false;
        String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};
        for (String pv : providers) {
            try {
                if (!lm.isProviderEnabled(pv)) continue;
                anyEnabled = true;
                Location last = lm.getLastKnownLocation(pv);
                if (last != null) onLocationChanged(last);
                lm.requestLocationUpdates(pv, 2000, 0, this, Looper.getMainLooper());
            } catch (SecurityException | IllegalArgumentException ignored) {
                // 只給「大概位置」權限時，部分舊手機不允許使用 GPS，略過即可
            }
        }
        if (!anyEnabled) {
            status.setText("⚠ 手機的定位功能沒有開啟");
        } else if (bestLoc == null) {
            status.setText("定位中…（第一次在室內可能要等一下）");
        }
    }

    @Override
    public void onLocationChanged(Location loc) {
        if (isBetter(loc, bestLoc)) {
            bestLoc = loc;
            maybeGeocode(loc);
            updateStatus();
        }
    }

    // 舊版 Android 介面方法需自行實作
    @Override public void onStatusChanged(String provider, int s, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }

    private static boolean isBetter(Location loc, Location cur) {
        if (cur == null) return true;
        long dt = loc.getTime() - cur.getTime();
        if (dt > 60_000) return true;
        if (dt < -60_000) return false;
        float da = loc.getAccuracy() - cur.getAccuracy();
        if (da < 0) return true;
        return dt > 0 && da <= 20;
    }

    private void maybeGeocode(final Location loc) {
        if (!Geocoder.isPresent() || geocoding) return;
        Location a = addressLoc;
        if (a != null && address != null && a.distanceTo(loc) < 30) return;
        geocoding = true;
        geoExec.execute(new Runnable() {
            @Override public void run() {
                String s = geocode(loc);
                if (s != null) { address = s; addressLoc = loc; }
                geocoding = false;
                ui.post(new Runnable() { @Override public void run() { updateStatus(); } });
            }
        });
    }

    @SuppressWarnings("deprecation")
    private String geocode(Location loc) {
        try {
            List<Address> r = new Geocoder(this, Locale.TAIWAN)
                    .getFromLocation(loc.getLatitude(), loc.getLongitude(), 1);
            if (r != null && !r.isEmpty()) {
                String line = r.get(0).getAddressLine(0);
                if (line != null) {
                    return line.replaceFirst("^\\d{3,6}", "").replace("台灣", "").trim();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void updateStatus() {
        Location l = bestLoc;
        if (l == null) return;
        String src = LocationManager.GPS_PROVIDER.equals(l.getProvider()) ? "GPS" : "網路";
        String place = address != null ? address
                : String.format(Locale.US, "%.5f, %.5f", l.getLatitude(), l.getLongitude());
        status.setText("📍 " + src + " 定位 ±" + Math.round(l.getAccuracy()) + "m\n" + place);
    }

    // ───────────────────────── 拍照 ─────────────────────────

    private void takePhoto() {
        if (busy) return;
        try {
            File dir = new File(getCacheDir(), "shots");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            pendingFile = new File(dir, "cap_" + System.currentTimeMillis() + ".jpg");
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pendingFile);
            Intent it = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            it.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            it.setClipData(ClipData.newRawUri("output", uri));
            it.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(it, REQ_CAPTURE);
        } catch (ActivityNotFoundException e) {
            toast("找不到手機的相機 App");
        } catch (Exception e) {
            toast("無法開啟相機：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAPTURE) return;
        final File f = pendingFile;
        pendingFile = null;
        if (res != RESULT_OK || f == null || !f.exists() || f.length() == 0) {
            if (f != null) //noinspection ResultOfMethodCallIgnored
                f.delete();
            return;
        }
        final Location loc = bestLoc;
        final Location aLoc = addressLoc;
        final String addr = (address != null && aLoc != null && loc != null && aLoc.distanceTo(loc) < 200)
                ? address : null;
        setBusy(true);
        exec.execute(new Runnable() {
            @Override public void run() {
                try {
                    final Result r = process(f, loc, addr);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            preview.setImageBitmap(r.thumb);
                            toast("已儲存 " + r.name);
                            setBusy(false);
                            if (burst.isChecked()) takePhoto();
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            toast("處理失敗：" + t.getMessage());
                            setBusy(false);
                        }
                    });
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        });
    }

    private static class Result {
        final Bitmap thumb; final String name;
        Result(Bitmap t, String n) { thumb = t; name = n; }
    }

    // ───────────────────────── 影像處理 ─────────────────────────

    private Result process(File src, Location loc, String addr) throws Exception {
        ExifInterface in = new ExifInterface(src.getAbsolutePath());
        int orient = in.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        Date when = parseExifDate(in.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
        if (when == null) when = new Date(src.lastModified() > 0 ? src.lastModified() : System.currentTimeMillis());
        if (addr == null && loc != null && Geocoder.isPresent()) addr = geocode(loc);

        // 先讀尺寸，超大照片（如 5000 萬畫素）縮小避免記憶體不足
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getAbsolutePath(), bounds);
        int sample = 1;
        while ((long) (bounds.outWidth / sample) * (bounds.outHeight / sample) > 24_000_000L) sample *= 2;

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inMutable = true;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeFile(src.getAbsolutePath(), o);
        if (bmp == null) throw new IOException("無法讀取照片");
        bmp = rotate(bmp, orient);

        drawWatermark(bmp, when, loc, addr);

        String name = "TS_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(when) + ".jpg";
        File out = new File(src.getParentFile(), "out_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos);
        }
        int tw = Math.min(1080, bmp.getWidth());
        Bitmap thumb = Bitmap.createScaledBitmap(bmp, tw, Math.max(1, bmp.getHeight() * tw / bmp.getWidth()), true);
        if (thumb != bmp) bmp.recycle();

        // 寫入 EXIF：拍攝時間、相機型號、GPS
        ExifInterface ex = new ExifInterface(out.getAbsolutePath());
        String ed = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(when);
        ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, ed);
        ex.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, ed);
        ex.setAttribute(ExifInterface.TAG_DATETIME, ed);
        String make = in.getAttribute(ExifInterface.TAG_MAKE);
        String model = in.getAttribute(ExifInterface.TAG_MODEL);
        if (make != null) ex.setAttribute(ExifInterface.TAG_MAKE, make);
        if (model != null) ex.setAttribute(ExifInterface.TAG_MODEL, model);
        if (loc != null) ex.setGpsInfo(loc);
        ex.saveAttributes();

        saveToGallery(out, name, when);
        //noinspection ResultOfMethodCallIgnored
        out.delete();
        return new Result(thumb, name);
    }

    private static Date parseExifDate(String s) {
        if (s == null) return null;
        try {
            return new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap rotate(Bitmap b, int orient) {
        int deg;
        switch (orient) {
            case ExifInterface.ORIENTATION_ROTATE_90: deg = 90; break;
            case ExifInterface.ORIENTATION_ROTATE_180: deg = 180; break;
            case ExifInterface.ORIENTATION_ROTATE_270: deg = 270; break;
            default: deg = 0;
        }
        if (deg == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(deg);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        if (!r.isMutable()) {
            Bitmap c = r.copy(Bitmap.Config.ARGB_8888, true);
            r.recycle();
            r = c;
        }
        return r;
    }

    private static Paint textPaint(float size, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(size);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        p.setShadowLayer(size * 0.08f, 0, size * 0.03f, 0x99000000);
        return p;
    }

    private static List<String> wrap(String s, Paint p, float max, int maxLines) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < s.length() && out.size() < maxLines) {
            int n = p.breakText(s, start, s.length(), true, max, null);
            if (n <= 0) n = 1;
            out.add(s.substring(start, start + n));
            start += n;
        }
        return out;
    }

    /**
     * 浮水印版面（左下角）：
     *   12:14 ┃ 2026.10.10
     *         ┃ 星期六 · 12:14:05
     *   新北市板橋區○○路○號
     *   25.012345, 121.456789  ±8m
     */
    private static void drawWatermark(Bitmap b, Date when, Location loc, String addr) {
        Canvas c = new Canvas(b);
        int w = b.getWidth(), h = b.getHeight();
        float base = Math.min(w, h);
        float pad = base * 0.04f;
        float sBig = base * 0.09f, sMid = base * 0.036f, sSmall = base * 0.03f;

        Paint pBig = textPaint(sBig, true);
        Paint pMid = textPaint(sMid, true);
        Paint pSmall = textPaint(sSmall, false);
        Paint pDim = textPaint(sSmall * 0.9f, false);
        pDim.setColor(0xDDFFFFFF);

        String hm = new SimpleDateFormat("HH:mm", Locale.TAIWAN).format(when);
        String date = new SimpleDateFormat("yyyy.MM.dd", Locale.TAIWAN).format(when);
        String wk = new SimpleDateFormat("EEEE", Locale.TAIWAN).format(when)
                + " · " + new SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(when);

        Rect tb = new Rect();
        pBig.getTextBounds(hm, 0, hm.length(), tb);
        float row1H = tb.height();
        float maxW = w - pad * 2;

        List<String> lines = new ArrayList<>();
        if (addr != null && !addr.isEmpty()) lines.addAll(wrap(addr, pSmall, maxW, 2));
        String coord = loc != null
                ? String.format(Locale.US, "%.6f, %.6f  ±%dm", loc.getLatitude(), loc.getLongitude(), Math.round(loc.getAccuracy()))
                : "未取得定位";
        float lineH = sSmall * 1.4f;
        float gap = sSmall * 0.9f;
        float total = row1H + gap + (lines.size() + 1) * lineH;

        float top = h - pad - total;

        // 底部漸層，讓白字在亮背景上也看得清楚
        float bgTop = Math.max(0, top - pad * 2.5f);
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, bgTop, 0, h,
                new int[]{0x00000000, 0x80000000, 0xB8000000},
                new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, bgTop, w, h, bg);

        // 第一列：大時間 ┃ 日期 / 星期＋秒
        float x = pad;
        float baseline1 = top + row1H;
        c.drawText(hm, x - tb.left, baseline1, pBig);
        float barX = x + tb.width() + pad * 0.6f;
        float barW = Math.max(2f, base * 0.008f);
        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        bar.setColor(0xFFFFC107);
        c.drawRect(barX, top, barX + barW, baseline1, bar);
        float tx = barX + barW + pad * 0.5f;
        c.drawText(date, tx, top + row1H * 0.46f, pMid);
        c.drawText(wk, tx, baseline1, pSmall);

        // 地址與座標
        float y = baseline1 + gap;
        for (String ln : lines) {
            y += lineH;
            c.drawText(ln, x, y, pSmall);
        }
        y += lineH;
        c.drawText(coord, x, y, pDim);
    }

    // ───────────────────────── 存檔 ─────────────────────────

    private void saveToGallery(File file, String name, Date when) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM);
            cv.put(MediaStore.Images.Media.DATE_TAKEN, when.getTime());
            cv.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri u = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
            if (u == null) throw new IOException("無法寫入相簿");
            try (OutputStream os = cr.openOutputStream(u); InputStream is = new FileInputStream(file)) {
                if (os == null) throw new IOException("無法寫入相簿");
                copy(is, os);
            }
            cv.clear();
            cv.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(u, cv, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File dst = new File(dir, name);
            int i = 1;
            while (dst.exists()) dst = new File(dir, name.replace(".jpg", "_" + (i++) + ".jpg"));
            try (InputStream is = new FileInputStream(file); OutputStream os = new FileOutputStream(dst)) {
                copy(is, os);
            }
            MediaScannerConnection.scanFile(this, new String[]{dst.getAbsolutePath()},
                    new String[]{"image/jpeg"}, null);
        }
    }

    private static void copy(InputStream is, OutputStream os) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
    }
}
