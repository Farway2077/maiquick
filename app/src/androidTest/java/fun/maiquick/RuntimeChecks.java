package fun.maiquick;

import android.app.Instrumentation;
import android.app.Activity;
import android.os.Bundle;
import org.json.JSONObject;

public class RuntimeChecks extends Instrumentation {
    private String mode;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); mode = arguments == null ? "" : arguments.getString("mode", ""); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if ("fixture".equals(mode) || "clear".equals(mode)) {
                Vault fixtureVault = new Vault(getTargetContext());
                if ("clear".equals(mode)) fixtureVault.clear();
                else fixtureVault.write(new JSONObject().put("token", "0".repeat(128)).put("uid", "test-ui-only-uid").put("username", "界面验证（模拟数据）"));
                result.putString("stream", "UI fixture " + mode + " completed; no score requests made.\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            LocalRuntime runtime = new LocalRuntime(getTargetContext());
            runtime.prepare(message -> { });
            JSONObject check = runtime.run(new JSONObject().put("action", "selftest"), message -> { });
            if (!check.getBoolean("ffi") || check.getInt("songs") < 1000 || !check.getString("python").startsWith("3.12.")) throw new AssertionError("Runtime check failed");
            result.putString("runtime", check.toString());
            Vault vault = new Vault(getTargetContext());
            JSONObject original = vault.read();
            try {
                vault.write(new JSONObject().put("token", "private-example").put("uid", "private-uid"));
                if (!new Vault(getTargetContext()).read().getString("token").equals("private-example")) throw new AssertionError("Vault persistence failed");
                if (getTargetContext().getSharedPreferences("account", 0).getString("sealed", "").contains("private-example")) throw new AssertionError("Plaintext credential found");
            } finally { vault.write(original); }
            result.putString("stream", "2 Android checks passed: local Python/FFI/public API and encrypted persistence.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "Android runtime check failed: " + error + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
