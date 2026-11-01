package df.root;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import df.root.databinding.ActivityMainBinding;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity implements IReporter {

    private static final String TAG = "dfroot";
    private static final int REQUEST_SELECT_KSUD = 4108;
    private static final int REQUEST_SELECT_KO = 4109;
    private static final long MAX_PAYLOAD_BYTES = 64L * 1024L * 1024L;
    private static final Pattern KMI_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+).*?(android\\d+)");
    private static final Pattern KERNEL_VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)");
    private static final Pattern KMI_VERSION_PATTERN = Pattern.compile("^android\\d+-(\\d+\\.\\d+)$");
    private static final String PREF_SELECTED_KO = ExploitRunner.PREF_SELECTED_BUNDLED_KO_KMI;
    private static final String AUTO_KO = ExploitRunner.AUTO_BUNDLED_KO;
    private static final String[] BUNDLED_KMIS = {
            "android12-5.10", "android13-5.10", "android13-5.15", "android14-5.15",
            "android14-6.1", "android15-6.6", "android16-6.12", "android17-6.18"
    };

    private ActivityMainBinding binding;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Executor mExec = Executors.newSingleThreadExecutor();

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
        mMain.post(() -> {
            boolean followTail = !binding.outputScroll.canScrollVertically(1);
            binding.outputView.append(msg);
            if (followTail) {
                binding.outputScroll.post(() -> binding.outputScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        // Keep long-press selection enabled for copying log lines. LogScrollView
        // separates a real vertical drag from a long press so the outer page
        // does not steal the gesture.
        binding.outputScroll.setNestedScrollingEnabled(false);

        updateDeviceProfile();
        updateKsudMode();
        updateKoMode();
        updateVendorTargetsUi();
        binding.btnSelectKsud.setOnClickListener(v -> selectPayload(REQUEST_SELECT_KSUD));
        binding.btnUseManagerKsud.setOnClickListener(v -> useManagerKsud());
        binding.btnSelectKo.setOnClickListener(v -> selectPayload(REQUEST_SELECT_KO));
        binding.btnUseBundledKo.setOnClickListener(v -> useBundledKo());
        binding.btnSaveVendorTargets.setOnClickListener(v -> saveVendorTargets());
        binding.btnResetVendorTargets.setOnClickListener(v -> resetVendorTargets());
        binding.koSelection.setOnItemClickListener((parent, view, position, id) ->
                onKoSelection(position));
        binding.githubLink.setOnClickListener(v -> openGithub());
        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_settings) {
                binding.homeScroll.setVisibility(View.GONE);
                binding.settingsScroll.setVisibility(View.VISIBLE);
                return true;
            }
            if (item.getItemId() == R.id.nav_home) {
                binding.settingsScroll.setVisibility(View.GONE);
                binding.homeScroll.setVisibility(View.VISIBLE);
                return true;
            }
            return false;
        });

        binding.btnRun.setOnClickListener(v -> {
            binding.btnRun.setEnabled(false);
            binding.outputView.setText("");
            mExec.execute(this::runExploit);
        });
    }

    private void selectPayload(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, requestCode);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != REQUEST_SELECT_KSUD && requestCode != REQUEST_SELECT_KO)
                || resultCode != RESULT_OK || data == null || data.getData() == null) return;

        try {
            if (requestCode == REQUEST_SELECT_KSUD) importCustomKsud(data.getData());
            else importCustomKo(data.getData());
        } catch (Exception e) {
            Log.e(TAG, "payload import failed", e);
            Toast.makeText(this, "无法使用该文件", Toast.LENGTH_LONG).show();
            report("导入载荷失败：" + e + "\n");
        }
    }

    private long copyToTemp(Uri source, File temp) throws IOException {
        long copied = 0;
        try (InputStream in = getContentResolver().openInputStream(source);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new IOException("文件提供者未返回数据");
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1; ) {
                copied += n;
                if (copied > MAX_PAYLOAD_BYTES) throw new IOException("文件超过 64 MiB");
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            temp.delete();
            throw e;
        }
        if (copied == 0) {
            temp.delete();
            throw new IOException("所选文件为空");
        }
        return copied;
    }

    private static void replaceFile(File temp, File destination) throws IOException {
        if (destination.exists() && !destination.delete()) {
            temp.delete();
            throw new IOException("无法替换之前的文件");
        }
        if (!temp.renameTo(destination)) {
            temp.delete();
            throw new IOException("无法暂存所选文件");
        }
    }

    private void importCustomKsud(Uri source) throws IOException {
        File dataDir = appDataDir();
        if (!dataDir.isDirectory() && !dataDir.mkdirs())
            throw new IOException("无法创建应用数据目录");

        File customKsud = new File(dataDir, "ksud.custom");
        File tempKsud = new File(dataDir, "ksud.custom.tmp");
        copyToTemp(source, tempKsud);
        if (!isArm64Executable(tempKsud)) {
            tempKsud.delete();
            throw new IOException("请选择 arm64 Android ksud 可执行文件或 shell 包装脚本");
        }
        if (!tempKsud.setExecutable(true, false)) {
            tempKsud.delete();
            throw new IOException("无法将 ksud 标记为可执行");
        }
        replaceFile(tempKsud, customKsud);

        String label = source.getLastPathSegment();
        if (label == null || label.isEmpty()) label = "自定义文件";
        getSharedPreferences("dfroot", MODE_PRIVATE)
                .edit().putString("custom_ksud_label", label).apply();
        updateKsudMode();
        report("已选择自定义 ksud：" + label + "\n");
    }

    private void importCustomKo(Uri source) throws IOException {
        File dataDir = appDataDir();
        if (!dataDir.isDirectory() && !dataDir.mkdirs())
            throw new IOException("无法创建应用数据目录");

        File customKo = ExploitRunner.customKoFile(this);
        File tempKo = new File(dataDir, "dirtyfrag.custom.ko.tmp");
        copyToTemp(source, tempKo);
        String label = displayName(source);
        if (!isDfRootKo(tempKo)) {
            tempKo.delete();
            throw new IOException("请为内核 " + currentKmiLabel()
                    + " 选择带匹配内核 vermagic 的 arm64 DFRoot dirtyfrag.ko");
        }
        replaceFile(tempKo, customKo);

        getSharedPreferences("dfroot", MODE_PRIVATE)
                .edit().putString("custom_ko_label", label)
                .putString(PREF_SELECTED_KO, "custom").apply();
        updateKoMode();
        report("已选择手动 DFRoot 模块：" + label + "（内核 " + currentKmiLabel() + "）\n");
    }

    private static boolean isArm64Executable(File file) throws IOException {
        byte[] header = readHeader(file, 20);
        boolean arm64Elf = (header[0] & 0xff) == 0x7f
                && header[1] == 'E' && header[2] == 'L' && header[3] == 'F'
                && header[4] == 2 && header[5] == 1
                && (header[18] & 0xff) == 0xb7 && header[19] == 0;
        return arm64Elf || (header[0] == '#' && header[1] == '!');
    }

    private boolean isDfRootKo(File file) throws IOException {
        byte[] header = readHeader(file, 20);
        boolean arm64Relocatable = (header[0] & 0xff) == 0x7f
                && header[1] == 'E' && header[2] == 'L' && header[3] == 'F'
                && header[4] == 2 && header[5] == 1
                && header[16] == 1 && header[17] == 0
                && (header[18] & 0xff) == 0xb7 && header[19] == 0;
        String kernelVersion = currentKernelVersion();
        String kernelVermagicPrefix = kernelVersion == null
                ? null : "vermagic=" + kernelVersion + ".";
        return arm64Relocatable
                && containsAscii(file, "name=dirtyfrag")
                && containsAscii(file, "description=DFRoot LKM")
                && kernelVermagicPrefix != null
                && containsAscii(file, kernelVermagicPrefix);
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String value = cursor.getString(column);
                    if (value != null && !value.isEmpty()) return value;
                }
            }
        } catch (Exception ignored) { }
        String fallback = uri.getLastPathSegment();
        return fallback == null || fallback.isEmpty() ? "自定义模块" : fallback;
    }

    private static byte[] readHeader(File file, int length) throws IOException {
        byte[] header = new byte[length];
        try (InputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < header.length) {
                int count = in.read(header, offset, header.length - offset);
                if (count < 0) throw new IOException("file is too short");
                offset += count;
            }
        }
        return header;
    }

    private static boolean containsAscii(File file, String text) throws IOException {
        byte[] needle = text.getBytes(StandardCharsets.US_ASCII);
        byte[] buffer = new byte[8192 + needle.length];
        int carry = 0;
        try (InputStream in = new FileInputStream(file)) {
            for (int read; (read = in.read(buffer, carry, 8192)) != -1; ) {
                int length = carry + read;
                for (int i = 0; i <= length - needle.length; i++) {
                    int j = 0;
                    while (j < needle.length && buffer[i + j] == needle[j]) j++;
                    if (j == needle.length) return true;
                }
                carry = Math.min(needle.length - 1, length);
                System.arraycopy(buffer, length - carry, buffer, 0, carry);
            }
        }
        return false;
    }

    private void useManagerKsud() {
        File customKsud = new File(appDataDir(), "ksud.custom");
        if (customKsud.exists() && !customKsud.delete()) {
            Toast.makeText(this, "无法删除自定义 ksud 文件", Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences("dfroot", MODE_PRIVATE).edit().remove("custom_ksud_label").apply();
        updateKsudMode();
        report("正在使用自动管理器 ksud 选择（默认）\n");
    }

    private void useBundledKo() {
        File customKo = ExploitRunner.customKoFile(this);
        if (customKo.exists() && !customKo.delete()) {
            Toast.makeText(this, "无法删除所选模块", Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences("dfroot", MODE_PRIVATE).edit()
                .remove("custom_ko_label").putString(PREF_SELECTED_KO, AUTO_KO).apply();
        updateKoMode();
        report("正在使用按 KMI 选择的内置 DirtyFrag 模块（默认）\n");
    }

    private void onKoSelection(int position) {
        File customKo = ExploitRunner.customKoFile(this);
        int customPosition = BUNDLED_KMIS.length + 1;
        if (customKo.isFile() && position == customPosition) return;

        String selectedKmi = position == 0 ? AUTO_KO : BUNDLED_KMIS[position - 1];
        if (!AUTO_KO.equals(selectedKmi) && !sameKernelVersion(selectedKmi, currentKmi())) {
            Toast.makeText(this, "该模块针对不同内核版本。选择未更改。",
                    Toast.LENGTH_LONG).show();
            updateKoMode();
            return;
        }

        if (customKo.exists() && !customKo.delete()) {
            Toast.makeText(this, "无法删除所选自定义模块", Toast.LENGTH_LONG).show();
            updateKoMode();
            return;
        }
        getSharedPreferences("dfroot", MODE_PRIVATE).edit()
                .remove("custom_ko_label").putString(PREF_SELECTED_KO, selectedKmi).apply();
        updateKoMode();
        String actualKmi = AUTO_KO.equals(selectedKmi) ? resolvedAutoKmi() : selectedKmi;
        report(actualKmi == null
                ? "内置模块选择：当前内核 KMI 无匹配 " + currentKmiLabel() + "\n"
                : "内置模块选择：dirtyfrag-" + actualKmi + ".ko\n");
    }

    private void updateKsudMode() {
        File customKsud = new File(appDataDir(), "ksud.custom");
        String label = getSharedPreferences("dfroot", MODE_PRIVATE)
                .getString("custom_ksud_label", "自定义文件");
        binding.ksudMode.setText(customKsud.isFile()
                ? "加载器：自定义 ksud（" + label + "）"
                : "加载器：自动（KernelSU Manager → ReSukiSU Manager → 系统 ksud）");
    }

    private void updateVendorTargetsUi() {
        String targets = ExploitRunner.getVendorTargets(this);
        binding.vendorTargetsInput.setText(targets);
        int count = targets.isEmpty() ? 0 : targets.split("\\R").length;
        binding.vendorTargetsStatus.setText(count + " 个目标已配置；按从上到下顺序尝试。");
    }

    private void saveVendorTargets() {
        String raw = binding.vendorTargetsInput.getText() == null
                ? "" : binding.vendorTargetsInput.getText().toString();
        String error = ExploitRunner.validateVendorTargets(raw);
        if (error != null) {
            binding.vendorTargetsLayout.setError(error);
            return;
        }
        String normalized = ExploitRunner.normalizeVendorTargets(raw);
        getSharedPreferences("dfroot", MODE_PRIVATE).edit()
                .putString(ExploitRunner.PREF_VENDOR_TARGETS, normalized).apply();
        binding.vendorTargetsLayout.setError(null);
        updateVendorTargetsUi();
        report("已保存 Vendor 目标配置；下次运行时生效\n");
        Toast.makeText(this, "Vendor 目标配置已保存", Toast.LENGTH_SHORT).show();
    }

    private void resetVendorTargets() {
        getSharedPreferences("dfroot", MODE_PRIVATE).edit()
                .remove(ExploitRunner.PREF_VENDOR_TARGETS).apply();
        binding.vendorTargetsLayout.setError(null);
        updateVendorTargetsUi();
        report("已重置 Vendor 目标配置为默认值\n");
        Toast.makeText(this, "已恢复默认 Vendor 目标", Toast.LENGTH_SHORT).show();
    }

    private void updateKoMode() {
        File customKo = ExploitRunner.customKoFile(this);
        String label = getSharedPreferences("dfroot", MODE_PRIVATE)
                .getString("custom_ko_label", "自定义模块");
        if (customKo.isFile()) {
            binding.koMode.setText("已选择：自定义 DFRoot 辅助模块 · " + label);
            binding.koGuidance.setText("正在使用你选择的辅助模块。如需适配其他内核，请为对应设备的 KMI 构建 DFRoot dirtyfrag.ko 并在此选择。");
        } else {
            String selection = normalizedKoSelection();
            boolean automatic = AUTO_KO.equals(selection);
            String actualKmi = automatic ? resolvedAutoKmi() : selection;
            if (actualKmi == null) {
                binding.koMode.setText("KMI 无匹配的内置模块：" + currentKmiLabel());
                binding.koGuidance.setText("该内核未在 uname 中暴露 Android KMI。请手动选择匹配的 arm64 DFRoot .ko，或为当前内核构建一个。");
            } else if (automatic && !actualKmi.equals(currentKmi())) {
                binding.koMode.setText("已选择：dirtyfrag-" + actualKmi + ".ko · 回退用于 " + currentKmiLabel());
                binding.koGuidance.setText("没有完全匹配的内置 KMI。自动模式使用相同主/次版本回退；兼容性未验证。如有需要，构建精确 KMI 的 DFRoot 辅助模块并选择。");
            } else {
                binding.koMode.setText("已选择：dirtyfrag-" + actualKmi + ".ko"
                        + (automatic ? " · 自动匹配 " + currentKmi() : " · 手动 KMI " + actualKmi));
                binding.koGuidance.setText("自动模式使用检测到的 KMI。你可以选择相同主/次版本的其他内置变体，或构建并选择自定义 DFRoot 辅助模块。仅凭 KMI 不能证明完全兼容。");
            }
        }
        updateKoSelector();
        updateRunAvailability();
    }

    private void updateKoSelector() {
        File customKo = ExploitRunner.customKoFile(this);
        String autoKmi = resolvedAutoKmi();
        if (autoKmi == null && currentKernelVersion() == null) {
            String[] options = customKo.isFile()
                    ? new String[]{"无内置 .ko：" + currentKmiLabel(), "自定义文件 · "
                            + getSharedPreferences("dfroot", MODE_PRIVATE)
                                    .getString("custom_ko_label", "自定义模块")}
                    : new String[]{"无内置 .ko：" + currentKmiLabel()};
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_dropdown_item_1line, options);
            binding.koSelection.setAdapter(adapter);
            binding.koSelectionLayout.setEnabled(false);
            binding.koSelection.setEnabled(false);
            binding.koSelection.setText(options[customKo.isFile() ? 1 : 0], false);
            return;
        }

        binding.koSelectionLayout.setEnabled(true);
        binding.koSelection.setEnabled(true);
        String[] options = new String[BUNDLED_KMIS.length + 1 + (customKo.isFile() ? 1 : 0)];
        options[0] = autoKmi == null
                ? "自动 · 无 Android KMI 匹配"
                : "自动 · dirtyfrag-" + autoKmi + ".ko"
                    + (autoKmi.equals(currentKmi()) ? "" : " · 回退用于 " + currentKmiLabel());
        String runningVersion = currentKernelVersion();
        for (int i = 0; i < BUNDLED_KMIS.length; i++) {
            String kmi = BUNDLED_KMIS[i];
            String suffix = runningVersion != null && runningVersion.equals(kernelVersion(kmi))
                    ? " · 内核版本一致" : " · 内核版本不同";
            options[i + 1] = "dirtyfrag-" + kmi + ".ko" + suffix;
        }
        if (customKo.isFile()) {
            String label = getSharedPreferences("dfroot", MODE_PRIVATE)
                    .getString("custom_ko_label", "自定义模块");
            options[options.length - 1] = "自定义文件 · " + label;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, options);
        binding.koSelection.setAdapter(adapter);

        String selected = normalizedKoSelection();
        int position = 0;
        if (customKo.isFile()) {
            position = options.length - 1;
        } else if (!AUTO_KO.equals(selected)) {
            for (int i = 0; i < BUNDLED_KMIS.length; i++) {
                if (BUNDLED_KMIS[i].equals(selected)) {
                    position = i + 1;
                    break;
                }
            }
        }
        binding.koSelection.setText(options[position], false);
    }

    private String normalizedKoSelection() {
        String selected = getSharedPreferences("dfroot", MODE_PRIVATE)
                .getString(PREF_SELECTED_KO, AUTO_KO);
        if (ExploitRunner.customKoFile(this).isFile() || AUTO_KO.equals(selected)) return selected;

        boolean known = false;
        for (String kmi : BUNDLED_KMIS) if (kmi.equals(selected)) known = true;
        if (known && sameKernelVersion(selected, currentKmi())) return selected;

        getSharedPreferences("dfroot", MODE_PRIVATE).edit()
                .putString(PREF_SELECTED_KO, AUTO_KO).apply();
        return AUTO_KO;
    }

    private String resolvedAutoKmi() {
        String deviceKmi = currentKmi();
        if (deviceKmi == null) return null;
        for (String kmi : BUNDLED_KMIS) if (kmi.equals(deviceKmi)) return kmi;
        String version = kernelVersion(deviceKmi);
        if (version == null) return null;
        for (String kmi : BUNDLED_KMIS) {
            if (version.equals(kernelVersion(kmi))) return kmi;
        }
        return null;
    }

    private static String kernelVersion(String kmi) {
        if (kmi == null) return null;
        Matcher matcher = KMI_VERSION_PATTERN.matcher(kmi);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static boolean sameKernelVersion(String firstKmi, String secondKmi) {
        String firstVersion = kernelVersion(firstKmi);
        String secondVersion = secondKmi == null ? currentKernelVersion() : kernelVersion(secondKmi);
        return firstVersion != null && firstVersion.equals(secondVersion);
    }

    private void updateRunAvailability() {
        String selected = normalizedKoSelection();
        boolean manualBundle = !AUTO_KO.equals(selected) && sameKernelVersion(selected, null);
        boolean hasModule = ExploitRunner.customKoFile(this).isFile()
                || resolvedAutoKmi() != null || manualBundle;
        binding.btnRun.setEnabled(hasModule && !new File("/dev/df").exists());
    }

    private void updateDeviceProfile() {
        String kernel = System.getProperty("os.version", "Unknown");
        String kmi = currentKmi();
        String soc = Build.SOC_MODEL;
        if (soc == null || soc.isEmpty() || "unknown".equalsIgnoreCase(soc)) soc = "Unknown";
        String kmiLabel = kmi == null
                ? "无法识别（内核 " + currentKernelVersion() + "）"
                : kmi;
        binding.deviceDetails.setText("品牌：" + Build.MANUFACTURER + "\n"
                + "型号 / 设备：" + Build.MODEL + " / " + Build.DEVICE + "\n"
                + "处理器：" + soc + "\n"
                + "安卓版本：" + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）\n"
                + "安全补丁：" + Build.VERSION.SECURITY_PATCH + "\n"
                + "内核：" + kernel + "\n"
                + "KMI：" + kmiLabel);
    }

    private static String currentKmi() {
        String release = System.getProperty("os.version", "");
        Matcher matcher = KMI_PATTERN.matcher(release);
        if (!matcher.find()) return null;
        return matcher.group(3) + "-" + matcher.group(1) + "." + matcher.group(2);
    }

    private static String currentKernelVersion() {
        Matcher matcher = KERNEL_VERSION_PATTERN.matcher(System.getProperty("os.version", ""));
        return matcher.find() ? matcher.group(1) + "." + matcher.group(2) : null;
    }

    private static String currentKmiLabel() {
        String kmi = currentKmi();
        if (kmi != null) return kmi;
        String version = currentKernelVersion();
        return version == null ? "unknown" : "unknown-" + version;
    }

    private void openGithub() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ankitrawatgit")));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_LONG).show();
        }
    }

    private File appDataDir() {
        return createDeviceProtectedStorageContext().getFilesDir().getParentFile();
    }

    private void runExploit() {
        try {
            int rc = ExploitRunner.run(this, this);
            String msg = rc == 0 ? "DirtyFrag Android Root：成功"
                       : rc == 1 ? "失败：ksud 退出报错"
                       : rc == 2 ? "失败：请检查日志"
                       : "失败：文件补丁失败";
            mMain.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            Log.e(TAG, "exploit exception", e);
            report("\nexception: " + e + "\n");
        } finally {
            mMain.post(this::updateRunAvailability);
        }
    }
}
