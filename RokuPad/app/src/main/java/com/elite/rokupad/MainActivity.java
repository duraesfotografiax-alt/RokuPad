package com.elite.rokupad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    static final int BG = 0xFF0E0F13;
    static final int CARD = 0xFF1C1E26;
    static final int ACCENT = 0xFF6C3BD1;
    static final int TXT = 0xFFECECF1;
    static final int MUTED = 0xFF8A8D99;

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService aux = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private volatile String ip;
    private TextView status;
    private float dp;
    private long lastErrorToast = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("rokupad", MODE_PRIVATE);
        ip = prefs.getString("ip", null);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(buildUi());

        if (ip == null) discover(); else checkTv();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        net.shutdownNow();
        aux.shutdownNow();
    }

    // Os botões de volume do celular controlam o volume da TV
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { send("VolumeUp"); return true; }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { send("VolumeDown"); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true;
        return super.onKeyUp(keyCode, event);
    }

    // ---------------- Interface ----------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = px(14);
        root.setPadding(pad, px(18), pad, pad);

        // Cabeçalho
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("RokuPad");
        title.setTextColor(TXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        status = new TextView(this);
        status.setTextColor(MUTED);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setText("Iniciando…");
        titles.addView(title);
        titles.addView(status);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        header.addView(smallBtn("Buscar TV", v -> discover()));
        header.addView(smallBtn("IP", v -> askIp(null)));
        root.addView(header);

        root.addView(row(
                btn("⏻  Ligar", v -> send("Power")),
                btn("⌂  Início", v -> send("Home")),
                btn("↩  Voltar", v -> send("Back"))));

        TouchPad pad2 = new TouchPad(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.setMargins(px(4), px(8), px(4), px(8));
        root.addView(pad2, lp);

        root.addView(row(
                btn("⏪", v -> send("Rev")),
                btn("⏯", v -> send("Play")),
                btn("⏩", v -> send("Fwd")),
                btn("✱", v -> send("Info"))));

        root.addView(row(
                btn("🔉", v -> send("VolumeDown")),
                btn("🔇", v -> send("VolumeMute")),
                btn("🔊", v -> send("VolumeUp"))));

        root.addView(row(
                btn("⌨  Teclado", v -> showKeyboard()),
                btn("▦  Apps", v -> showApps()),
                btn("🔍", v -> send("Search"))));

        return root;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(54), 1f);
            lp.setMargins(px(4), px(4), px(4), px(4));
            r.addView(v, lp);
        }
        return r;
    }

    private TextView btn(String label, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(TXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(px(14));
        b.setBackground(bg);
        b.setClickable(true);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            l.onClick(v);
        });
        return b;
    }

    private TextView smallBtn(String label, View.OnClickListener l) {
        TextView b = btn(label, l);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setPadding(px(12), px(8), px(12), px(8));
        ((GradientDrawable) b.getBackground()).setColor(ACCENT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(px(6), 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private int px(float v) { return Math.round(v * dp); }

    private void setStatus(String s) { ui.post(() -> status.setText(s)); }

    private void toast(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show()); }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    // ---------------- Touchpad ----------------

    class TouchPad extends View {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float step = 42 * dp;
        float startX, startY, lastX, lastY, curX = -1, curY = -1;
        boolean moved;
        long downTime;

        TouchPad(Context c) {
            super(c);
            fill.setColor(CARD);
            text.setColor(MUTED);
            text.setTextSize(15 * getResources().getDisplayMetrics().scaledDensity);
            text.setTextAlign(Paint.Align.CENTER);
            dot.setColor(0x666C3BD1);
            setHapticFeedbackEnabled(true);
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = 24 * dp;
            c.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, fill);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            c.drawText("Deslize para mover", cx, cy - 10 * dp, text);
            c.drawText("Toque para OK", cx, cy + 16 * dp, text);
            if (curX >= 0) c.drawCircle(curX, curY, 30 * dp, dot);
        }

        private void key(String k) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            send(k);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lastX = curX = e.getX();
                    startY = lastY = curY = e.getY();
                    moved = false;
                    downTime = e.getEventTime();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - lastX;
                    while (Math.abs(dx) >= step) {
                        key(dx > 0 ? "Right" : "Left");
                        lastX += dx > 0 ? step : -step;
                        dx = e.getX() - lastX;
                        moved = true;
                    }
                    float dy = e.getY() - lastY;
                    while (Math.abs(dy) >= step) {
                        key(dy > 0 ? "Down" : "Up");
                        lastY += dy > 0 ? step : -step;
                        dy = e.getY() - lastY;
                        moved = true;
                    }
                    curX = e.getX();
                    curY = e.getY();
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    float dist = (float) Math.hypot(e.getX() - startX, e.getY() - startY);
                    if (!moved && e.getEventTime() - downTime < 450 && dist < 24 * dp) key("Select");
                    curX = -1;
                    invalidate();
                    performClick();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    curX = -1;
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(e);
        }

        @Override
        public boolean performClick() { return super.performClick(); }
    }

    // ---------------- Comunicação com o Roku (ECP, porta 8060) ----------------

    private void send(String key) {
        if (ip == null) { toast("Nenhuma TV conectada. Toque em \"Buscar TV\"."); return; }
        net.execute(() -> post("/keypress/" + key));
    }

    private void post(String path) {
        String target = ip;
        if (target == null) return;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + target + ":8060" + path).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            OutputStream os = c.getOutputStream();
            os.close();
            int code = c.getResponseCode();
            if (code == 403) {
                errorToast("A TV bloqueou o controle. No Roku: Configurações > Sistema > "
                        + "Configurações avançadas do sistema > Controle por apps móveis > Acesso à rede: Padrão.");
            }
        } catch (Exception ex) {
            setStatus("Sem resposta da TV (" + target + ")");
            errorToast("Não consegui falar com a TV. Celular e TV estão no mesmo Wi-Fi?");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void errorToast(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastErrorToast > 5000) {
            lastErrorToast = now;
            toast(msg);
        }
    }

    private static String httpGet(String host, String path, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":8060" + path).openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">([^<]*)</" + name + ">").matcher(xml);
        return m.find() ? unescape(m.group(1).trim()) : null;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">");
    }

    private static String tvName(String host) {
        try {
            String xml = httpGet(host, "/query/device-info", 2000);
            String n = tag(xml, "user-device-name");
            if (n == null || n.isEmpty()) n = tag(xml, "friendly-device-name");
            if (n == null || n.isEmpty()) n = tag(xml, "model-name");
            return n == null || n.isEmpty() ? "Roku" : n;
        } catch (Exception e) {
            return null;
        }
    }

    private void checkTv() {
        final String target = ip;
        setStatus("Conectando em " + target + "…");
        aux.execute(() -> {
            String name = tvName(target);
            if (name != null) setStatus("Conectado: " + name);
            else setStatus("TV não respondeu (" + target + ")");
        });
    }

    private void setIp(String newIp) {
        ip = newIp;
        prefs.edit().putString("ip", newIp).apply();
        checkTv();
    }

    // Procura TVs Roku na rede (SSDP)
    private void discover() {
        setStatus("Procurando TVs na rede…");
        aux.execute(() -> {
            Set<String> found = new LinkedHashSet<>();
            WifiManager.MulticastLock lock = null;
            DatagramSocket s = null;
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    lock = wm.createMulticastLock("rokupad");
                    lock.setReferenceCounted(false);
                    lock.acquire();
                }
                s = new DatagramSocket();
                s.setSoTimeout(800);
                byte[] msg = ("M-SEARCH * HTTP/1.1\r\n"
                        + "Host: 239.255.255.250:1900\r\n"
                        + "Man: \"ssdp:discover\"\r\n"
                        + "ST: roku:ecp\r\n"
                        + "MX: 2\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                InetAddress group = InetAddress.getByName("239.255.255.250");
                for (int i = 0; i < 2; i++) s.send(new DatagramPacket(msg, msg.length, group, 1900));

                Pattern loc = Pattern.compile("(?i)location:\\s*http://([0-9.]+):8060");
                long end = System.currentTimeMillis() + 3500;
                byte[] buf = new byte[2048];
                while (System.currentTimeMillis() < end) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                    } catch (SocketTimeoutException t) {
                        continue;
                    }
                    Matcher m = loc.matcher(new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII));
                    if (m.find()) found.add(m.group(1));
                }
            } catch (Exception e) {
                // segue para o resultado
            } finally {
                if (s != null) s.close();
                if (lock != null && lock.isHeld()) lock.release();
            }

            List<String> ips = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (String f : found) {
                String n = tvName(f);
                ips.add(f);
                labels.add((n == null ? "Roku" : n) + "  •  " + f);
            }

            ui.post(() -> {
                if (ips.isEmpty()) {
                    status.setText(ip == null ? "Nenhuma TV encontrada" : "Busca sem resultado (usando " + ip + ")");
                    askIp("Não encontrei a TV automaticamente. Digite o IP dela.");
                } else if (ips.size() == 1) {
                    setIp(ips.get(0));
                } else {
                    dialog().setTitle("Escolha a TV")
                            .setItems(labels.toArray(new String[0]), (d, which) -> setIp(ips.get(which)))
                            .show();
                }
            });
        });
    }

    private void askIp(String message) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setHint("Ex.: 192.168.0.10");
        if (ip != null) et.setText(ip);
        et.setSingleLine(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(px(20), px(8), px(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String msg = (message == null ? "" : message + "\n\n")
                + "No Roku: Configurações > Rede > Sobre > Endereço IP.\nCelular e TV precisam estar no mesmo Wi-Fi.";
        dialog().setTitle("IP da TV")
                .setMessage(msg)
                .setView(box)
                .setPositiveButton("Conectar", (d, w) -> {
                    String v = et.getText().toString().trim();
                    if (!v.isEmpty()) setIp(v);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // Lista os apps instalados na TV e abre o escolhido
    private void showApps() {
        if (ip == null) { toast("Nenhuma TV conectada."); return; }
        final String target = ip;
        aux.execute(() -> {
            try {
                String xml = httpGet(target, "/query/apps", 3000);
                Matcher m = Pattern.compile("<app id=\"([^\"]+)\"[^>]*>([^<]*)</app>").matcher(xml);
                List<String> ids = new ArrayList<>();
                List<String> names = new ArrayList<>();
                while (m.find()) {
                    ids.add(m.group(1));
                    names.add(unescape(m.group(2).trim()));
                }
                ui.post(() -> {
                    if (ids.isEmpty()) { toast("Nenhum app encontrado na TV."); return; }
                    dialog().setTitle("Abrir app na TV")
                            .setItems(names.toArray(new String[0]),
                                    (d, which) -> net.execute(() -> post("/launch/" + ids.get(which))))
                            .show();
                });
            } catch (Exception e) {
                toast("Não consegui buscar os apps da TV.");
            }
        });
    }

    // Teclado: o que você digita aparece direto na TV
    private void showKeyboard() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        et.setHint("Digite aqui…");
        LinearLayout box = new LinearLayout(this);
        box.setPadding(px(20), px(8), px(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void afterTextChanged(Editable s) { }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                for (int i = 0; i < before; i++) send("Backspace");
                CharSequence added = s.subSequence(start, start + count);
                for (int i = 0; i < added.length(); i++) {
                    try {
                        send("Lit_" + URLEncoder.encode(String.valueOf(added.charAt(i)), "UTF-8"));
                    } catch (Exception ignored) { }
                }
            }
        });

        AlertDialog d = dialog().setTitle("Digitar na TV")
                .setMessage("O que você digitar aparece no campo de busca da TV.")
                .setView(box)
                .setPositiveButton("Enter", (di, w) -> send("Enter"))
                .setNegativeButton("Fechar", null)
                .create();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        d.show();
        et.requestFocus();
    }
}
