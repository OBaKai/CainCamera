package com.llk.beauty_camera.filter.bean.beauty;

import android.content.Context;

import com.llk.beauty_camera.filter.bean.GLImageFilter;
import com.llk.beauty_camera.filter.bean.GLImageGaussianBlurFilter;
import com.llk.beauty_camera.filter.utils.OpenGLUtils;

import java.nio.FloatBuffer;

/**
 * 实时美颜，这里用的是高反差保留磨皮法
 */
public class GLImageBeautyFilter extends GLImageFilter implements IBeautify {

    // 美肤滤镜
    private GLImageBeautyComplexionFilter mComplexionFilter;
    // 高斯模糊
    private GLImageBeautyBlurFilter mBeautyBlurFilter;
    // 高通滤波
    private GLImageBeautyHighPassFilter mHighPassFilter;
    // 高通滤波做高斯模糊处理，保留边沿细节
    private GLImageGaussianBlurFilter mHighPassBlurFilter;
    // 磨皮程度调节滤镜
    private GLImageBeautyAdjustFilter mBeautyAdjustFilter;

    // 缩放
    private float mBlurScale = 0.5f;

    public GLImageBeautyFilter(Context context) {
        this(context, null, null);
    }

    public GLImageBeautyFilter(Context context, String vertexShader, String fragmentShader) {
        super(context, vertexShader, fragmentShader);
        initFilters();
    }

    private void initFilters() {
        mComplexionFilter = new GLImageBeautyComplexionFilter(mContext);
        mBeautyBlurFilter = new GLImageBeautyBlurFilter(mContext);
        mHighPassFilter = new GLImageBeautyHighPassFilter(mContext);
        mHighPassBlurFilter = new GLImageGaussianBlurFilter(mContext);
        mBeautyAdjustFilter = new GLImageBeautyAdjustFilter(mContext);
    }

    @Override
    public void onInputSizeChanged(int width, int height) {
        super.onInputSizeChanged(width, height);
        if (mComplexionFilter != null) {
            mComplexionFilter.onInputSizeChanged(width, height);
        }
        if (mBeautyBlurFilter != null) {
            mBeautyBlurFilter.onInputSizeChanged((int) (width * mBlurScale), (int) (height * mBlurScale));
        }
        if (mHighPassFilter != null) {
            mHighPassFilter.onInputSizeChanged((int) (width * mBlurScale), (int) (height * mBlurScale));
        }
        if (mHighPassBlurFilter != null) {
            mHighPassBlurFilter.onInputSizeChanged((int) (width * mBlurScale), (int) (height * mBlurScale));
        }
        if (mBeautyAdjustFilter != null) {
            mBeautyAdjustFilter.onInputSizeChanged(width, height);
        }
    }

    @Override
    public void onDisplaySizeChanged(int width, int height) {
        super.onDisplaySizeChanged(width, height);
        if (mComplexionFilter != null) {
            mComplexionFilter.onDisplaySizeChanged(width, height);
        }
        if (mBeautyBlurFilter != null) {
            mBeautyBlurFilter.onDisplaySizeChanged(width, height);
        }
        if (mHighPassFilter != null) {
            mHighPassFilter.onDisplaySizeChanged(width, height);
        }
        if (mHighPassBlurFilter != null) {
            mHighPassBlurFilter.onDisplaySizeChanged(width, height);
        }
        if (mBeautyAdjustFilter != null) {
            mBeautyAdjustFilter.onDisplaySizeChanged(width, height);
        }
    }

    @Override
    public boolean drawFrame(int textureId, FloatBuffer vertexBuffer, FloatBuffer textureBuffer) {
        if (textureId == OpenGLUtils.GL_NOT_TEXTURE) {
            return false;
        }
        int currentTexture = textureId;
        int sourceTexture = mComplexionFilter.drawFrameBuffer(currentTexture, vertexBuffer, textureBuffer);
        currentTexture = sourceTexture;

        int blurTexture = currentTexture;
        int highPassBlurTexture = currentTexture;
        // 1、输入纹理做高斯模糊处理
        if (mBeautyBlurFilter != null) {
            blurTexture = mBeautyBlurFilter.drawFrameBuffer(currentTexture, vertexBuffer, textureBuffer);
            currentTexture = blurTexture;
        }
        // 2、计算高通滤波，做高反差保留处理
        if (mHighPassFilter != null) {
            mHighPassFilter.setBlurTexture(currentTexture);
            currentTexture = mHighPassFilter.drawFrameBuffer(sourceTexture, vertexBuffer, textureBuffer);
        }
        // 3、高通滤波纹理做高斯模糊处理，去掉边沿细节
        if (mHighPassBlurFilter != null) {
            highPassBlurTexture = mHighPassBlurFilter.drawFrameBuffer(currentTexture, vertexBuffer, textureBuffer);
        }
        // 调节
        if (mBeautyAdjustFilter != null) {
            mBeautyAdjustFilter.setBlurTexture(blurTexture, highPassBlurTexture);
            currentTexture = mBeautyAdjustFilter.drawFrameBuffer(sourceTexture, vertexBuffer, textureBuffer);
        }
        return false;
    }

