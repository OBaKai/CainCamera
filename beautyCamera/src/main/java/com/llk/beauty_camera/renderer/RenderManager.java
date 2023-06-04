package com.llk.beauty_camera.renderer;

import android.content.Context;
import android.util.SparseArray;

import com.cgfay.filter.glfilter.base.GLImageFilter;
import com.cgfay.filter.glfilter.base.GLImageOESInputFilter;
import com.cgfay.filter.glfilter.beauty.GLImageBeautyFilter;
import com.cgfay.filter.glfilter.beauty.bean.IBeautify;
import com.cgfay.filter.glfilter.utils.OpenGLUtils;
import com.cgfay.filter.glfilter.utils.TextureRotationUtils;
import com.llk.beauty_camera.camera.CameraParam;

import java.nio.FloatBuffer;

/**
 * 渲染管理器
 */
public final class RenderManager {

    public RenderManager() {
        mCameraParam = CameraParam.getInstance();
    }

    // 滤镜列表
    private SparseArray<GLImageFilter> mFilterArrays = new SparseArray<GLImageFilter>();

    // 坐标缓冲
    private ScaleType mScaleType = ScaleType.CENTER_CROP;
    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTextureBuffer;
    // 用于显示裁剪的纹理顶点缓冲
    private FloatBuffer mDisplayVertexBuffer;
    private FloatBuffer mDisplayTextureBuffer;

    // 视图宽高
    private int mViewWidth, mViewHeight;
    // 输入图像大小
    private int mTextureWidth, mTextureHeight;

    // 相机参数
    private CameraParam mCameraParam;
    // 上下文
    private Context mContext;

    /**
     * 初始化
     */
    public void init(Context context) {
        initBuffers();
        initFilters(context);
        mContext = context;
    }

    /**
     * 释放资源
     */
    public void release() {
        releaseBuffers();
        releaseFilters();
        mContext = null;
    }

    /**
     * 释放滤镜
     */
    private void releaseFilters() {
        for (int i = 0; i < RenderIndex.NumberIndex; i++) {
            if (mFilterArrays.get(i) != null) {
                mFilterArrays.get(i).release();
            }
        }
        mFilterArrays.clear();
    }

    /**
     * 释放缓冲区
     */
    private void releaseBuffers() {
        if (mVertexBuffer != null) {
            mVertexBuffer.clear();
            mVertexBuffer = null;
        }
        if (mTextureBuffer != null) {
            mTextureBuffer.clear();
            mTextureBuffer = null;
        }
        if (mDisplayVertexBuffer != null) {
            mDisplayVertexBuffer.clear();
            mDisplayVertexBuffer = null;
        }
        if (mDisplayTextureBuffer != null) {
            mDisplayTextureBuffer.clear();
            mDisplayTextureBuffer = null;
        }
    }

    /**
     * 初始化缓冲区
     */
    private void initBuffers() {
        releaseBuffers();
        mDisplayVertexBuffer = OpenGLUtils.createFloatBuffer(TextureRotationUtils.CubeVertices);
        mDisplayTextureBuffer = OpenGLUtils.createFloatBuffer(TextureRotationUtils.TextureVertices);
        mVertexBuffer = OpenGLUtils.createFloatBuffer(TextureRotationUtils.CubeVertices);
        mTextureBuffer = OpenGLUtils.createFloatBuffer(TextureRotationUtils.TextureVertices);
    }

    /**
     * 初始化滤镜
     * @param context
     */
    private void initFilters(Context context) {
        releaseFilters();
        // 相机输入滤镜
        mFilterArrays.put(RenderIndex.CameraIndex, new GLImageOESInputFilter(context));
        // 美颜滤镜
        mFilterArrays.put(RenderIndex.BeautyIndex, new GLImageBeautyFilter(context));
        // 显示输出
        mFilterArrays.put(RenderIndex.DisplayIndex, new GLImageFilter(context));
    }

    /**
     * 绘制纹理
     * @param inputTexture
     * @param mMatrix
     * @return
     */
    public int drawFrame(int inputTexture, float[] mMatrix) {
        int currentTexture = inputTexture;
        if (mFilterArrays.get(RenderIndex.CameraIndex) == null
                || mFilterArrays.get(RenderIndex.DisplayIndex) == null) {
            return currentTexture;
        }
        if (mFilterArrays.get(RenderIndex.CameraIndex) instanceof GLImageOESInputFilter) {
            ((GLImageOESInputFilter)mFilterArrays.get(RenderIndex.CameraIndex)).setTextureTransformMatrix(mMatrix);
        }
        currentTexture = mFilterArrays.get(RenderIndex.CameraIndex)
                .drawFrameBuffer(currentTexture, mVertexBuffer, mTextureBuffer);
        if (!mCameraParam.isCloseBeautyFilter) {
            // 美颜滤镜
            if (mFilterArrays.get(RenderIndex.BeautyIndex) != null) {
                if (mFilterArrays.get(RenderIndex.BeautyIndex) instanceof IBeautify
                        && mCameraParam.beauty != null) {
                    ((IBeautify) mFilterArrays.get(RenderIndex.BeautyIndex)).onBeauty(mCameraParam.beauty);
                }
                currentTexture = mFilterArrays.get(RenderIndex.BeautyIndex).drawFrameBuffer(currentTexture, mVertexBuffer, mTextureBuffer);
            }
        }

        // 显示输出，需要调整视口大小
        mFilterArrays.get(RenderIndex.DisplayIndex).drawFrame(currentTexture, mDisplayVertexBuffer, mDisplayTextureBuffer);

        return currentTexture;
    }

