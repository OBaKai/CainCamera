package com.llk.beauty_camera.renderer;

import android.graphics.SurfaceTexture;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.llk.beauty_camera.BaseBeautyCameraComponent;
import com.llk.beauty_camera.camera.CameraParam;
import com.llk.beauty_camera.filter.gles.EglCore;
import com.llk.beauty_camera.filter.gles.WindowSurface;
import com.llk.beauty_camera.filter.utils.OpenGLUtils;

import java.lang.ref.WeakReference;

import javax.microedition.khronos.opengles.GL10;

/**
 * 相机渲染器
 */
public class CameraRenderer extends Thread {

    private static final String TAG = "CameraRenderer";

    private final Object mSync = new Object();

    private int mPriority;
    private Looper mLooper;

    private @Nullable CameraRenderHandler mHandler;

    // 截屏 - 拍照时候用的
    private GLImageReader mImageReader;
    // EGL共享上下文
    private EglCore mEglCore;
    // 预览用的EGLSurface
    private WindowSurface mDisplaySurface;
    private volatile boolean mNeedToAttach;
    private WeakReference<SurfaceTexture> mWeakSurfaceTexture;
    // 矩阵
    private final float[] mMatrix = new float[16];
    // 输入OES纹理
    private int mInputTexture = OpenGLUtils.GL_NOT_TEXTURE;
    // 当前纹理
    private int mCurrentTexture;
    // 渲染管理器
    private final RenderManager mRenderManager;
    // 计算帧率
    private final FrameRateMeter mFrameRateMeter;
    // 预览参数
    private CameraParam mCameraParam;

    // Presenter
    private final WeakReference<BaseBeautyCameraComponent> mWeakPresenter;

    private volatile boolean mThreadStarted;

    public CameraRenderer(@NonNull BaseBeautyCameraComponent presenter) {
        super(TAG);
        mPriority = Process.THREAD_PRIORITY_DISPLAY;
        mWeakPresenter = new WeakReference<>(presenter);
        mCameraParam = CameraParam.getInstance();
        mRenderManager = new RenderManager();
        mFrameRateMeter = new FrameRateMeter();
        mThreadStarted = false;
    }

    /**
     * 初始化渲染器
     */
    public void initRenderer() {
        synchronized (this) {
            if (!mThreadStarted) {
                start();
                mThreadStarted = true;
            }
        }
    }

    /**
     * 销毁渲染器
     */
    public void destroyRenderer() {
        synchronized (this) {
            quit();
        }
    }

    /**
     * 暂停时释放SurfaceTexture
     */
    public void onPause() {
        if (mWeakSurfaceTexture != null) {
            mWeakSurfaceTexture.clear();
        }
    }

    /**
     * 绑定Surface
     * @param surface
     */
    public void onSurfaceCreated(Surface surface) {
        Handler handler = getHandler();
        handler.sendMessage(handler.obtainMessage(CameraRenderHandler.MSG_INIT, surface));
    }

    /**
     * 绑定SurfaceTexture
     * @param surfaceTexture
     */
    public void onSurfaceCreated(SurfaceTexture surfaceTexture) {
        Handler handler = getHandler();
        handler.sendMessage(handler.obtainMessage(CameraRenderHandler.MSG_INIT, surfaceTexture));
    }

    /**
     * 设置预览大小
     * @param width
     * @param height
     */
    public void onSurfaceChanged(int width, int height) {
        Handler handler = getHandler();
        handler.sendMessage(handler.obtainMessage(CameraRenderHandler.MSG_DISPLAY_CHANGE, width, height));
    }

    /**
     * 解绑Surface
     */
    public void onSurfaceDestroyed() {
        Handler handler = getHandler();
        handler.sendMessage(handler.obtainMessage(CameraRenderHandler.MSG_DESTROY));
    }

    /**
     * 设置输入纹理大小
     * @param width
     * @param height
     */
    public void setTextureSize(int width, int height) {
        mRenderManager.setTextureSize(width, height);
        if (mImageReader != null) {
            mImageReader.init(width, height);
        }
    }

