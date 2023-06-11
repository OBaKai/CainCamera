package com.llk.beauty_camera.recorder.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.llk.beauty_camera.recorder.MediaType;
import com.llk.beauty_camera.recorder.OnRecordListener;
import com.llk.beauty_camera.recorder.RecordInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class LlkAudioRecorder implements Runnable {

    private static final String TAG = LlkAudioRecorder.class.getSimpleName();

    private static final boolean VERBOSE = true;

    private static final int BUFFER_SIZE = 8192;

    private int mBufferSize = BUFFER_SIZE;

    // 录音器
    private AudioRecord mAudioRecord;
    // 音频转码器
    private AudioTranscoder mAudioTranscoder;
    // 音频参数
    private AudioParams mAudioParams;
    // 录制标志位
    private volatile boolean mRecording;
    // 最小缓冲大小
    private int minBufferSize;
    // 录制状态监听器
    private OnRecordListener mRecordListener;


    //================ encoder ================

    private static final String AUDIO_MIME_TYPE = "audio/mp4a-latm";

    private static final int ENCODE_TIMEOUT = -1;

    private MediaFormat mMediaFormat;
    private MediaCodec mMediaCodec;
    private MediaMuxer mMediaMuxer;
    private ByteBuffer[] mInputBuffers;
    private ByteBuffer[] mOutputBuffers;
    private MediaCodec.BufferInfo mBufferInfo;

    private int mSampleRate;
    private int mChannelCount;

    private int mAudioTrackId;
    private int mTotalBytesRead;
    private long mPresentationTimeUs;   // 编码的时长

    private void encoderPrepare(AudioParams params){
        if (params.getAudioPath() == null) {
            throw new IllegalStateException("No Output Path found.");
        }

        mSampleRate = params.getSampleRate();
        mChannelCount = (params.getChannel() == AudioFormat.CHANNEL_IN_MONO)? 1 : 2;

        mMediaFormat = MediaFormat.createAudioFormat(AUDIO_MIME_TYPE, mSampleRate, mChannelCount);
        mMediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        mMediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, params.getBitRate());
        mMediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, mBufferSize);

        if (VERBOSE) {
            Log.d(TAG, "encoderPrepare format: " + mMediaFormat);
        }

        try {
            mMediaCodec = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
        } catch (IOException e) {
            Log.e(TAG, "init MediaCodec fail, err=" + e.getMessage());
            e.printStackTrace();
        }
        mMediaCodec.configure(mMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mMediaCodec.start();

        mInputBuffers = mMediaCodec.getInputBuffers();
        mOutputBuffers = mMediaCodec.getOutputBuffers();

        mBufferInfo = new MediaCodec.BufferInfo();

        try {
            mMediaMuxer = new MediaMuxer(params.getAudioPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        mTotalBytesRead = 0;
        mPresentationTimeUs = 0;
    }

    /**
     * 编码PCM数据
     */
    public void encoderEncodePCM(byte[] data, int len) {
        int inputIndex;
        inputIndex = mMediaCodec.dequeueInputBuffer(ENCODE_TIMEOUT);
        if (inputIndex >= 0) {
            ByteBuffer buffer = mInputBuffers[inputIndex];
            buffer.clear();

            if (len < 0) {
                mMediaCodec.queueInputBuffer(inputIndex, 0, 0, (long) mPresentationTimeUs, 0);
            } else {
                mTotalBytesRead += len;
                buffer.put(data, 0, len);
                mMediaCodec.queueInputBuffer(inputIndex, 0, len, (long) mPresentationTimeUs, 0);
                mPresentationTimeUs = 1000000L * (mTotalBytesRead / mChannelCount / 2) / mSampleRate;
                if (VERBOSE) Log.d(TAG, "encodePCM: presentationUs：" + mPresentationTimeUs + ", s: " + (mPresentationTimeUs / 1000000f));
            }
        }

        int outputIndex = 0;
        while (outputIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
            outputIndex = mMediaCodec.dequeueOutputBuffer(mBufferInfo, 0);
            if (outputIndex >= 0) {
                ByteBuffer encodedData = mOutputBuffers[outputIndex];
                encodedData.position(mBufferInfo.offset);
                encodedData.limit(mBufferInfo.offset + mBufferInfo.size);
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 && mBufferInfo.size != 0) {
                    mMediaCodec.releaseOutputBuffer(outputIndex, false);
                } else {
                    mMediaMuxer.writeSampleData(mAudioTrackId, mOutputBuffers[outputIndex], mBufferInfo);
                    mMediaCodec.releaseOutputBuffer(outputIndex, false);
                }
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                mMediaFormat = mMediaCodec.getOutputFormat();
                mAudioTrackId = mMediaMuxer.addTrack(mMediaFormat);
                mMediaMuxer.start();
            }
        }
    }

    private void encoderRelease() {
        try {
            if (mMediaCodec != null) {
                mMediaCodec.stop();
                mMediaCodec.release();
                mMediaCodec = null;
            }
            if (mMediaMuxer != null) {
                mMediaMuxer.stop();
                mMediaMuxer.release();
                mMediaMuxer = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    //================ encoder ================


    public MediaType getMediaType() {
        return MediaType.AUDIO;
    }

    public void setOnRecordListener(OnRecordListener listener) {
        mRecordListener = listener;
    }

    /**
     * 开始录制
     */
    public void startRecord() {
        mRecording = true;
        new Thread(this).start();
    }

    /**
     * 停止录制
     */
    public void stopRecord() {
        mRecording = false;
    }

    /**
     * 准备编码器
     * @throws IOException -
     */
    @SuppressLint("MissingPermission")
    public void prepare(@NonNull AudioParams params) throws Exception {
        mAudioParams = params;
        if (mAudioRecord != null) {
            release();
        }
        encoderRelease();

        float speed = 1.0f;
        try {
            minBufferSize = (int)(params.getSampleRate() * 4 * 0.02);
            if (mBufferSize < minBufferSize / speed * 2) {
                mBufferSize = (int) (minBufferSize / speed * 2);
            } else {
                mBufferSize = BUFFER_SIZE;
            }
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    params.getSampleRate(),
                    params.getChannel(),
                    params.getAudioFormat(),
                    minBufferSize);
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }

        int channelCount = (params.getChannel() == AudioFormat.CHANNEL_IN_MONO)? 1 : 2;
        encoderPrepare(params);

        // 音频转码器
        mAudioTranscoder = new AudioTranscoder();
        mAudioTranscoder.setSpeed(speed);
        mAudioTranscoder.configure(params.getSampleRate(), channelCount, params.getAudioFormat());
        mAudioTranscoder.setOutputSampleRateHz(params.getSampleRate());
        mAudioTranscoder.flush();
    }

    /**
     * 释放数据
     */
    public synchronized void release() {
        if (mAudioRecord != null) {
            try {
                mAudioRecord.release();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                mAudioRecord = null;
            }
        }
        encoderRelease();
    }

    @Override
    public void run() {
        long duration = 0;
        try {

            // 初始化录音器
            boolean needToStart = true;
            while (mRecording && needToStart) {
                synchronized (this) {
                    if (mAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                        mAudioRecord.startRecording();
                        // 录制开始回调
                        if (mRecordListener != null) {
                            mRecordListener.onRecordStart(MediaType.AUDIO);
                        }
                        needToStart = false;
                    }
                }
                SystemClock.sleep(10);
            }

            byte[] pcmData = new byte[minBufferSize];
            // 录制编码
            while (mRecording) {
                int size;
                // 取出录音PCM数据
                synchronized (this) {
                    if (mAudioRecord == null) {
                        break;
                    }
                    size = mAudioRecord.read(pcmData, 0, pcmData.length);
                }
                // 将音频送去转码处理
                if (size > 0) {
                    ByteBuffer inBuffer = ByteBuffer.wrap(pcmData,0,size).order(ByteOrder.LITTLE_ENDIAN);
                    mAudioTranscoder.queueInput(inBuffer);
                } else {
                    Thread.sleep(100);
                }

                // 音频倍速转码输出
                ByteBuffer outPut = mAudioTranscoder.getOutput();
                if (outPut != null && outPut.hasRemaining()) {
                    byte[] outData = new byte[outPut.remaining()];
                    outPut.get(outData);
                    synchronized (this) {
                        encoderEncodePCM(outData, outData.length);
                    }
                } else {
                    Thread.sleep(5);
                }
            }

            // 刷新缓冲区
            synchronized (this) {
                if (mAudioTranscoder != null) {
                    mAudioTranscoder.endOfStream();
                    ByteBuffer outBuffer = mAudioTranscoder.getOutput();
                    if (outBuffer != null && outBuffer.hasRemaining()) {
                        byte[] output = new byte[outBuffer.remaining()];
                        outBuffer.get(output);
                        synchronized (this) {
                            encoderEncodePCM(output, output.length);
                        }
                    }
                }
                encoderEncodePCM(null, -1);
            }
            duration = mPresentationTimeUs;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                release();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 录制完成回调
        if (mRecordListener != null) {
            mRecordListener.onRecordFinish(new RecordInfo(mAudioParams.getAudioPath(), duration, getMediaType()));
        }
    }

}
