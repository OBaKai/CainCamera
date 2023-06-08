package com.llk.app_bc;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.Log;
import android.view.TextureView;
import android.widget.Toast;

import com.llk.beauty_camera.BeautyCameraManager;
import com.llk.beauty_camera.recorder.MediaInfo;

public class MainActivity extends AppCompatActivity {

    private BeautyCameraManager manager;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.btn1).setOnClickListener(v -> {
            manager.startRecord();
        });
        findViewById(R.id.btn2).setOnClickListener(v -> {
            manager.stopRecord();
        });

        TextureView textureView = findViewById(R.id.ctv);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                manager.onSurfaceCreated(surface);
                manager.onSurfaceChanged(width, height);
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                manager.onSurfaceChanged(width, height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                manager.onSurfaceDestroyed();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
        });

        manager = new BeautyCameraManager(this);
        manager.onCreate();


        manager.setRecordSeconds(60);


        manager.setCameraStateCallback(new BeautyCameraManager.CameraStateCallback() {
            @Override
            public void onCameraRecordStart() {

            }

            @Override
            public void onCameraRecording(float progress) {

            }

            @Override
            public void onCamereRecordFinish(MediaInfo mediaInfo) {
                Log.e("llk", "onCamereRecordFinish " + mediaInfo.getFilePath());
                Toast.makeText(MainActivity.this, mediaInfo.getFilePath(), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onCamereRecordError(int error) {

            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        manager.onResume();
    }

    @Override
    protected void onStop() {
        super.onStop();
        manager.onStop();
    }

    @Override
    protected void onStart() {
        super.onStart();
        manager.onStart();
    }

    @Override
    protected void onPause() {
        super.onPause();
        manager.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        manager.onDestroy();
    }
}