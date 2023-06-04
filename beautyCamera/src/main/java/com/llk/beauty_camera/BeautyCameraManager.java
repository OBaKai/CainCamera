package com.llk.beauty_camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGLContext;
import android.util.Log;

import androidx.fragment.app.FragmentActivity;

import com.cgfay.filter.glfilter.color.bean.DynamicColor;
import com.cgfay.filter.glfilter.makeup.bean.DynamicMakeup;
import com.cgfay.filter.glfilter.resource.FilterHelper;
import com.cgfay.filter.glfilter.resource.ResourceJsonCodec;
import com.llk.beauty_camera.camera.CameraApi;
import com.llk.beauty_camera.camera.CameraController;
import com.llk.beauty_camera.camera.CameraParam;
import com.llk.beauty_camera.camera.CameraXController;
import com.llk.beauty_camera.camera.ICameraController;
import com.llk.beauty_camera.editor.MediaCommandEditor;
import com.llk.beauty_camera.recorder.BCMediaRecorder;
import com.llk.beauty_camera.recorder.MediaInfo;
import com.llk.beauty_camera.recorder.MediaType;
import com.llk.beauty_camera.recorder.OnRecordStateListener;
import com.llk.beauty_camera.recorder.RecordInfo;
import com.llk.beauty_camera.recorder.SpeedMode;
import com.llk.beauty_camera.recorder.audio.AudioParams;
import com.llk.beauty_camera.recorder.video.VideoParams;
import com.llk.beauty_camera.renderer.CameraRenderer;
import com.llk.beauty_camera.utils.BrightnessUtils;
import com.llk.beauty_camera.utils.FileTools;

import java.io.File;

/**
 * author: llk
 * date  : 2023/6/4
 * detail:
 */
public class BeautyCameraManager extends BaseBeautyCameraComponent {

    private static final String TAG = BeautyCameraManager.class.getSimpleName();
    private static final boolean DEBUG = true;

    private static final String OUTPUT_MEDIA_FILE_PREFIX = "bc_media_";
    private static final String VIDEO_FILE_SUFFIX = ".mp4";
    private static final String AUDIO_FILE_SUFFIX = ".aac";

    // 预览参数
    private final CameraParam mCameraParam;

    // 音视频参数
    private final VideoParams mVideoParams;
    private final AudioParams mAudioParams;

    // 视频录制器
    private BCMediaRecorder mMediaRecorder;
    // 录制音频信息
    private RecordInfo mAudioInfo;
    // 录制视频信息
    private RecordInfo mVideoInfo;
    // 命令行编辑器
    private MediaCommandEditor mCommandEditor;
    // 相机接口
    private final ICameraController mCameraController;

    // 渲染器
    private final CameraRenderer mCameraRenderer;

    private boolean mOperateStarted = false;

    private CameraStateCallback cameraStateCallback;
    private Context mContext;

//    private final OnCaptureListener mOnCaptureListener = bitmap -> { //截帧回调
//
//    };

//    private final OnFpsListener mOnFpsListener = fps -> { //fps帧率值回调
//
//    };

