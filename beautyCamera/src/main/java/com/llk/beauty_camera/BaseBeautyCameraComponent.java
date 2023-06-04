package com.llk.beauty_camera;

import android.graphics.SurfaceTexture;
import android.opengl.EGLContext;

import com.llk.beauty_camera.recorder.SpeedMode;

/**
 * author: llk
 * date  : 2023/6/4
 * detail:
 */
public abstract class BaseBeautyCameraComponent extends IBaseComponentlifecycle {

    /**
     * 绑定SharedContext
     * @param context SharedContext
     */
    public abstract void onBindSharedContext(EGLContext context);

    /**
     * 录制帧可用
     * @param texture
     * @param timestamp
     */
    public abstract void onRecordFrameAvailable(int texture, long timestamp);

    /**
     * SurfaceTexture 创建
     * @param surfaceTexture
     */
    public abstract void onSurfaceCreated(SurfaceTexture surfaceTexture);

    /**
     * SurfaceTexture 发生变化
     * @param width
     * @param height
     */
    public abstract void onSurfaceChanged(int width, int height);

    /**
     * SurfaceTexture 销毁
     */
    public abstract void onSurfaceDestroyed();

    /**
     * 是否关闭滤镜
     * @param isClose -
     */
    public abstract void closeBeautyFilter(boolean isClose);

    /**
     * 拍照
     */
    public abstract void takePicture();

    /**
     * 切换相机
     */
    public abstract void switchCamera();

    /**
     * 开始录制
     */
    public abstract void startRecord();

    /**
     * 停止录制
     */
    public abstract void stopRecord();

    /**
     * 取消录制
     */
    public abstract void cancelRecord();

    /**
     * 是否正处于录制过程
     * @return true：正在录制，false：非录制状态
     */
    public abstract boolean isRecording();

    /**
     * 设置是否允许录制音频
     * @param enable
     */
    public abstract void setRecordAudioEnable(boolean enable);

    /**
     * 设置录制时长
     * @param seconds 录制视频时长(秒)
     */
    public abstract void setRecordSeconds(int seconds);

    /**
     * 设置速度模式
     * @param mode 设置录制的速度模式
     */
    public abstract void setSpeedMode(SpeedMode mode);

    /**
     * 是否打开闪光灯
     * @param on    打开闪光灯
     */
    public abstract void changeFlashLight(boolean on);

    /**
     * 是否允许边框模糊
     * @param enable true:允许边框模糊
     */
    public abstract void enableEdgeBlurFilter(boolean enable);
}
