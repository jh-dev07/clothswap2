package com.coverswap.cloth;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<View> controls = new ArrayList<>();
    private final String[] categories = {"upper_body", "lower_body", "dress"};
    private final List<Integer> personIds = new ArrayList<>();
    private EditText address, username, password, steps, seed;
    private CheckBox localHttp, highResolution;
    private LinearLayout loginPanel, studio;
    private Spinner person, category;
    private ImageView preview;
    private TextView status;
    private String token, server, target, garment;
    private byte[] result;

    private final ActivityResultLauncher<String[]> targetPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> loadPhoto(uri, true));
    private final ActivityResultLauncher<String[]> garmentPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> loadPhoto(uri, false));
    private final ActivityResultLauncher<String> exportPicker = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("image/png"), this::saveResult);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ConstraintLayout root = new ConstraintLayout(this);
        ScrollView scroll = new ScrollView(this);
        ConstraintLayout.LayoutParams bounds = new ConstraintLayout.LayoutParams(0, 0);
        bounds.topToTop = bounds.bottomToBottom = bounds.startToStart = bounds.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
        root.addView(scroll, bounds);
        LinearLayout content = column();
        content.setPadding(dp(20), dp(16), dp(20), dp(20));
        scroll.addView(content);
        setContentView(root);

        TextView title = new TextView(this);
        title.setText("ClothSwap");
        title.setTextSize(30);
        content.addView(title);
        status = new TextView(this);
        status.setText("Sign in to your ClothSwap server.");
        content.addView(status);
        loginPanel = column();
        content.addView(loginPanel);
        address = field(loginPanel, "Server URL", getPreferences(0).getString("server", ""), InputType.TYPE_TEXT_VARIATION_URI);
        username = field(loginPanel, "Username", "", InputType.TYPE_CLASS_TEXT);
        password = field(loginPanel, "Password", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false);
        localHttp = new CheckBox(this);
        localHttp.setText("Allow unencrypted HTTP on my test network");
        loginPanel.addView(localHttp);
        controls.add(localHttp);
        button(loginPanel, "Sign in", this::signIn);

        studio = column();
        content.addView(studio);
        button(studio, "Sign out", this::signOut);
        button(studio, "Choose person photo", () -> targetPicker.launch(new String[]{"image/*"}));
        button(studio, "Choose garment photo", () -> garmentPicker.launch(new String[]{"image/*"}));
        button(studio, "Detect people", () -> submit(false));
        person = spinner(studio, new String[0]);
        category = spinner(studio, new String[]{"Upper body", "Lower body", "Dress"});
        steps = field(studio, "Steps (1–60)", "30", InputType.TYPE_CLASS_NUMBER);
        seed = field(studio, "Seed", "42", InputType.TYPE_CLASS_NUMBER);
        highResolution = new CheckBox(this);
        highResolution.setText("High resolution");
        studio.addView(highResolution);
        controls.add(highResolution);
        button(studio, "Swap clothes", () -> submit(true));
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        studio.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
        button(studio, "Save PNG", () -> {
            if (result == null) status.setText("Generate a result first.");
            else exportPicker.launch("clothswap.png");
        });
        showSession();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private EditText field(LinearLayout parent, String hint, String value, int type) {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setHint(hint);
        field.setInputType(type);
        field.setText(value);
        parent.addView(field);
        controls.add(field);
        return field;
    }

    private void button(LinearLayout parent, String label, Runnable action) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setOnClickListener(v -> action.run());
        parent.addView(button);
        controls.add(button);
    }

    private Spinner spinner(LinearLayout parent, String[] labels) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(adapter(labels));
        parent.addView(spinner);
        controls.add(spinner);
        return spinner;
    }

    private ArrayAdapter<String> adapter(String[] labels) {
        return new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
    }

    private void showSession() {
        loginPanel.setVisibility(token == null ? View.VISIBLE : View.GONE);
        studio.setVisibility(token == null ? View.GONE : View.VISIBLE);
    }

    private void clearSession() {
        token = server = target = garment = null;
        result = null;
        personIds.clear();
        person.setAdapter(adapter(new String[0]));
        Glide.with(this).clear(preview);
        preview.setImageDrawable(null);
        showSession();
    }

    private void signIn() {
        try {
            String base = address.getText().toString().trim().replaceAll("/+$", "");
            URI uri = new URI(base);
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean http = "http".equalsIgnoreCase(uri.getScheme());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (!https && !(http && localHttp.isChecked()))) {
                throw new IOException("Enter an HTTPS server URL, or enable HTTP for your test network.");
            }
            JSONObject payload = new JSONObject()
                    .put("username", username.getText().toString().trim())
                    .put("password", password.getText().toString());
            password.setText("");
            runTask("Signing in…", () -> {
                String session = post(base, "/auth/login", payload, null).getString("access_token");
                finishTask(() -> {
                    token = session;
                    server = base;
                    getPreferences(0).edit().putString("server", base).apply();
                    showSession();
                    status.setText("Signed in. Choose two photos.");
                });
            });
        } catch (Exception error) {
            status.setText(error.getMessage());
        }
    }

    private void signOut() {
        String currentServer = server;
        String currentToken = token;
        runTask("Signing out…", () -> {
            String message;
            try {
                post(currentServer, "/auth/logout", new JSONObject(), currentToken);
                message = "Signed out.";
            } catch (ApiError error) {
                message = error.code == 401 ? "Signed out." : "Signed out locally. Server revocation failed.";
            } catch (IOException error) {
                message = "Signed out locally. Server revocation could not be confirmed.";
            }
            String notice = message;
            finishTask(() -> {
                clearSession();
                status.setText(notice);
            });
        });
    }

    private void loadPhoto(Uri uri, boolean targetPhoto) {
        if (uri == null) return;
        runTask("Opening photo…", () -> {
            byte[] bytes;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                bytes = read(input, 12_000_000);
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0 ||
                    (long) options.outWidth * options.outHeight > 16_000_000) {
                throw new IOException("Choose a supported image up to 16 megapixels.");
            }
            String encoded = Base64.encodeToString(bytes, Base64.NO_WRAP);
            finishTask(() -> {
                result = null;
                if (targetPhoto) {
                    target = encoded;
                    personIds.clear();
                    person.setAdapter(adapter(new String[0]));
                } else {
                    garment = encoded;
                }
                Glide.with(this).load(bytes).fitCenter().into(preview);
                status.setText(targetPhoto ? "Person photo loaded. Tap Detect people." : "Garment photo loaded.");
            });
        });
    }

    private void submit(boolean generate) {
        if (target == null || (generate && garment == null)) {
            status.setText("Choose the required photos first.");
            return;
        }
        try {
            JSONObject payload = new JSONObject().put("photo", target);
            if (generate) {
                int selected = person.getSelectedItemPosition();
                if (selected < 0 || selected >= personIds.size()) throw new IOException("Detect and select a person first.");
                int stepCount = Integer.parseInt(steps.getText().toString());
                int seedValue = Integer.parseInt(seed.getText().toString());
                if (stepCount < 1 || stepCount > 60 || seedValue < 0) throw new IOException("Use 1–60 steps and a nonnegative seed.");
                payload.put("garment", garment).put("person", personIds.get(selected))
                        .put("category", categories[category.getSelectedItemPosition()])
                        .put("steps", stepCount).put("seed", seedValue)
                        .put("high_resolution", highResolution.isChecked());
            }
            String currentServer = server;
            String currentToken = token;
            runTask(generate ? "Generating on the server…" : "Detecting people…", () -> {
                JSONObject response = post(currentServer, generate ? "/swap" : "/detect", payload, currentToken);
                if (generate) {
                    byte[] png = Base64.decode(response.getString("image"), Base64.DEFAULT);
                    finishTask(() -> {
                        result = png;
                        Glide.with(this).load(png).fitCenter().into(preview);
                        status.setText("Result ready. Tap Save PNG.");
                    });
                } else {
                    JSONArray people = response.getJSONArray("people");
                    String[] labels = new String[people.length()];
                    List<Integer> ids = new ArrayList<>();
                    for (int i = 0; i < labels.length; i++) {
                        JSONObject item = people.getJSONObject(i);
                        labels[i] = item.getString("label") + "  " + item.getJSONArray("box");
                        ids.add(item.getInt("index"));
                    }
                    finishTask(() -> {
                        personIds.clear();
                        personIds.addAll(ids);
                        person.setAdapter(adapter(labels));
                        status.setText(ids.isEmpty() ? "No people detected." : "Select a person and garment category.");
                    });
                }
            });
        } catch (Exception error) {
            status.setText(error instanceof NumberFormatException ? "Enter valid integers for steps and seed." : error.getMessage());
        }
    }

    private void saveResult(Uri uri) {
        byte[] png = result;
        if (uri == null || png == null) return;
        runTask("Saving…", () -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IOException("Could not open the output file.");
                output.write(png);
            }
            finishTask(() -> status.setText("PNG saved."));
        });
    }

    private interface Task { void run() throws Exception; }

    private void runTask(String message, Task task) {
        setEnabled(false);
        status.setText(message);
        worker.execute(() -> {
            try { task.run(); }
            catch (Exception error) {
                finishTask(() -> {
                    if (error instanceof ApiError && ((ApiError) error).code == 401 && token != null) clearSession();
                    status.setText(error.getMessage() == null ? "Request failed." : error.getMessage());
                });
            }
        });
    }

    private void finishTask(Runnable callback) {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            setEnabled(true);
            callback.run();
        });
    }

    private void setEnabled(boolean enabled) {
        for (View control : controls) control.setEnabled(enabled);
    }

    private static class ApiError extends IOException {
        final int code;
        ApiError(int code, String message) { super(message); this.code = code; }
    }

    private static JSONObject post(String server, String path, JSONObject payload, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(server + path).toURL().openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(path.equals("/swap") ? 900_000 : 60_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String detail = "Server returned HTTP " + code;
                try (InputStream input = connection.getErrorStream()) {
                    if (input != null) {
                        Object value = new JSONObject(new String(read(input, 8192), StandardCharsets.UTF_8)).opt("detail");
                        if (value instanceof String) detail = (String) value;
                    }
                } catch (Exception ignored) { }
                throw new ApiError(code, detail);
            }
            try (InputStream input = connection.getInputStream()) {
                return new JSONObject(new String(read(input, 90_000_000), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] read(InputStream input, int limit) throws IOException {
        if (input == null) throw new IOException("No data available.");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("Image or response exceeds the size limit.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
