package com.llk.beauty_camera.filter.bean.beauty;

import android.content.Context;
import android.opengl.GLES30;

import com.llk.beauty_camera.filter.bean.GLImageFilter;
import com.llk.beauty_camera.filter.utils.OpenGLUtils;


/**
 * 高通滤波器
 */
class GLImageBeautyHighPassFilter extends GLImageFilter {

    private int mBlurTextureHandle;
    private int mBlurTexture;

    public GLImageBeautyHighPassFilter(Context context) {
        this(context, VERTEX_SHADER, OpenGLUtils.getShaderFromAssets(context,
                "shader/beauty/fragment_beauty_highpass.glsl"));
    }

    public GLImageBeautyHighPassFilter(Context context, String vertexShader, String fragmentShader) {
        super(context, vertexShader, fragmentShader);
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        mBlurTextureHandle = GLES30.glGetUniformLocation(mProgramHandle, "blurTexture");
    }

    @Override
    public void onDrawFrameBegin() {
        super.onDrawFrameBegin();
        OpenGLUtils.bindTexture(mBlurTextureHandle, mBlurTexture, 1);
    }

    /**
     * 设置经过高斯模糊的滤镜
     * @param texture
     */
    public void setBlurTexture(int texture) {
        mBlurTexture = texture;
    }

}
