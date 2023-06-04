package com.llk.beauty_camera.recorder;

/**
 * 媒体信息
 * @author CainHuang
 * @date 2019/7/7
 */
public class MediaInfo {

    private String filePath;
    private long duration;

    public MediaInfo(String path, long duration) {
        this.filePath = path;
        this.duration = duration;
    }

    public long getDuration() {
        return duration;
    }

    public String getFilePath() {
        return filePath;
    }
}
