package com.example.thinclient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import edu.cmu.cs.gabriel.client.comm.ServerComm;
import edu.cmu.cs.gabriel.client.results.ErrorType;
import edu.cmu.cs.gabriel.protocol.Protos;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private volatile boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String[] permissions = new String[] {
                Manifest.permission.INTERNET, Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE};
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) !=
                    PackageManager.PERMISSION_GRANTED) {
                requestPermissions(permissions, 0);
                break;
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        running = true;
        pool.execute(() -> {
            BatteryManager batteryManager =
                    (BatteryManager) getSystemService(Context.BATTERY_SERVICE);

            pool.execute(() -> {
                Consumer<Protos.ResultWrapper> consumer = resultWrapper -> {
                    Protos.ResultWrapper.Result result = resultWrapper.getResults(0);
                    ByteString byteString = result.getPayload();
                    byte[] bytes = byteString.toByteArray();
                    String className = new String(bytes);

                    // System.out.println(className);
                };

                Consumer<ErrorType> onDisconnect = errorType -> {
                    Log.e(TAG, "Disconnect Error:" + errorType.name());
                    finish();
                };

                ServerComm serverComm = ServerComm.createServerComm(
                        consumer, "vm030.elijah.cs.cmu.edu", 9099, getApplication(), onDisconnect);

                File testImages = new File( Environment.getExternalStorageDirectory().getPath()
                        + "/test_images/stirling");
                int count = 0;
                int total = 0;
                long start = SystemClock.uptimeMillis();
                long startEnergy = batteryManager.getLongProperty(
                        BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);

                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream("/sdcard/output/stirling/baseline_" + System.currentTimeMillis() + ".txt");
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }

                for (File classDir : testImages.listFiles()) {
                    String correctClass = classDir.getName();
                    for (File imageFile : classDir.listFiles()) {
                        serverComm.send(packFrame(imageFile.getPath()), "images", true);

                        if (count == 20) {
                            long end = SystemClock.uptimeMillis();
                            System.out.println("time change" + (end - start) + "ms");

                            long endEnergy = batteryManager.getLongProperty(
                                    BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);
                            System.out.println("energy: " + endEnergy);
                            System.out.println("energy change: " + (endEnergy - startEnergy) + "nWh");

                            try {
                                fos.write(("time change: " + (end - start) + "ms energy change: " + (startEnergy - endEnergy) + " nWh\n").getBytes());
                            } catch (IOException e) {
                                e.printStackTrace();
                            }

                            count = 0;
                            start = SystemClock.uptimeMillis();

                            startEnergy = batteryManager.getLongProperty(
                                    BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);
                        }

                        if (total == 6000) {
                            try {
                                fos.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            running = false;
                            return;
                        }

                        count++;
                        total++;
                    }
                }
            });

            measureBattery(batteryManager);
            finish();
        });
    }

    public Protos.InputFrame packFrame(String path) {
        try {
            FileInputStream fos = new FileInputStream(path);
            ByteString imageContents = ByteString.readFrom(fos);
            fos.close();

            return Protos.InputFrame.newBuilder()
                    .setPayloadType(Protos.PayloadType.IMAGE)
                    .addPayloads(imageContents).build();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void measureBattery(BatteryManager batteryManager) {
        try {
            FileOutputStream fos = new FileOutputStream(
                    "/sdcard/output/bg_thread/thin_" +
                            System.currentTimeMillis() + ".txt");
            BatteryReceiver batteryReceiver = new BatteryReceiver();
            IntentFilter intentFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            registerReceiver(batteryReceiver, intentFilter);

            int endCount = 0;

            while (true) {
                long start = SystemClock.uptimeMillis();

                long toWait = Math.max(0, ((start + 100) - SystemClock.uptimeMillis()));
                Thread.sleep(toWait);

                if (!running) {
                    break;
                }
                int current = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                int voltage = batteryReceiver.getVoltage();
                fos.write(("current: " + current + " voltage: " + voltage + "\n").getBytes());
            }
            fos.close();
            unregisterReceiver(batteryReceiver);
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
        }
    }

    private class BatteryReceiver extends BroadcastReceiver {
        private int voltage;

        private int getVoltage() {
            return voltage;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Integer.MIN_VALUE);
        }
    }
}