package fun.maiquick;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Executes the embedded Python/PRoot runtime locally; it opens no server. */
public final class LocalRuntime {
    public interface Progress { void update(String message); }
    private final Context context;
    private final File root;
    private String abi;
    public LocalRuntime(Context context) {
        this.context = context.getApplicationContext();
        root = new File(context.getFilesDir(), "runtime-v1");
    }
    public synchronized void prepare(Progress progress) throws Exception {
        for (String candidate : Build.SUPPORTED_ABIS) {
            if (candidate.equals("arm64-v8a") || candidate.equals("x86_64")) { abi = candidate; break; }
        }
        if (abi == null) throw new IOException("需要 64 位安卓系统（ARM64 或 x86_64）。");
        File marker = new File(root, ".ready-" + abi);
        if (!marker.isFile()) {
            progress.update("首次准备手机本地环境…\n只需准备一次，请稍候。");
            root.mkdirs();
            String metadataText = null;
            int count = 0;
            try (ZipInputStream zip = new ZipInputStream(context.getAssets().open("runtime-" + abi + ".zip"))) {
                ZipEntry entry;
                byte[] buffer = new byte[65536];
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.getName().equals("runtime-metadata.json")) {
                        ByteArrayOutputStream metadataBytes = new ByteArrayOutputStream();
                        int size;
                        while ((size = zip.read(buffer)) >= 0) metadataBytes.write(buffer, 0, size);
                        metadataText = new String(metadataBytes.toByteArray(), StandardCharsets.UTF_8);
                    } else {
                        File target = new File(root, entry.getName());
                        if (!target.toPath().normalize().startsWith(root.toPath())) throw new IOException("运行包路径无效。");
                        target.getParentFile().mkdirs();
                        try (OutputStream output = new FileOutputStream(target)) {
                            int length;
                            while ((length = zip.read(buffer)) >= 0) output.write(buffer, 0, length);
                        }
                        if (++count % 700 == 0) progress.update("正在准备本地环境… " + count + " 个文件");
                    }
                    zip.closeEntry();
                }
            }
            if (metadataText == null) throw new IOException("本地运行包不完整。");
            JSONObject metadata = new JSONObject(metadataText);
            List<String> names = new ArrayList<>();
            metadata.keys().forEachRemaining(names::add);
            // Create ordinary directories and modes before adding symlinks.
            for (String name : names) {
                JSONObject data = metadata.getJSONObject(name);
                File target = new File(root, name);
                if (data.getString("type").equals("dir")) target.mkdirs();
                if (!data.getString("type").equals("link")) Os.chmod(target.getPath(), data.getInt("mode"));
            }
            for (String name : names) {
                JSONObject data = metadata.getJSONObject(name);
                if (data.getString("type").equals("link")) {
                    File target = new File(root, name);
                    target.getParentFile().mkdirs();
                    if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete();
                    Os.symlink(data.getString("target"), target.getPath());
                }
            }
            marker.createNewFile();
        }
        // The small engine is refreshed independently of the large rootfs.
        File engine = new File(root, "app/engine.py");
        engine.getParentFile().mkdirs();
        try (InputStream input = context.getAssets().open("engine.py"); OutputStream output = new FileOutputStream(engine)) {
            byte[] buffer = new byte[16384]; int length;
            while ((length = input.read(buffer)) >= 0) output.write(buffer, 0, length);
        }
        File temp = new File(context.getCacheDir(), "proot-tmp");
        temp.mkdirs();
    }
    public synchronized JSONObject run(JSONObject request, Progress progress) throws Exception {
        File etc = new File(root, "etc"); etc.mkdirs();
        StringBuilder nameservers = new StringBuilder();
        android.net.ConnectivityManager connectivity = context.getSystemService(android.net.ConnectivityManager.class);
        android.net.LinkProperties properties = connectivity.getLinkProperties(connectivity.getActiveNetwork());
        if (properties != null) for (java.net.InetAddress address : properties.getDnsServers()) nameservers.append("nameserver ").append(address.getHostAddress()).append("\n");
        if (nameservers.length() == 0) nameservers.append("nameserver 223.5.5.5\nnameserver 1.1.1.1\n");
        try (FileOutputStream dns = new FileOutputStream(new File(etc, "resolv.conf"))) { dns.write(nameservers.toString().getBytes(StandardCharsets.UTF_8)); }
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        List<String> command = new ArrayList<>(Arrays.asList(nativeDir + "/libproot.so", "--kill-on-exit", "--link2symlink", "-0", "-r", root.getPath(),
            "-b", "/dev", "-b", "/proc", "-w", "/app", "/usr/local/bin/python3", "-u", "/app/engine.py"));
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> env = builder.environment();
        env.remove("LD_PRELOAD");
        env.put("LD_LIBRARY_PATH", nativeDir);
        env.put("PROOT_TMP_DIR", new File(context.getCacheDir(), "proot-tmp").getPath());
        env.put("PROOT_LOADER", nativeDir + "/libproot-loader.so");
        File loader32 = new File(nativeDir, "libproot-loader32.so");
        if (loader32.isFile()) env.put("PROOT_LOADER_32", loader32.getPath());
        env.put("PROOT_NO_SECCOMP", "1");
        env.put("PYTHONHOME", "/usr/local");
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        env.put("LANG", "C.UTF-8");
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONDONTWRITEBYTECODE", "1");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        JSONObject result = null;
        String userError = null;
        try {
            try (OutputStream output = process.getOutputStream()) {
                output.write((request.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Native diagnostics are never forwarded or logged, because
                    // third-party error text could contain request credentials.
                    if (!line.startsWith("{")) continue;
                    JSONObject event;
                    try { event = new JSONObject(line); } catch (Exception ignored) { continue; }
                    switch (event.optString("event")) {
                        case "progress": progress.update(event.optString("message")); break;
                        case "result": result = event; break;
                        case "error": userError = event.optString("message", "更新失败，请重试。"); break;
                    }
                }
            }
            int code = process.waitFor();
            if (userError != null) throw new IOException(userError);
            if (code != 0 || result == null) throw new IOException("本地运行环境未正常完成。请重新打开工具后重试。");
            return result;
        } finally { process.destroy(); }
    }
}
