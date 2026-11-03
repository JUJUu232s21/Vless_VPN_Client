import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.GravityCompat;
import com.google.android.material.snackbar.Snackbar;
import java.util.ArrayList;
import java.util.List;
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private PermissionHelper permissionHelper;
    private ActivityResultLauncher<Intent> vpnPermissionLauncher;
    private EditText vlessconnect_text;
    private ImageView btnStartVpn;
    private ListView listSettings;
    List<String> dataList = new ArrayList<>();
    private SimpleTextAdapter adapter;
    private ListView saveConfigs;
    private List<String> configList = new ArrayList<>();
    private SimpleTextAdapter configAdapter;
    private ImageView addconfigs;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
                overridePendingTransition(0 , 0);
            }
        });

        permissionHelper = new PermissionHelper(this);


        androidx.drawerlayout.widget.DrawerLayout drawerLayout = findViewById(R.id.drawerLayout);
        if (drawerLayout != null) {
            try {
                java.lang.reflect.Field draggerField = androidx.drawerlayout.widget.DrawerLayout.class.getDeclaredField("mLeftDragger");
                draggerField.setAccessible(true);
                Object dragger = draggerField.get(drawerLayout);
                if (dragger != null) {
                    java.lang.reflect.Field edgeSizeField = dragger.getClass().getDeclaredField("mEdgeSize");
                    edgeSizeField.setAccessible(true);
                    int edgeSize = edgeSizeField.getInt(dragger);
                    int newEdgeSize = (int) (getResources().getDisplayMetrics().widthPixels * 0.5f);
                    edgeSizeField.setInt(dragger, Math.max(edgeSize, newEdgeSize));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error", e);
            }
        }

        ImageView leftMenuOpen = findViewById(R.id.left_Menu_Open);
        if (leftMenuOpen != null && drawerLayout != null) {
            leftMenuOpen.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        }

        listSettings = findViewById(R.id.listSettings);
        adapter = new SimpleTextAdapter(this, dataList);
        listSettings.setAdapter(adapter);

        addconfigs = findViewById(R.id.addconfigs);
        saveConfigs = findViewById(R.id.saveConfigs);

        configAdapter = new SimpleTextAdapter(this, configList);
        saveConfigs.setAdapter(configAdapter);
        loadSavedConfigs();

        if (addconfigs != null) {
            addconfigs.setOnClickListener(v -> {
                if (isVpnRunning()) {
                    Toast.makeText(this, "Отключите VPN для добавления конфигов!", Toast.LENGTH_SHORT).show();
                    return;
                }
                showAddConfigDialog();
            });
        }

        saveConfigs.setOnItemClickListener((parent, view, position, id) -> {
            if (isVpnRunning()) {
                Toast.makeText(this, "Отключите VPN для смены конфига!", Toast.LENGTH_SHORT).show();
                return;
            }
            String selectedConfig = configList.get(position);
            vlessconnect_text.setText(selectedConfig);
            if (drawerLayout != null) {
                drawerLayout.closeDrawers();
            }
        });

        saveConfigs.setOnItemLongClickListener((parent, view, position, id) -> {
            if (isVpnRunning()) {
                Toast.makeText(this, "Отключите VPN для удаления конфигов!", Toast.LENGTH_SHORT).show();
                return true;
            }
            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this, R.style.CustomAlertDialog)
                    .setTitle("Удалить конфиг")
                    .setPositiveButton("Удалить", (d, which) -> {
                        configList.remove(position);
                        saveConfigsToPrefs();
                        configAdapter.notifyDataSetChanged();
                    })
                    .setNegativeButton("Отмена", null)
                    .create();
            dialog.setOnShowListener(dialogInterface -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.blue));
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, R.color.blue));
            });

            dialog.show();
            return true;
        });

        CheckBox fgCheck = findViewById(R.id.Foreground_services_check);
        if (fgCheck != null) {
            SharedPreferences prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE);
            boolean isPersistent = prefs.getBoolean("persistent_notification", false);
            fgCheck.setChecked(isPersistent);

            fgCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.edit().putBoolean("persistent_notification", isChecked).apply();
            });
        }

        updateBatteryOptimizationState();

        listSettings.setOnItemLongClickListener((parent, view, position, id) -> {
            String itemText = dataList.get(position);
            int colonIdx = itemText.indexOf(':');
            if (colonIdx != -1) {
                if (isVpnRunning()) {
                    Toast.makeText(this, "Отключите VPN для редактирования параметров!", Toast.LENGTH_SHORT).show();
                    return true;
                }
                String paramName = itemText.substring(0, colonIdx).trim();
                String currentValue = itemText.substring(colonIdx + 1).trim();
                showEditParamDialog(paramName, currentValue);
                return true;
            }

            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Copied Text", itemText);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(MainActivity.this, "Текст скопирован!", Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        vpnPermissionLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) {
                startVpnService();
            } else {
                Toast.makeText(this, "Разрешение на VPN не получено", Toast.LENGTH_SHORT).show();
            }
        });

        btnStartVpn = findViewById(R.id.btnStartVpn);
        vlessconnect_text = findViewById(R.id.vlessconnect_text);
        updateVpnButtonState();

        SharedPreferences prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE);
        String savedVless = prefs.getString("vless_link", "");

        vlessconnect_text.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateVlessDetails(s.toString());
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        if (!savedVless.isEmpty()) {
            vlessconnect_text.setText(savedVless);
            updateVlessDetails(savedVless);
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            androidx.core.graphics.Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            return insets;
        });

        btnStartVpn.setOnClickListener(view -> {
            CloseKeyBoard();
            vlessconnect_text.clearFocus();
            String vlessUrl = vlessconnect_text.getText().toString().trim();
            if (vlessUrl.isEmpty()) {
                Toast.makeText(this, "Добавьте ссылку vless://", Toast.LENGTH_SHORT).show();
                return;
            }

            prefs.edit().putString("vless_link", vlessUrl).apply();

            if (isVpnRunning()) {
                stopVpnService();
            } else {
                try {
                    checkNotificationPermissionAndStartVpn();
                } catch (Exception e) {
                    Toast.makeText(this, "Ошибка запуска VPN: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void CloseKeyBoard() {
        InputMethodManager imm = (InputMethodManager) this.getSystemService(Activity.INPUT_METHOD_SERVICE);
        View view = this.getCurrentFocus();
        if (view == null) {
            view = new View(this);
        }
        imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private boolean isVpnRunning() {
        if (MyVpnService.isRunning) {
            return true;
        }
        SharedPreferences prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE);
        return prefs.getBoolean("service_running", false);
    }

    private void checkNotificationPermissionAndStartVpn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionHelper.check(android.Manifest.permission.POST_NOTIFICATIONS, status -> {
                prepareAndStartVpn();
            });
        } else {
            prepareAndStartVpn();
        }
    }

    private void prepareAndStartVpn() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            vpnPermissionLauncher.launch(intent);
        } else {
            startVpnService();
        }
    }

    private void startVpnService() {
        String vlessUrl = vlessconnect_text.getText().toString().trim();
        String configJson;
        try {
            configJson = VlessParser.parseToSingBoxConfig(vlessUrl);
        } catch (Exception e) {;
            Toast.makeText(this, "Ошибка VLESS ссылки: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, MyVpnService.class);
        intent.putExtra("config_json", configJson);
        ContextCompat.startForegroundService(this, intent);
        getSharedPreferences("vpn_prefs", MODE_PRIVATE).edit().putBoolean("service_running", true).apply();
        if (btnStartVpn != null) {
            btnStartVpn.setImageResource(R.drawable.baseline_power_settings_new_24);
        }
    }

    private void stopVpnService() {
        Intent intent = new Intent(this, MyVpnService.class);
        intent.setAction("STOP");
        startService(intent);
        stopService(new Intent(this, MyVpnService.class));
        getSharedPreferences("vpn_prefs", MODE_PRIVATE).edit().putBoolean("service_running", false).apply();
        if (btnStartVpn != null) {
            btnStartVpn.setImageResource(R.drawable.baseline_power_settings_new_24_red);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSystemBar();
        updateVpnButtonState();
        updateBatteryOptimizationState();
    }

    private void updateVpnButtonState() {
        if (btnStartVpn != null) {
            if (isVpnRunning()) {
                btnStartVpn.setImageResource(R.drawable.baseline_power_settings_new_24);
            } else {
                btnStartVpn.setImageResource(R.drawable.baseline_power_settings_new_24_red);
            }
        }
    }

    private void updateVlessDetails(String url) {
        dataList.clear();
        dataList.addAll(VlessParser.parseDetails(url));
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void showAddConfigDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_input, null);
        EditText etInput = dialogView.findViewById(R.id.etInput);

        AlertDialog dialog = new AlertDialog.Builder(MainActivity.this, R.style.CustomAlertDialog)
                .setTitle("Добавить ключ")
                .setView(dialogView)
                .setCancelable(false)
                .setPositiveButton("Добавить", (d, which) -> {
                    String userInput = etInput.getText().toString().trim();
                    if (userInput.startsWith("vless://")) {
                        if (configList.contains(userInput)) {
                            Toast.makeText(MainActivity.this, "Такой конфиг уже существует!", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        configList.add(0, userInput);
                        saveConfigsToPrefs();
                        configAdapter.notifyDataSetChanged();
                        vlessconnect_text.setText(userInput);
                    } else {
                        Toast.makeText(this, "Ошибка: ссылка должна начинаться с vless://", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.blue));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, R.color.blue));
        });

        dialog.show();
    }

    private void showEditParamDialog(String paramName, String currentValue) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_input, null);
        EditText etInput = dialogView.findViewById(R.id.etInput);
        if (etInput != null) {
            etInput.setHint(paramName);
            etInput.setText(currentValue);
            etInput.setSelection(currentValue.length());
        }

        AlertDialog dialog = new AlertDialog.Builder(MainActivity.this, R.style.CustomAlertDialog)
                .setTitle("Изменить " + paramName)
                .setView(dialogView)
                .setCancelable(false)
                .setPositiveButton("Сохранить", (d, which) -> {
                    if (etInput == null) return;
                    String newValue = etInput.getText().toString().trim();
                    if (newValue.isEmpty()) {
                        Toast.makeText(this, "Значение не может быть пустым", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String currentVless = vlessconnect_text.getText().toString().trim();
                    if (!currentVless.startsWith("vless://")) {
                        return;
                    }

                    String updatedVless = VlessParser.updateParamInVlessUrl(currentVless, paramName, newValue);

                    int indexInList = configList.indexOf(currentVless);
                    if (indexInList != -1) {
                        configList.set(indexInList, updatedVless);
                    } else {
                        configList.add(0, updatedVless);
                    }

                    saveConfigsToPrefs();
                    if (configAdapter != null) {
                        configAdapter.notifyDataSetChanged();
                    }

                    vlessconnect_text.setText(updatedVless);
                    updateVlessDetails(updatedVless);
                    Toast.makeText(this, paramName + " изменен", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Копировать", (d, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("Copied " + paramName, currentValue);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(MainActivity.this, "Текст скопирован!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Отмена", null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.blue));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(ContextCompat.getColor(this, R.color.blue));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, R.color.blue));
        });

        dialog.show();
    }

    private void loadSavedConfigs() {
        configList.clear();
        SharedPreferences prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE);
        String savedJson = prefs.getString("saved_configs_json", "[]");
        try {
            org.json.JSONArray jsonArray = new org.json.JSONArray(savedJson);
            for (int i = 0; i < jsonArray.length(); i++) {
                configList.add(jsonArray.getString(i));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error", e);
        }
        if (configAdapter != null) {
            configAdapter.notifyDataSetChanged();
        }
    }

    private void saveConfigsToPrefs() {
        SharedPreferences prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE);
        org.json.JSONArray jsonArray = new org.json.JSONArray(configList);
        prefs.edit().putString("saved_configs_json", jsonArray.toString()).apply();
    }

    private void updateBatteryOptimizationState() {
        CheckBox batteryCheck = findViewById(R.id.ignore_battery_optimizate);
        if (batteryCheck != null) {
            boolean isIgnoring = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    isIgnoring = pm.isIgnoringBatteryOptimizations(getPackageName());
                }
            }

            batteryCheck.setOnCheckedChangeListener(null);
            batteryCheck.setChecked(isIgnoring);

            batteryCheck.setOnClickListener(v -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                    boolean currentlyIgnoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
                    if (!currentlyIgnoring) {
                        try {
                            Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } catch (Exception e) {
                            try {
                                Intent intent = new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                                startActivity(intent);
                            } catch (Exception ignored) {}
                        }
                    } else {
                        try {
                            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } catch (Exception ignored) {}
                    }
                }
            });
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        getWindow().getDecorView().post(this::updateSystemBar);
    }

    private void updateSystemBar() {
        View sideMenu = findViewById(R.id.sideMenu);
        if (sideMenu != null) {
            android.view.ViewGroup.LayoutParams params = sideMenu.getLayoutParams();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.75);
            sideMenu.setLayoutParams(params);
        }
        View rightSideMenu = findViewById(R.id.rightSideMenu);
        if (rightSideMenu != null) {
            android.view.ViewGroup.LayoutParams params = rightSideMenu.getLayoutParams();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.75);
            rightSideMenu.setLayoutParams(params);
        }
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(false);
            controller.setAppearanceLightNavigationBars(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
    }
}