    //录制与合成，相关回调
    private final OnRecordStateListener mOnRecordStateListener = new OnRecordStateListener() {
        @Override
        public void onRecordStart() { //开始录制
            if (cameraStateCallback != null)
                cameraStateCallback.onCameraRecordStart();
        }

        @Override
        public void onRecording(long duration) { //录制中
            if (cameraStateCallback != null)
                cameraStateCallback.onCameraRecording(duration);
        }

        /**
         * 录制完成（由于音视频是分开录制的，会存在多次回调。音频、视频信息分开回调的）
         */
        @Override
        public void onRecordFinish(RecordInfo info) {
            if (info.getType() == MediaType.AUDIO) {
                mAudioInfo = info;
            } else if (info.getType() == MediaType.VIDEO) {
                mVideoInfo = info;
                //mCurrentProgress = info.getDuration() * 1.0f / mVideoParams.getMaxDuration();
            }

            if (mMediaRecorder == null) return;
            if (mMediaRecorder.enableAudio() && (mAudioInfo == null || mVideoInfo == null)) return;

            if (mMediaRecorder.enableAudio()) { //音频与视频融合
                final String currentFile = FileTools.makeFilePath(mContext, OUTPUT_MEDIA_FILE_PREFIX + System.currentTimeMillis() + VIDEO_FILE_SUFFIX);
                FileTools.createFile(currentFile);
                mCommandEditor.execCommand(MediaCommandEditor.mergeAudioVideo(mVideoInfo.getFileName(), mAudioInfo.getFileName(), currentFile), (result) -> {
                            if (result == 0) { //合成成功
                                MediaInfo mediaInfo = new MediaInfo(currentFile, mVideoInfo.getDuration());
                                if (cameraStateCallback != null){
                                    cameraStateCallback.onCamereRecordFinish(mediaInfo);
                                }
                                // 删除旧的文件
                                FileTools.deleteFile(mAudioInfo.getFileName());
                                FileTools.deleteFile(mVideoInfo.getFileName());
                                mAudioInfo = null;
                                mVideoInfo = null;
                            }else {
                                if (cameraStateCallback != null){
                                    cameraStateCallback.onCamereRecordError(result);
                                }
                                if (DEBUG){
                                    Log.e(TAG, "mergeAudioVideo fail, result=" + result);
                                }
                            }
                        });
            } else {
                if (mVideoInfo != null) {
                    final String currentFile = FileTools.makeFilePath(mContext, OUTPUT_MEDIA_FILE_PREFIX + System.currentTimeMillis() + VIDEO_FILE_SUFFIX);
                    FileTools.moveFile(mVideoInfo.getFileName(), currentFile);

                    MediaInfo mediaInfo = new MediaInfo(currentFile, mVideoInfo.getDuration());
                    if (cameraStateCallback != null){
                        cameraStateCallback.onCamereRecordFinish(mediaInfo);
                    }

                    mAudioInfo = null;
                    mVideoInfo = null;
                }else {
                    if (cameraStateCallback != null){
                        cameraStateCallback.onCamereRecordError(-999);
                    }
                }
            }

            if (mMediaRecorder != null) {
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
        }
    };

    public BeautyCameraManager(FragmentActivity context){
        mContext = context;

        mCameraRenderer = new CameraRenderer(this);

        mCameraParam = CameraParam.getInstance();
        mCommandEditor = new MediaCommandEditor();

        mVideoParams = new VideoParams();
        mAudioParams = new AudioParams();

        mVideoParams.setVideoPath(FileTools.makeFilePath(context, "temp"+VIDEO_FILE_SUFFIX));
        mAudioParams.setAudioPath(FileTools.makeFilePath(context, "temp"+AUDIO_FILE_SUFFIX));

        mCameraRenderer.initRenderer();

        if (CameraApi.hasCamera2(context)) {
            mCameraController = new CameraXController(context);
        } else {
            mCameraController = new CameraController(context);
        }
        mCameraController.setPreviewCallback(data -> { //相机预览数据回调
            if (DEBUG){
                Log.d(TAG, "onPreviewFrame: width - " + mCameraController.getPreviewWidth()
                        + ", height - " + mCameraController.getPreviewHeight());
            }
            mCameraRenderer.requestRender();
        });

        mCameraController.setOnSurfaceTextureListener(surfaceTexture -> { //Camera 输出SurfaceTexture准备完成回调
            if (DEBUG){
                Log.d(TAG, "onCameraOpened: " +
                        "orientation - " + mCameraController.getOrientation()
                        + "width - " + mCameraController.getPreviewWidth()
                        + ", height - " + mCameraController.getPreviewHeight());
            }
            mCameraRenderer.bindInputSurfaceTexture(surfaceTexture);
        });

        //mCameraController.setOnFrameAvailableListener(mOnFrameAvailableListener); //SurfaceTexture帧可用回调

        if (BrightnessUtils.getSystemBrightnessMode(context) == 1) {
            mCameraParam.brightness = -1;
        } else {
            mCameraParam.brightness = BrightnessUtils.getSystemBrightness(context);
        }
    }

    public void setCameraStateCallback(CameraStateCallback callback){
        cameraStateCallback = callback;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        openCamera();
//        mCameraParam.captureCallback = mOnCaptureListener;
//        mCameraParam.fpsCallback = mOnFpsListener;
    }

    @Override
    public void onPause() {
        super.onPause();
        mCameraRenderer.onPause();
        closeCamera();
//        mCameraParam.captureCallback = null;
//        mCameraParam.fpsCallback = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mMediaRecorder != null) {
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
        if (mCommandEditor != null) {
            mCommandEditor.release();
            mCommandEditor = null;
        }

        mCameraRenderer.destroyRenderer();

        mContext = null;
    }

    /**
     * 打开相机
     */
    private void openCamera() {
        mCameraController.openCamera();
        calculateImageSize();
    }

    /**
     * 关闭相机
     */
    private void closeCamera() {
        mCameraController.closeCamera();
    }

    private void calculateImageSize() {
        int width;
        int height;
        if (mCameraController.getOrientation() == 90 || mCameraController.getOrientation() == 270) {
            width = mCameraController.getPreviewHeight();
            height = mCameraController.getPreviewWidth();
        } else {
            width = mCameraController.getPreviewWidth();
            height = mCameraController.getPreviewHeight();
        }
        mVideoParams.setVideoSize(width, height);
        mCameraRenderer.setTextureSize(width, height);
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public void onBindSharedContext(EGLContext context) {
        mVideoParams.setEglContext(context);
    }

    @Override
    public void onRecordFrameAvailable(int texture, long timestamp) {
        if (mOperateStarted && mMediaRecorder != null && mMediaRecorder.isRecording()) {
            mMediaRecorder.frameAvailable(texture, timestamp);
        }
    }

    @Override
    public void onSurfaceCreated(SurfaceTexture surfaceTexture) {
        mCameraRenderer.onSurfaceCreated(surfaceTexture);
    }

    @Override
    public void onSurfaceChanged(int width, int height) {
        mCameraRenderer.onSurfaceChanged(width, height);
    }

    @Override
    public void onSurfaceDestroyed() {
        mCameraRenderer.onSurfaceDestroyed();
    }

    @Override
    public void changeDynamicFilter(DynamicColor color) {
        mCameraRenderer.changeFilter(color);
    }

    @Override
    public void changeDynamicMakeup(DynamicMakeup makeup) {
        mCameraRenderer.changeMakeup(makeup);
    }

    @Override
    public void changeDynamicFilter(int filterIndex) {
        String folderPath = FilterHelper.getFilterDirectory(mContext) + File.separator +
                FilterHelper.getFilterList().get(filterIndex).unzipFolder;
        DynamicColor color = null;
        if (!FilterHelper.getFilterList().get(filterIndex).unzipFolder.equalsIgnoreCase("none")) {
            try {
                color = ResourceJsonCodec.decodeFilterData(folderPath);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        mCameraRenderer.changeFilter(color);
    }

    @Override
    public int previewFilter() {
        return 0;
    }

    @Override
    public int nextFilter() {
        return 0;
    }

    @Override
    public int getFilterIndex() {
        return 0;
    }

    @Override
    public void closeBeautyFilter(boolean isClose) {
        mCameraParam.isCloseBeautyFilter = isClose;
    }

    @Override
    public void takePicture() {
        mCameraRenderer.takePicture();
    }

    @Override
    public void switchCamera() {
        mCameraController.switchCamera();
    }

    @Override
    public void startRecord() {
        if (mOperateStarted) {
            return;
        }
        if (mMediaRecorder == null) {
            mMediaRecorder = new BCMediaRecorder(mOnRecordStateListener);
        }
        mMediaRecorder.startRecord(mVideoParams, mAudioParams);
        mOperateStarted = true;
    }

    @Override
    public void stopRecord() {
        if (!mOperateStarted) {
            return;
        }
        mOperateStarted = false;
        if (mMediaRecorder != null) {
            mMediaRecorder.stopRecord();
        }
    }

    @Override
    public void cancelRecord() {
        stopRecord();
    }

    @Override
    public boolean isRecording() {
        return (mOperateStarted && mMediaRecorder != null && mMediaRecorder.isRecording());
    }

    @Override
    public void setRecordAudioEnable(boolean enable) {
        if (mMediaRecorder != null) {
            mMediaRecorder.setEnableAudio(enable);
        }
    }

    @Override
    public void setRecordSeconds(int seconds) {
        // 最大时长
        long mMaxDuration = (long) seconds * BCMediaRecorder.SECOND_IN_US;
        mVideoParams.setMaxDuration(mMaxDuration);
        mAudioParams.setMaxDuration(mMaxDuration);
    }

    @Override
    public void setSpeedMode(SpeedMode mode) {
        mVideoParams.setSpeedMode(mode);
        mAudioParams.setSpeedMode(mode);
    }

    @Override
    public void changeFlashLight(boolean on) {
        if (mCameraController != null) {
            mCameraController.setFlashLight(on);
        }
    }

    @Override
    public void enableEdgeBlurFilter(boolean enable) {
        mCameraRenderer.changeEdgeBlur(enable);
    }

    public interface CameraStateCallback{
        void onCameraRecordStart();
        void onCameraRecording(long duration);
        void onCamereRecordFinish(MediaInfo mediaInfo);

        void onCamereRecordError(int error);
    }
}
