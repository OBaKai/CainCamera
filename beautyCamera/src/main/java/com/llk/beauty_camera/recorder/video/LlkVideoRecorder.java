package com.llk.beauty_camera.recorder.video;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.llk.beauty_camera.filter.bean.GLImageFilter;
import com.llk.beauty_camera.filter.gles.EglCore;
import com.llk.beauty_camera.filter.gles.WindowSurface;
import com.llk.beauty_camera.filter.utils.OpenGLUtils;
import com.llk.beauty_camera.filter.utils.TextureRotationUtils;
import com.llk.beauty_camera.recorder.MediaType;
import com.llk.beauty_camera.recorder.OnRecordListener;
import com.llk.beauty_camera.recorder.RecordInfo;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public final class LlkVideoRecorder implements Runnable{

    private static final String TAG = LlkVideoRecorder.class.getSimpleName();
    private static final boolean VERBOSE = true;

    // 开始录制
    private static final int MSG_START_RECORDING = 0;
    // 停止录制
    private static final int MSG_STOP_RECORDING = 1;
    // 录制帧可用
    private static final int MSG_FRAME_AVAILABLE = 2;
    // 退出录制
    private static final int MSG_QUIT = 3;

    // 录制用的OpenGL上下文和EGLSurface
    private WindowSurface mInputWindowSurface;
    private EglCore mEglCore;
    private GLImageFilter mImageFilter;
    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTextureBuffer;

    // 录制Handler;
    private volatile RecordHandler mHandler;

    // 录制状态锁
    private final Object mReadyFence = new Object();
    private boolean mReady;
    private boolean mRunning;

    // 录制监听器
    private OnRecordListener mRecordListener;

    // 倍速录制索引你
    private int mDrawFrameIndex;  // 绘制帧索引，用于表示预览的渲染次数，用于大于1.0倍速录制的丢帧操作
    private long mFirstTime; // 录制开始的时间，方便开始录制

    //================ encoder ================
    private Surface mInputSurface;
    private MediaMuxer mMediaMuxer;
    private MediaCodec mMediaCodec;
    private MediaCodec.BufferInfo mBufferInfo;
    private int mTrackIndex;
    private boolean mMuxerStarted;
    // 录制起始时间戳
    private long mStartTimeStamp;
    // 记录上一个时间戳
    private long mLastTimeStamp;
    // 录制时长
    private long mDuration;

    private VideoParams mVideoParams;

    private void encoderPrepare(VideoParams params){
        mVideoParams = params;
        mBufferInfo = new MediaCodec.BufferInfo();

        // 设置编码格式
        int videoWidth = (params.getVideoWidth() % 2 == 0) ? params.getVideoWidth() : params.getVideoWidth() - 1;
        int videoHeight = (params.getVideoHeight() % 2 == 0) ? params.getVideoHeight() : params.getVideoHeight() - 1;
        MediaFormat format = MediaFormat.createVideoFormat(VideoParams.MIME_TYPE, videoWidth, videoHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, params.getBitRate());
        format.setInteger(MediaFormat.KEY_FRAME_RATE, VideoParams.FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VideoParams.I_FRAME_INTERVAL);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int profile = 0;
            int level = 0;
            if (VideoParams.MIME_TYPE.equals("video/avc")) {
                profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;
                if (videoWidth * videoHeight >= 1920 * 1080) {
                    level = MediaCodecInfo.CodecProfileLevel.AVCLevel4;
                } else {
                    level = MediaCodecInfo.CodecProfileLevel.AVCLevel31;
                }
            } else if (VideoParams.MIME_TYPE.equals("video/hevc")) {
                profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain;
                if (videoWidth * videoHeight >= 1920 * 1080) {
                    level = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4;
                } else {
                    level = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31;
                }
            }
            format.setInteger(MediaFormat.KEY_PROFILE, profile);
            // API 23以后可以设置AVC的编码level，低于23设置了但不生效
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_LEVEL, level);
            }
        }
        if (VERBOSE) {
            Log.d(TAG, "encoderPrepare format: " + format);
        }
        // 创建编码器
        try {
            mMediaCodec = MediaCodec.createEncoderByType(VideoParams.MIME_TYPE);
        } catch (IOException e) {
            Log.e(TAG, "init MediaCodec fail, err=" + e.getMessage());
            e.printStackTrace();
        }
        mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mInputSurface = mMediaCodec.createInputSurface();
        mMediaCodec.start();

        // 创建复用器
        try {
            mMediaMuxer = new MediaMuxer(params.getVideoPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        mTrackIndex = -1;
        mMuxerStarted = false;
    }

    private void encoderDrain(boolean endOfStream) {
        final int TIMEOUT_USEC = 10000;
        if (VERBOSE) Log.d(TAG, "encoderDrain endOfStream=" + endOfStream);

        if (endOfStream) {
            mMediaCodec.signalEndOfInputStream();
        }

        ByteBuffer[] encoderOutputBuffers = mMediaCodec.getOutputBuffers();
        while (true) {
            int encoderStatus = mMediaCodec.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                encoderOutputBuffers = mMediaCodec.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat newFormat = mMediaCodec.getOutputFormat();
                if (VERBOSE) {
                    Log.d(TAG, "encoder output format changed: " + newFormat.getString(MediaFormat.KEY_MIME));
                }
                // 提取视频轨道并打开复用器
                mTrackIndex = mMediaMuxer.addTrack(newFormat);
                mMediaMuxer.start();
                mMuxerStarted = true;
            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
            } else {
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                }

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    if (VERBOSE) {
                        Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    }
                    mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }

                    // 计算录制时钟
                    if (mLastTimeStamp > 0 && mBufferInfo.presentationTimeUs < mLastTimeStamp) {
                        mBufferInfo.presentationTimeUs = mLastTimeStamp + 10 * 1000;
                    }
                    encoderCalculateTimeUs(mBufferInfo);
                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);
                    // 将编码数据写入复用器中
                    mMediaMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    if (VERBOSE) {
                        Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer, ts=" +
                                mBufferInfo.presentationTimeUs);
                    }

                    // 录制时长回调
                    if (mRecordListener != null) {
                        mRecordListener.onRecording(MediaType.VIDEO, mDuration);
                    }
                }

                mMediaCodec.releaseOutputBuffer(encoderStatus, false);

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        if (VERBOSE) {
                            Log.d(TAG, "end of stream reached");
                        }
                    }
                    break;      // out of while
                }
            }
        }
    }

    private void encoderRelease() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");
        if (mMediaCodec != null) {
            mMediaCodec.stop();
            mMediaCodec.release();
            mMediaCodec = null;
        }
        if (mMediaMuxer != null) {
            if (mMuxerStarted) {
                mMediaMuxer.stop();
            }
            mMediaMuxer.release();
            mMediaMuxer = null;
        }
    }

    //================ encoder ================

    /**
     * 计算pts
     * @param info
     */
    private void encoderCalculateTimeUs(MediaCodec.BufferInfo info) {
        mLastTimeStamp = info.presentationTimeUs;
        if (mStartTimeStamp == 0) {
            mStartTimeStamp = info.presentationTimeUs;
        } else {
            mDuration = info.presentationTimeUs - mStartTimeStamp;
        }
    }

    /**
     * 设置录制监听器
     * @param listener
     */
    public void setOnRecordListener(OnRecordListener listener) {
        mRecordListener = listener;
    }

    /**
     * 开始录制
     * @param params 录制参数
     */
    public void startRecord(VideoParams params) {
        if (VERBOSE) {
            Log.d(TAG, "VideoRecorder: startRecord()");
        }
        synchronized (mReadyFence) {
            if (mRunning) {
                Log.w(TAG, "VideoRecorder thread already running");
                return;
            }
            mRunning = true;
            new Thread(this, "VideoRecorder").start();
            while (!mReady) {
                try {
                    mReadyFence.wait();
                } catch (InterruptedException ie) {
                    // ignore
                }
            }
        }

        mDrawFrameIndex = 0;
        mFirstTime = -1;
        mHandler.sendMessage(mHandler.obtainMessage(MSG_START_RECORDING, params));
    }

    /**
     * 停止录制
     */
    public void stopRecord() {
        if (mHandler != null) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_STOP_RECORDING));
            mHandler.sendMessage(mHandler.obtainMessage(MSG_QUIT));
        }
    }

    /**
     * 释放所有资源
     */
    public void release() {
        if (mHandler != null) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_QUIT));
        }
    }

    /**
     * 判断是否正在录制
     * @return
     */
    public boolean isRecording() {
        synchronized (mReadyFence) {
            return mRunning;
        }
    }

    /**
     * 录制帧可用状态
     * @param texture
     * @param timestamp
     */
    public void frameAvailable(int texture, long timestamp) {
        synchronized (mReadyFence) {
            if (!mReady) {
                return;
            }
        }
        // 时间戳为0时，不可用
        if (timestamp == 0) {
            return;
        }

        if (mHandler != null) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_FRAME_AVAILABLE,
                    (int) (timestamp >> 32), (int) timestamp, texture));
        }
    }

    @Override
    public void run() {
        Looper.prepare();
        synchronized (mReadyFence) {
            mHandler = new RecordHandler(this);
            mReady = true;
            mReadyFence.notify();
        }
        Looper.loop();

        Log.d(TAG, "Video record thread exiting");
        synchronized (mReadyFence) {
            mReady = mRunning = false;
            mHandler = null;
        }
    }

    /**
     * 录制Handler
     */
    private static class RecordHandler extends Handler {

        private WeakReference<LlkVideoRecorder> mWeakRecorder;

        public RecordHandler(LlkVideoRecorder encoder) {
            mWeakRecorder = new WeakReference<LlkVideoRecorder>(encoder);
        }

        @Override
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;
            Object obj = inputMessage.obj;

            LlkVideoRecorder encoder = mWeakRecorder.get();
            if (encoder == null) {
                Log.w(TAG, "RecordHandler.handleMessage: encoder is null");
                return;
            }

            switch (what) {
                case MSG_START_RECORDING: {
                    encoder.onStartRecord((VideoParams) obj);
                    break;
                }

                case MSG_STOP_RECORDING: {
                    encoder.onStopRecord();
                    break;
                }

                case MSG_FRAME_AVAILABLE: {
                    long timestamp = (((long) inputMessage.arg1) << 32) |
                            (((long) inputMessage.arg2) & 0xffffffffL);
                    encoder.onRecordFrameAvailable((int)obj, timestamp);
                    break;
                }

                case MSG_QUIT: {
                    Looper.myLooper().quit();
                    break;
                }

                default:
                    throw new RuntimeException("Unhandled msg what=" + what);
            }
        }
    }

    /**
     * 开始录制
     * @param params
     */
    private void onStartRecord(@NonNull VideoParams params) {
        if (VERBOSE) {
            Log.d(TAG, "onStartRecord " + params);
        }
        mVertexBuffer = OpenGLUtils.createFloatBuffer(TextureRotationUtils.CubeVertices);
        mTextureBuffer = OpenGLUtils.createFloatBuffer(TextureRotationUtils.TextureVertices);

        encoderPrepare(params);

        // 创建EGL上下文和Surface
        mEglCore = new EglCore(params.getEglContext(), EglCore.FLAG_RECORDABLE);
        mInputWindowSurface = new WindowSurface(mEglCore, mInputSurface, true);
        mInputWindowSurface.makeCurrent();
        // 创建录制用的滤镜
        mImageFilter = new GLImageFilter(null);
        mImageFilter.onInputSizeChanged(params.getVideoWidth(), params.getVideoHeight());
        mImageFilter.onDisplaySizeChanged(params.getVideoWidth(), params.getVideoHeight());
        // 录制开始回调
        if (mRecordListener != null) {
            mRecordListener.onRecordStart(MediaType.VIDEO);
        }
    }

    /**
     * 停止录制
     */
    private void onStopRecord() {
        if (VERBOSE) {
            Log.d(TAG, "onStopRecord");
        }
        encoderDrain(true);
        encoderRelease();
        if (mImageFilter != null) {
            mImageFilter.release();
            mImageFilter = null;
        }
        if (mInputWindowSurface != null) {
            mInputWindowSurface.release();
            mInputWindowSurface = null;
        }
        if (mEglCore != null) {
            mEglCore.release();
            mEglCore = null;
        }

        // 录制完成回调
        if (mRecordListener != null) {
            mRecordListener.onRecordFinish(new RecordInfo(mVideoParams.getVideoPath(), mDuration, MediaType.VIDEO));
        }
    }

    /**
     * 录制帧可用
     * @param texture
     * @param timestampNanos
     */
    private void onRecordFrameAvailable(int texture, long timestampNanos) {
        if (VERBOSE) {
            Log.d(TAG, "onRecordFrameAvailable");
        }
        drawFrame(texture, timestampNanos);
        mDrawFrameIndex++;
    }

    /**
     * 绘制编码一帧数据
     * @param texture
     * @param timestampNanos
     */
    private void drawFrame(int texture, long timestampNanos) {
        mInputWindowSurface.makeCurrent();
        mImageFilter.drawFrame(texture, mVertexBuffer, mTextureBuffer);
        mInputWindowSurface.setPresentationTime(getPTS(timestampNanos));
        mInputWindowSurface.swapBuffers();
        encoderDrain(false);
    }


    /**
     * 计算时间戳
     */
    private long getPTS(long timestampNanos) {
        return timestampNanos;
    }
}
