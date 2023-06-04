package com.llk.beauty_camera.filter.bean.beauty;

/**
 * 美颜参数
 */
public class BeautyParam {
    // 磨皮程度 0.0 ~ 1.0f
    public float beautyIntensity;
    // 美肤程度 0.0 ~ 0.5f
    public float complexionIntensity;

    public BeautyParam() {
        reset();
    }

    /**
     * 重置为默认参数
     */
    public void reset() {
        beautyIntensity = 0.5f;
        complexionIntensity = 0.5f;
    }
}