    /**
     * 设置输入纹理大小
     * @param width
     * @param height
     */
    public void setTextureSize(int width, int height) {
        mTextureWidth = width;
        mTextureHeight = height;
        if (mViewWidth != 0 && mViewHeight != 0) {
            adjustCoordinateSize();
            onFilterChanged();
        }
    }

    /**
     * 获取纹理宽度
     * @return
     */
    public int getTextureWidth() {
        return mTextureWidth;
    }

    /**
     * 获取纹理高度
     * @return
     */
    public int getTextureHeight() {
        return mTextureHeight;
    }

    /**
     * 设置纹理显示大小
     * @param width
     * @param height
     */
    public void setDisplaySize(int width, int height) {
        mViewWidth = width;
        mViewHeight = height;
        if (mTextureWidth != 0 && mTextureHeight != 0) {
            adjustCoordinateSize();
            onFilterChanged();
        }
    }

    /**
     * 调整滤镜
     */
    private void onFilterChanged() {
        for (int i = 0; i < RenderIndex.NumberIndex; i++) {
            if (mFilterArrays.get(i) != null) {
                mFilterArrays.get(i).onInputSizeChanged(mTextureWidth, mTextureHeight);
                // 到显示之前都需要创建FBO，这里限定是防止创建多余的FBO，节省GPU资源
                if (i < RenderIndex.DisplayIndex) {
                    mFilterArrays.get(i).initFrameBuffer(mTextureWidth, mTextureHeight);
                }
                mFilterArrays.get(i).onDisplaySizeChanged(mViewWidth, mViewHeight);
            }
        }
    }

    /**
     * 调整由于surface的大小与SurfaceView大小不一致带来的显示问题
     */
    private void adjustCoordinateSize() {
        float[] textureCoord = null;
        float[] vertexCoord = null;
        float[] textureVertices = TextureRotationUtils.TextureVertices;
        float[] vertexVertices = TextureRotationUtils.CubeVertices;
        float ratioMax = Math.max((float) mViewWidth / mTextureWidth,
                (float) mViewHeight / mTextureHeight);
        // 新的宽高
        int imageWidth = Math.round(mTextureWidth * ratioMax);
        int imageHeight = Math.round(mTextureHeight * ratioMax);
        // 获取视图跟texture的宽高比
        float ratioWidth = (float) imageWidth / (float) mViewWidth;
        float ratioHeight = (float) imageHeight / (float) mViewHeight;
        if (mScaleType == ScaleType.CENTER_INSIDE) {
            vertexCoord = new float[] {
                    vertexVertices[0] / ratioHeight, vertexVertices[1] / ratioWidth,
                    vertexVertices[2] / ratioHeight, vertexVertices[3] / ratioWidth,
                    vertexVertices[4] / ratioHeight, vertexVertices[5] / ratioWidth,
                    vertexVertices[6] / ratioHeight, vertexVertices[7] / ratioWidth,
            };
        } else if (mScaleType == ScaleType.CENTER_CROP) {
            float distHorizontal = (1 - 1 / ratioWidth) / 2;
            float distVertical = (1 - 1 / ratioHeight) / 2;
            textureCoord = new float[] {
                    addDistance(textureVertices[0], distHorizontal), addDistance(textureVertices[1], distVertical),
                    addDistance(textureVertices[2], distHorizontal), addDistance(textureVertices[3], distVertical),
                    addDistance(textureVertices[4], distHorizontal), addDistance(textureVertices[5], distVertical),
                    addDistance(textureVertices[6], distHorizontal), addDistance(textureVertices[7], distVertical),
            };
        }
        if (vertexCoord == null) {
            vertexCoord = vertexVertices;
        }
        if (textureCoord == null) {
            textureCoord = textureVertices;
        }
        // 更新VertexBuffer 和 TextureBuffer
        if (mDisplayVertexBuffer == null || mDisplayTextureBuffer == null) {
            initBuffers();
        }
        mDisplayVertexBuffer.clear();
        mDisplayVertexBuffer.put(vertexCoord).position(0);
        mDisplayTextureBuffer.clear();
        mDisplayTextureBuffer.put(textureCoord).position(0);
    }

    /**
     * 计算距离
     * @param coordinate
     * @param distance
     * @return
     */
    private float addDistance(float coordinate, float distance) {
        return coordinate == 0.0f ? distance : 1 - distance;
    }
}
