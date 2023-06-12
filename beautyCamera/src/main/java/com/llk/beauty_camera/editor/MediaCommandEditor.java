package com.llk.beauty_camera.editor;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.llk.beauty_camera.BuildConfig;

import java.nio.ByteBuffer;

public class MediaCommandEditor {

    private static final String TAG = MediaCommandEditor.class.getSimpleName();
    private static final boolean isDebug = BuildConfig.DEBUG;

    private Handler mHandler;

    public interface CommandProcessCallback {

        void onProcessResult(int result);
    }

    public MediaCommandEditor() {
        HandlerThread thread = new HandlerThread("media_command_editor");
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    @Override
    protected void finalize() throws Throwable {
        release();
        super.finalize();
    }

    /**
     * 释放资源
     */
    public void release() {
        if (mHandler == null) {
            return;
        }
        mHandler.getLooper().quitSafely();
        mHandler = null;
    }

    @SuppressLint("WrongConstant")
    public void execMuxer(@NonNull String outputPath,
                          @NonNull String videoPath,
                          @NonNull String audioPath,
                          @Nullable CommandProcessCallback callback){
        mHandler.post(() -> { //丢到子线程处理
            MediaMuxer muxer = null;
            MediaExtractor audioExtractor = null;
            MediaExtractor videoExtractor = null;
            long startTime = 0;
            try{
                if (isDebug) startTime = System.currentTimeMillis();

                muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                int audioTrackIndex = -1;
                int audioMuxerTrackIndex = -1;
                int audioMaxInputSize = 0;
                audioExtractor = new MediaExtractor();
                audioExtractor.setDataSource(audioPath);
                int audioTrackCount = audioExtractor.getTrackCount();
                for (int i=0; i<audioTrackCount; i++){
                    MediaFormat format = audioExtractor.getTrackFormat(i);
                    String mine = format.getString(MediaFormat.KEY_MIME);
                    if (mine.startsWith("audio/")){
                        audioTrackIndex = i;
                        audioMuxerTrackIndex = muxer.addTrack(format); //将音轨添加到MediaMuxer，并返回新的轨道
                        audioMaxInputSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                        break;
                    }
                }
                if (isDebug){
                    Log.d(TAG, "find audioMuxerTrackIndex -> " + audioMuxerTrackIndex);
                }

                int videoTrackIndex = -1;
                int videoMuxerTrackIndex = -1;
                int videoMaxInputSize = 0;
                int videoFrameRate = 0;
                videoExtractor = new MediaExtractor();
                videoExtractor.setDataSource(videoPath);
                int videoTrackCount = videoExtractor.getTrackCount();
                for (int i=0; i<videoTrackCount; i++){
                    MediaFormat format = videoExtractor.getTrackFormat(i);
                    String mine = format.getString(MediaFormat.KEY_MIME);
                    if (mine.startsWith("video/")){
                        videoTrackIndex = i;
                        videoMuxerTrackIndex = muxer.addTrack(format); //将视频轨添加到MediaMuxer，并返回新的轨道
                        videoMaxInputSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE); //得到能获取的有关视频的最大值
                        videoFrameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE); //获取视频的帧率
                        break;
                    }
                }
                if (isDebug){
                    Log.d(TAG, "find videoMuxerTrackIndex -> " + videoMuxerTrackIndex);
                }

                muxer.start(); //开始合成

                audioExtractor.selectTrack(audioTrackIndex);
                MediaCodec.BufferInfo audioMediaInfo = new MediaCodec.BufferInfo();
                ByteBuffer audioBuffer = ByteBuffer.allocate(audioMaxInputSize);
                while (true) {
                    int sampleSize = audioExtractor.readSampleData(audioBuffer, 0); //检索当前编码的样本并将其存储在字节缓冲区中
                    if (sampleSize <= 0) {
                        audioExtractor.unselectTrack(audioTrackIndex);
                        break;
                    }

                    //设置样本编码信息
                    audioMediaInfo.offset = 0;
                    audioMediaInfo.size = sampleSize;
                    audioMediaInfo.flags = audioExtractor.getSampleFlags();
                    audioMediaInfo.presentationTimeUs = audioExtractor.getSampleTime();

                    muxer.writeSampleData(audioMuxerTrackIndex, audioBuffer, audioMediaInfo);

                    audioExtractor.advance();
                }

                videoExtractor.selectTrack(videoTrackIndex); //将提供视频图像的视频选择到视频轨上
                MediaCodec.BufferInfo videoMediaInfo = new MediaCodec.BufferInfo();
                ByteBuffer videoBuffer = ByteBuffer.allocate(videoMaxInputSize);
                while (true) {
                    int sampleSize = videoExtractor.readSampleData(videoBuffer, 0); //检索当前编码的样本并将其存储在字节缓冲区中
                    if (sampleSize <= 0) {
                        videoExtractor.unselectTrack(videoTrackIndex);
                        break;
                    }

                    //设置样本编码信息
                    videoMediaInfo.offset = 0;
                    videoMediaInfo.size = sampleSize;
                    videoMediaInfo.flags = videoExtractor.getSampleFlags();
                    videoMediaInfo.presentationTimeUs += 1000 * 1000 / videoFrameRate;

                    muxer.writeSampleData(videoMuxerTrackIndex, videoBuffer, videoMediaInfo);

                    videoExtractor.advance();
                }
                if (callback != null){
                    callback.onProcessResult(0);
                }
            }catch (Exception e){
                Log.e(TAG, "execMuxer fail, err=" + e.getLocalizedMessage());
                e.printStackTrace();
                if (callback != null){
                    callback.onProcessResult(-1);
                }
            }finally {
                if (muxer != null){
                    muxer.stop();
                    muxer.release();
                }

                if (audioExtractor != null) audioExtractor.release();
                if (videoExtractor != null) videoExtractor.release();

                if (isDebug){
                    long duration = System.currentTimeMillis() - startTime;
                    Log.e(TAG, "execMuxer finish, duration=" + duration);
                }
            }
        });
    }
}