    @Override
    public int drawFrameBuffer(int textureId, FloatBuffer vertexBuffer, FloatBuffer textureBuffer) {
        if (textureId == OpenGLUtils.GL_NOT_TEXTURE) {
            return textureId;
        }
        int currentTexture = textureId;
        int sourceTexture = mComplexionFilter.drawFrameBuffer(currentTexture, vertexBuffer, textureBuffer);
        currentTexture = sourceTexture;

        int blurTexture = currentTexture;
        int highPassBlurTexture = currentTexture;
        // 高斯模糊
        if (mBeautyBlurFilter != null) {
            blurTexture = mBeautyBlurFilter.drawFrameBuffer(currentTexture, vertexBuffer, textureBuffer);
            currentTexture = blurTexture;
        }
        // 高通滤波，做高反差保留
        if (mHighPassFilter != null) {
            mHighPassFilter.setBlurTexture(currentTexture);
            currentTexture = mHighPassFilter.drawFrameBuffer(sourceTexture, vertexBuffer, textureBuffer);
        }
        // 对高反差保留的结果进行高斯模糊，过滤边沿数值
        if (mHighPassBlurFilter != null) {
            highPassBlurTexture = mHighPassBlurFilter.drawFrameBuffer(currentTexture, vertexBuffer, textureBuffer);
            currentTexture = highPassBlurTexture;
        }
        // 混合处理
        if (mBeautyAdjustFilter != null) {
            currentTexture = sourceTexture;
            mBeautyAdjustFilter.setBlurTexture(blurTexture, highPassBlurTexture);
            currentTexture = mBeautyAdjustFilter.drawFrameBuffer(currentTexture, vertexBuffer, textureBuffer);
        }
        return currentTexture;
    }

    @Override
    public void initFrameBuffer(int width, int height) {
        super.initFrameBuffer(width, height);
        if (mComplexionFilter != null) {
            mComplexionFilter.initFrameBuffer(width, height);
        }
        if (mBeautyBlurFilter != null) {
            mBeautyBlurFilter.initFrameBuffer((int) (width * mBlurScale), (int) (height * mBlurScale));
        }
        if (mHighPassFilter != null) {
            mHighPassFilter.initFrameBuffer((int) (width * mBlurScale), (int) (height * mBlurScale));
        }
        if (mHighPassBlurFilter != null) {
            mHighPassBlurFilter.initFrameBuffer((int) (width * mBlurScale), (int) (height * mBlurScale));
        }
        if (mBeautyAdjustFilter != null) {
            mBeautyAdjustFilter.initFrameBuffer(width, height);
        }
    }

    @Override
    public void destroyFrameBuffer() {
        super.destroyFrameBuffer();
        if (mComplexionFilter != null) {
            mComplexionFilter.destroyFrameBuffer();
        }
        if (mBeautyBlurFilter != null) {
            mBeautyBlurFilter.destroyFrameBuffer();
        }
        if (mHighPassFilter != null) {
            mHighPassFilter.destroyFrameBuffer();
        }
        if (mHighPassBlurFilter != null) {
            mHighPassBlurFilter.destroyFrameBuffer();
        }
        if (mBeautyAdjustFilter != null) {
            mBeautyAdjustFilter.destroyFrameBuffer();
        }
    }

    @Override
    public void release() {
        super.release();
        if (mComplexionFilter != null) {
            mComplexionFilter.release();
            mComplexionFilter = null;
        }
        if (mBeautyBlurFilter != null) {
            mBeautyBlurFilter.release();
            mBeautyBlurFilter = null;
        }
        if (mHighPassFilter != null) {
            mHighPassFilter.release();
            mHighPassFilter = null;
        }
        if (mHighPassBlurFilter != null) {
            mHighPassBlurFilter.release();
            mHighPassBlurFilter = null;
        }
        if (mBeautyAdjustFilter != null) {
            mBeautyAdjustFilter.release();
            mBeautyAdjustFilter = null;
        }
    }

    @Override
    public void onBeauty(BeautyParam beauty) {
        if (mComplexionFilter != null) {
            mComplexionFilter.setComplexionLevel(beauty.complexionIntensity);
        }
        if (mBeautyAdjustFilter != null) {
            mBeautyAdjustFilter.setSkinBeautyIntensity(beauty.beautyIntensity);
        }
    }
}