    /**
     * 绑定外部输入的SurfaceTexture
     * @param surfaceTexture
     */
    public void bindInputSurfaceTexture(@NonNull SurfaceTexture surfaceTexture) {
        queueEvent(() -> onBindInputSurfaceTexture(surfaceTexture));
    }

    /**
     * 释放所有资源
     */
    void release() {
        Log.d(TAG, "release: ");
        if (mImageReader != null) {
            mImageReader.release();
            mImageReader = null;
        }
        if (mDisplaySurface != null) {
            mDisplaySurface.makeCurrent();
        }
        if (mInputTexture != OpenGLUtils.GL_NOT_TEXTURE) {
            OpenGLUtils.deleteTexture(mInputTexture);
            mInputTexture = OpenGLUtils.GL_NOT_TEXTURE;
        }
        mRenderManager.release();
        if (mWeakSurfaceTexture != null) {
            mWeakSurfaceTexture.clear();
        }
        if (mDisplaySurface != null) {
            mDisplaySurface.release();
            mDisplaySurface = null;
        }
        if (mEglCore != null) {
            mEglCore.release();
            mEglCore = null;
        }
    }

    /**
     * 拍照
     */
    public void takePicture() {
        synchronized (mSync) {
            mCameraParam.isTakePicture = true;
        }
        requestRender();
    }

    /**
     * 渲染事件
     * @param runnable
     */
    public void queueEvent(@NonNull Runnable runnable) {
        getHandler().queueEvent(runnable);
    }

    /**
     * 请求渲染
     */
    public void requestRender() {
        getHandler().sendEmptyMessage(CameraRenderHandler.MSG_RENDER);
    }

    // ---------------------------------------- 渲染内部处理方法 -------------------------------------
    /**
     * 初始化渲染器
     */
    void initRender(Surface surface) {
        if (mWeakPresenter == null || mWeakPresenter.get() == null) {
            return;
        }
        Log.d(TAG, "initRender: ");
        mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
        mDisplaySurface = new WindowSurface(mEglCore, surface, false);
        mDisplaySurface.makeCurrent();

        GLES30.glDisable(GL10.GL_DITHER);
        GLES30.glClearColor(0,0, 0, 0);
        GLES30.glEnable(GL10.GL_CULL_FACE);
        GLES30.glEnable(GL10.GL_DEPTH_TEST);

        // 渲染器初始化
        mRenderManager.init(mWeakPresenter.get().getContext());

        if (mWeakPresenter.get() != null) {
            mWeakPresenter.get().onBindSharedContext(mEglCore.getEGLContext());
        }
    }

    /**
     * 初始化渲染器
     */
    void initRender(SurfaceTexture surfaceTexture) {
        Log.d(TAG, "initRender: ");
        mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
        mDisplaySurface = new WindowSurface(mEglCore, surfaceTexture);
        mDisplaySurface.makeCurrent();

        GLES30.glDisable(GL10.GL_DITHER);
        GLES30.glClearColor(0,0, 0, 0);
        GLES30.glEnable(GL10.GL_CULL_FACE);
        GLES30.glEnable(GL10.GL_DEPTH_TEST);

        // 渲染器初始化
        mRenderManager.init(mWeakPresenter.get().getContext());

        if (mWeakPresenter.get() != null) {
            mWeakPresenter.get().onBindSharedContext(mEglCore.getEGLContext());
        }
    }

    /**
     * 设置预览大小
     * @param width
     * @param height
     */
    void setDisplaySize(int width, int height) {
        mRenderManager.setDisplaySize(width, height);
    }

