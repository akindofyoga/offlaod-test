package com.example.thinclient;

import static android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import com.google.protobuf.ByteString;

import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.task.core.BaseOptions;
import org.tensorflow.lite.task.vision.detector.Detection;
import org.tensorflow.lite.task.vision.detector.ObjectDetector;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
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

        // Request ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION on Vuzix Blade 2
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + BuildConfig.APPLICATION_ID));
                startActivity(intent);
            }
        }

        // Permissions for ODG, Magicleap, and Google Glass
//        String[] permissions = new String[] {
//                Manifest.permission.INTERNET, Manifest.permission.READ_EXTERNAL_STORAGE,
//                Manifest.permission.WRITE_EXTERNAL_STORAGE};
//        for (String permission : permissions) {
//            if (ContextCompat.checkSelfPermission(this, permission) !=
//                    PackageManager.PERMISSION_GRANTED) {
//                requestPermissions(permissions, 0);
//                break;
//            }
//        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.execute(() -> {
            BatteryManager batteryManager =
                    (BatteryManager) getSystemService(Context.BATTERY_SERVICE);

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

            for (String odname : new String[] {"ed0.tflite", "ed1.tflite", "ed2.tflite"}) {
                running = true;
                pool.execute(() -> {
                    File testImages = new File(Environment.getExternalStorageDirectory().getPath()
                            + "/test_images/stirling");
                    int count = 0;
                    int total = 0;
                    long start = SystemClock.uptimeMillis();
                    long startEnergy = batteryManager.getLongProperty(
                            BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);

                    FileOutputStream fos = null;
                    try {
                        //fos = new FileOutputStream("/sdcard/output/stirling/thin_" + System.currentTimeMillis() + ".txt");
                        fos = new FileOutputStream("/sdcard/output/stirling/" + odname + "_" + System.currentTimeMillis() + ".txt");
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }

                    ObjectDetector objectDetector = null;
                    try {
                        ObjectDetector.ObjectDetectorOptions.Builder optionsBuilder =
                                ObjectDetector.ObjectDetectorOptions.builder()
                                        .setScoreThreshold(0.4f)
                                        .setMaxResults(1);

                        BaseOptions.Builder baseOptionsBuilder = BaseOptions.builder().setNumThreads(1);

                        // This check is necessary because baseOptionsBuilder.useGpu(); will not work on Google
                        // glass.
                        if ((new CompatibilityList()).isDelegateSupportedOnThisDevice()) {
                            baseOptionsBuilder.useGpu();
                        }

                        optionsBuilder.setBaseOptions(baseOptionsBuilder.build());

                        File modelFile = new File("/sdcard/models/stirling/" + odname);
                        objectDetector = ObjectDetector.createFromFileAndOptions(
                                modelFile, optionsBuilder.build());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    for (File classDir : testImages.listFiles()) {
                        String correctClass = classDir.getName();
                        for (File imageFile : classDir.listFiles()) {
//                            try {
//                                FileInputStream fis = new FileInputStream(imageFile.getPath());
//                                serverComm.send(packFrame(fis), "images", true);
//                                fis.close();
//                            } catch (IOException e) {
//                                e.printStackTrace();
//                            }

                            Bitmap image = BitmapFactory.decodeFile(imageFile.getPath());
                            ImageProcessor imageProcessor = (new ImageProcessor.Builder()).build();
                            TensorImage tensorImage = imageProcessor.process(TensorImage.fromBitmap(image));

                            List<Detection> detections = objectDetector.detect(tensorImage);
                            if (detections.size() == 0) {
                                continue;
                            }
                            if (detections.size() > 1) {
                                throw new RuntimeException();
                            }
                            RectF rectF = detections.get(0).getBoundingBox();

                            if ((rectF.top < 0) || (rectF.left < 0) || (rectF.width() == 0) ||
                                    (rectF.height() == 0)) {
                                continue;
                            }

                            if ((rectF.left + rectF.width()) > image.getWidth()) {
                                System.out.println("bad");
                                continue;
                            }

                            image = Bitmap.createBitmap(image, (int)rectF.left, (int)rectF.top,
                                    (int)rectF.width(), (int)rectF.height());

                            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                            image.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);
                            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(
                                    byteArrayOutputStream.toByteArray());
                            serverComm.send(packFrame(byteArrayInputStream), "images", true);

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

                            if (total == 120) {
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

                measureBattery(batteryManager, odname);
            }
            finish();
        });
    }

    public Protos.InputFrame packFrame(InputStream inputStream) {
        try {
            ByteString imageContents = ByteString.readFrom(inputStream);

            return Protos.InputFrame.newBuilder()
                    .setPayloadType(Protos.PayloadType.IMAGE)
                    .addPayloads(imageContents).build();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void measureBattery(BatteryManager batteryManager, String odname) {
        try {
            FileOutputStream fos = new FileOutputStream(
                    "/sdcard/output/bg_thread/" + odname + "_" +
                            System.currentTimeMillis() + ".txt");
            BatteryReceiver batteryReceiver = new BatteryReceiver();
            IntentFilter intentFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            registerReceiver(batteryReceiver, intentFilter);

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