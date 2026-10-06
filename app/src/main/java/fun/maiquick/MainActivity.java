package fun.maiquick;

import android.app.*;
import android.content.*;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 40, 35), CARD = Color.rgb(27, 56, 49), ACCENT = Color.rgb(98, 237, 209), WHITE = Color.rgb(241, 249, 245), MUTED = Color.rgb(168, 193, 183);
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Vault vault;
    private LocalRuntime runtime;
    private JSONObject account = new JSONObject();
    private boolean ready;
    private TextView status, identity, history;
    private Button update, settings, shortcut;
    private ProgressBar progress;
    private String sharedQr = "";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        vault = new Vault(this); runtime = new LocalRuntime(this);
        String vaultError = "";
        try { account = vault.read(); } catch (Exception e) { vaultError = "原绑定无法解密，请重新绑定。"; }
        render();
        readIntent(getIntent());
        final String error = vaultError;
        worker.execute(() -> {
            try {
                runtime.prepare(this::showProgress);
                runOnUiThread(() -> {
                    ready = true; refresh();
                    if (!error.isEmpty()) status.setText(error);
                    if (!sharedQr.isEmpty()) bindDialog(sharedQr);
                    else if ("fun.maiquick.UPDATE".equals(getIntent().getAction()) && bound()) startUpdate();
                });
            } catch (Exception e) { runOnUiThread(() -> { progress.setVisibility(View.GONE); status.setText(e.getMessage()); }); }
        });
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); readIntent(intent);
        if (ready && !RUNNING.get()) {
            if (!sharedQr.isEmpty()) bindDialog(sharedQr);
            else if ("fun.maiquick.UPDATE".equals(intent.getAction()) && bound()) startUpdate();
        }
    }
    private void readIntent(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && text.contains("SGWCMAID")) sharedQr = text;
        }
    }
    private int dp(int value) { return (int) (getResources().getDisplayMetrics().density * value); }
    private GradientDrawable background(int color, int radius) { GradientDrawable result = new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(radius)); return result; }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(dp(3), 1); return view;
    }
    private void space(LinearLayout layout, int height) { View view = new View(this); layout.addView(view, new LinearLayout.LayoutParams(1, dp(height))); }
    private Button button(String label, boolean primary) {
        Button view = new Button(this); view.setText(label); view.setTextSize(17); view.setAllCaps(false); view.setTextColor(primary ? BG : WHITE);
        view.setBackground(background(primary ? ACCENT : CARD, 18)); view.setMinHeight(dp(58)); return view;
    }
    private LinearLayout card() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); view.setPadding(dp(22), dp(22), dp(22), dp(22)); view.setBackground(background(CARD, 24)); return view; }
    private void render() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(24), dp(34), dp(24), dp(30)); scroll.addView(page);
        TextView chip = text("MAI QUICK  /  舞萌 DX", 13, ACCENT); chip.setLetterSpacing(.10f); page.addView(chip);
        space(page, 22);
        TextView title = text("每次推分，\n轻松同步。", 36, WHITE); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); page.addView(title);
        space(page, 12); page.addView(text("首次绑定一次。之后点一下，更新到水鱼。", 14, MUTED));
        space(page, 28);
        LinearLayout state = card();
        identity = text("尚未绑定", 13, ACCENT); state.addView(identity); space(state, 12);
        status = text("正在准备手机本地运行环境…", 21, WHITE); status.setTypeface(Typeface.DEFAULT, Typeface.BOLD); state.addView(status);
        space(state, 18); progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true); state.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        space(state, 18);
        update = button("绑定并开始使用", true); state.addView(update, new LinearLayout.LayoutParams(-1, dp(60))); update.setOnClickListener(v -> { if (bound()) startUpdate(); else bindDialog(""); });
        page.addView(state); space(page, 18);
        history = text("还没有更新记录", 14, MUTED); page.addView(history); space(page, 22);
        settings = button("绑定 / 修改 Token", false); page.addView(settings, new LinearLayout.LayoutParams(-1, dp(54))); settings.setOnClickListener(v -> settingsDialog());
        space(page, 12);
        shortcut = button("添加桌面「更新成绩」按钮", false); page.addView(shortcut, new LinearLayout.LayoutParams(-1, dp(54))); shortcut.setOnClickListener(v -> pinShortcut());
        space(page, 24);
        page.addView(text("手机本地运行  ·  无需电脑或后台", 13, ACCENT)); space(page, 10);
        page.addView(text("快捷模式更新达成率与 DX 分。已有 FC/FS 标记会保留，新获得的标记需用完整导入方式更新。\n\nToken 和 UID 加密保存在本机。Token 仅发给水鱼。", 12, MUTED));
        setContentView(scroll); refreshControls();
    }
    private boolean bound() { return !account.optString("uid").isEmpty() && !account.optString("token").isEmpty(); }
    private void refreshControls() {
        boolean enabled = ready && !RUNNING.get();
        update.setEnabled(enabled); settings.setEnabled(enabled); shortcut.setEnabled(enabled && bound());
        update.setAlpha(enabled ? 1 : .5f); settings.setAlpha(enabled ? 1 : .5f);
    }
    private void refresh() {
        identity.setText(bound() ? "水鱼账号  /  " + account.optString("username") : "尚未绑定");
        update.setText(bound() ? "更新成绩  ↻" : "绑定并开始使用");
        status.setText(bound() ? "已就绪，随时更新。" : "只需绑定一次。");
        progress.setVisibility(View.GONE);
        JSONObject receipt = account.optJSONObject("last");
        if (receipt != null) {
            String time = receipt.optString("local_time", "");
            history.setText("上次更新  " + time + "\n接受 " + receipt.optInt("count") + " 条 · 新增 " + receipt.optInt("creates") + " 条");
        }
        refreshControls();
    }
    private void showProgress(String message) { runOnUiThread(() -> { status.setText(message); progress.setVisibility(View.VISIBLE); }); }
    private void fail(String message) { RUNNING.set(false); progress.setVisibility(View.GONE); status.setText(message); refreshControls(); }
    private void startUpdate() {
        if (!ready || !bound() || !RUNNING.compareAndSet(false, true)) return;
        refreshControls(); showProgress("正在读取最新成绩…");
        worker.execute(() -> {
            try {
                JSONObject request = new JSONObject(account.toString()); request.put("action", "update");
                JSONObject receipt = runtime.run(request, this::showProgress);
                receipt.put("local_time", new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(new java.util.Date()));
                account.put("last", receipt); vault.write(account);
                runOnUiThread(() -> {
                    RUNNING.set(false); refresh();
                    int skipped = receipt.optInt("skipped");
                    status.setText("更新完成，" + receipt.optInt("count") + " 条成绩。" + (skipped > 0 ? "\n另有 " + skipped + " 条宴谱或未匹配谱面未导入。" : ""));
                });
            } catch (Exception e) { runOnUiThread(() -> fail(e.getMessage())); }
        });
    }
    private EditText field(String hint, boolean secret) {
        EditText result = new EditText(this); result.setHint(hint); result.setTextSize(14); result.setTextColor(Color.rgb(25, 45, 38)); result.setSingleLine(true);
        result.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        result.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); return result;
    }
    private String clipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (!clipboard.hasPrimaryClip() || clipboard.getPrimaryClip().getItemCount() == 0) return "";
        return clipboard.getPrimaryClip().getItemAt(0).coerceToText(this).toString().trim();
    }
    private void bindDialog(String suggestedQr) {
        sharedQr = "";
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(22), dp(8), dp(22), 0);
        TextView description = text("粘贴水鱼 Token 和以 SGWCMAID 开头的玩家二维码内容。完成后无需重复复制二维码。", 14, Color.DKGRAY); form.addView(description);
        EditText token = field("水鱼成绩导入 Token", true); token.setText(account.optString("token")); form.addView(token);
        EditText qr = field("玩家二维码字符串（SGWCMAID…）", false); qr.setText(suggestedQr); form.addView(qr);
        Button paste = new Button(this); paste.setText("粘贴二维码字符串"); paste.setOnClickListener(v -> qr.setText(clipboard())); form.addView(paste);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("首次绑定 / 更换舞萌账号").setView(form).setNegativeButton("取消", null).setPositiveButton("保存绑定", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String tokenValue = token.getText().toString().trim(), qrValue = qr.getText().toString().trim();
            if (!tokenValue.matches("[0-9a-fA-F]{128}")) { token.setError("请粘贴完整的 128 位 Token"); return; }
            if (!qrValue.contains("SGWCMAID")) { qr.setError("需要玩家登录二维码字符串"); return; }
            if (!RUNNING.compareAndSet(false, true)) return;
            dialog.dismiss(); refreshControls(); showProgress("正在保存长期绑定…");
            worker.execute(() -> {
                try {
                    JSONObject request = new JSONObject().put("action", "bind").put("token", tokenValue).put("qr", qrValue);
                    JSONObject result = runtime.run(request, this::showProgress);
                    JSONObject replacement = new JSONObject().put("token", tokenValue).put("uid", result.getString("uid")).put("username", result.getString("username"));
                    vault.write(replacement); account = replacement;
                    runOnUiThread(() -> { RUNNING.set(false); refresh(); status.setText("绑定成功。点一下即可更新。"); });
                } catch (Exception e) { runOnUiThread(() -> fail(e.getMessage())); }
            });
        })); dialog.show();
    }
    private void settingsDialog() {
        new AlertDialog.Builder(this).setTitle("账号设置").setItems(new String[]{"首次绑定 / 更换舞萌账号", "只修改水鱼 Token", "清除本机绑定", "检查本机运行环境"}, (d, choice) -> {
            if (choice == 0) bindDialog("");
            else if (choice == 1) tokenDialog();
            else if (choice == 2) new AlertDialog.Builder(this).setTitle("清除本机绑定？").setMessage("水鱼中的成绩会保留。下次使用需要重新复制一次二维码。").setNegativeButton("取消", null).setPositiveButton("清除", (x, y) -> { vault.clear(); account = new JSONObject(); refresh(); }).show();
            else {
                if (!RUNNING.compareAndSet(false, true)) return;
                refreshControls(); showProgress("正在检查本机环境和网络…");
                worker.execute(() -> {
                    try {
                        runtime.run(new JSONObject().put("action", "selftest"), this::showProgress);
                        runOnUiThread(() -> { RUNNING.set(false); refresh(); status.setText("本机环境正常，水鱼连接正常。"); });
                    } catch (Exception e) { runOnUiThread(() -> fail(e.getMessage())); }
                });
            }
        }).show();
    }
    private void tokenDialog() {
        EditText token = field("新的 128 位 Token", true);
        token.setPadding(dp(24), dp(12), dp(24), dp(12));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("修改水鱼 Token").setView(token).setNegativeButton("取消", null).setPositiveButton("验证并保存", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String value = token.getText().toString().trim();
            if (!value.matches("[0-9a-fA-F]{128}")) { token.setError("Token 必须完整复制"); return; }
            if (!RUNNING.compareAndSet(false, true)) return;
            dialog.dismiss(); refreshControls(); showProgress("正在验证新 Token…");
            worker.execute(() -> {
                try {
                    JSONObject result = runtime.run(new JSONObject().put("action", "validate").put("token", value), this::showProgress);
                    String username = result.getString("username");
                    if (bound() && !username.equals(account.optString("username"))) throw new java.io.IOException("这是另一个水鱼账号。请使用更换账号重新绑定。");
                    account.put("token", value).put("username", username); vault.write(account);
                    runOnUiThread(() -> { RUNNING.set(false); refresh(); status.setText("Token 已更新，UID 绑定保留。"); });
                } catch (Exception e) { runOnUiThread(() -> fail(e.getMessage())); }
            });
        })); dialog.show();
    }
    private void pinShortcut() {
        ShortcutManager manager = getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) { Toast.makeText(this, "当前桌面不支持固定快捷方式，请直接打开应用。", Toast.LENGTH_LONG).show(); return; }
        ShortcutInfo info = new ShortcutInfo.Builder(this, "maiquick-update").setShortLabel("更新舞萌成绩").setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))
            .setIntent(new Intent(this, MainActivity.class).setAction("fun.maiquick.UPDATE")).build();
        manager.requestPinShortcut(info, null);
    }
    @Override public void onBackPressed() {
        if (RUNNING.get()) { Toast.makeText(this, "正在同步，请等更新结果。", Toast.LENGTH_SHORT).show(); return; }
        super.onBackPressed();
    }
}