    /**
     * 渲染一帧数据
     */
    void onDrawFrame() {
        if (mDisplaySurface == null || mWeakSurfaceTexture == null || mWeakSurfaceTexture.get() == null) {
            return;
        }
        // 切换渲染上下文
        mDisplaySurface.makeCurrent();

        // 更新纹理
        long timeStamp = 0;
        synchronized (this) {
            final SurfaceTexture surfaceTexture = mWeakSurfaceTexture.get();
            updateSurfaceTexture(surfaceTexture);
            timeStamp = surfaceTexture.getTimestamp();
        }

        // 如果不存在外部输入纹理，则直接返回，不做处理
        if (mInputTexture == OpenGLUtils.GL_NOT_TEXTURE) {
            return;
        }
        // 绘制渲染
        mCurrentTexture = mRenderManager.drawFrame(mInputTexture, mMatrix);

        // 录制视频
        if (mWeakPresenter.get() != null) {
            mWeakPresenter.get().onRecordFrameAvailable(mCurrentTexture, timeStamp);
        }

        // 是否绘制人脸关键点
//        mRenderManager.drawFacePoint(mCurrentTexture);

        // 显示到屏幕
        mDisplaySurface.swapBuffers();

        // 执行拍照
        synchronized (mSync) {
            if (mCameraParam.isTakePicture) {
                if (mImageReader == null) {
                    mImageReader = new GLImageReader(mEglCore.getEGLContext(), bitmap -> {
                        if (mCameraParam.captureCallback != null) {
                            mCameraParam.captureCallback.onCapture(bitmap);
                        }
                    });
                    mImageReader.init(mRenderManager.getTextureWidth(), mRenderManager.getTextureHeight());
                }
                if (mImageReader != null) {
                    mImageReader.drawFrame(mCurrentTexture);
                }
                mCameraParam.isTakePicture = false;
            }
        }

        // 计算渲染帧率
        calculateFps();
    }

    /**
     * 更新输入纹理
     * @param surfaceTexture
     */
    private void updateSurfaceTexture(@NonNull SurfaceTexture surfaceTexture) {
        // 绑定到当前的输入纹理
        synchronized (this) {
            if (mNeedToAttach) {
                if (mInputTexture != OpenGLUtils.GL_NOT_TEXTURE) {
                    OpenGLUtils.deleteTexture(mInputTexture);
                }
                mInputTexture = OpenGLUtils.createOESTexture();
                try {
                    surfaceTexture.attachToGLContext(mInputTexture);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                mNeedToAttach = false;
            }
        }
        try {
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(mMatrix);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 绑定外部输入的SurfaceTexture
     * @param surfaceTexture
     */
    private void onBindInputSurfaceTexture(SurfaceTexture surfaceTexture) {
        synchronized (this) {
            if (mWeakSurfaceTexture == null || mWeakSurfaceTexture.get() != surfaceTexture) {
                mWeakSurfaceTexture = new WeakReference<>(surfaceTexture);
                mNeedToAttach = true;
            }
        }
    }

    /**
     * 计算fps
     */
    private void calculateFps() {
        if ((mCameraParam).fpsCallback != null) {
            mFrameRateMeter.drawFrameCount();
            (mCameraParam).fpsCallback.onFpsCallback(mFrameRateMeter.getFPS());
        }
    }

    // -------------------------------------- HandlerThread核心 ------------------------------------
    @Override
    public void run() {
        Looper.prepare();
        synchronized (this) {
            mLooper = Looper.myLooper();
            notifyAll();
        }
        Process.setThreadPriority(mPriority);
        Looper.loop();
        // 移除所有消息并销毁所有资源
        getHandler().handleQueueEvent();
        getHandler().removeCallbacksAndMessages(null);
        release();
        mThreadStarted = false;
        Log.d(TAG, "Thread has delete!");
    }

    /**
     * 获取当前的Looper
     * @return
     */
    private Looper getLooper() {
        if (!isAlive()) {
            return null;
        }
        synchronized (this) {
            while (isAlive() && mLooper == null) {
                try {
                    wait();
                } catch (InterruptedException e) {

                }
            }
        }
        return mLooper;
    }

    /**
     * 获取当前线程的Handler
     * @return
     */
    @NonNull
    public CameraRenderHandler getHandler() {
        if (mHandler == null) {
            mHandler = new CameraRenderHandler(getLooper(), this);
        }
        return mHandler;
    }

    /**
     * 退出渲染线程
     * @return
     */
    private boolean quit() {
        Looper looper = getLooper();
        if (looper != null) {
            looper.quitSafely();
            return true;
        }
        return false;
    }
}
