package com.example.thinclient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import com.google.protobuf.ByteString;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.function.Consumer;

import edu.cmu.cs.gabriel.client.comm.ServerComm;
import edu.cmu.cs.gabriel.client.results.ErrorType;
import edu.cmu.cs.gabriel.protocol.Protos;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

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

        Consumer<Protos.ResultWrapper> consumer = resultWrapper -> {
            Protos.ResultWrapper.Result result = resultWrapper.getResults(0);
            ByteString byteString = result.getPayload();
            byte[] bytes = byteString.toByteArray();
            String className = new String(bytes);

            System.out.println(className);
        };

        Consumer<ErrorType> onDisconnect = errorType -> {
            Log.e(TAG, "Disconnect Error:" + errorType.name());
            finish();
        };

        ServerComm serverComm = ServerComm.createServerComm(
                consumer, "vm030.elijah.cs.cmu.edu", 9099, getApplication(), onDisconnect);


        serverComm.send(packFrame("/sdcard/test_images/stirling/1screw/2_frame-0000.jpg"), "images", true);
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
}