package com.llk.beauty_camera;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.TextureView;

import androidx.core.view.GestureDetectorCompat;

public class CameraTextureView extends TextureView {

    private static final String TAG = CameraTextureView.class.getSimpleName();
    private static final boolean VERBOSE = false;

    // 滑动事件监听
    private OnTouchScroller mScroller;
    // 单双击事件监听
    private OnMultiClickListener mMultiClickListener;

    // 手势监听器
    private GestureDetectorCompat mGestureDetector;

    public CameraTextureView(Context context) {
        this(context, null);
    }

    public CameraTextureView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CameraTextureView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setClickable(true);
        mGestureDetector = new GestureDetectorCompat(getContext(), new GestureDetector.OnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                if (VERBOSE) {
                    Log.d(TAG, "onDown: ");
                }
                //测试贴纸触摸

                return false;
            }

            @Override
            public void onShowPress(MotionEvent e) {
                if (VERBOSE) {
                    Log.d(TAG, "onShowPress: ");
                }
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (VERBOSE) {
                    Log.d(TAG, "onSingleTapUp: ");
                }
                return false;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (VERBOSE) {
                    Log.d(TAG, "onScroll: ");
                }

                // 上下滑动
                if (Math.abs(distanceX) < Math.abs(distanceY) * 1.5) {
                    // 是否从左边开始上下滑动
                    boolean leftScroll = e1.getX() < getWidth() / 2;
                    if (distanceY > 0) {
                        if (mScroller != null) {
                            mScroller.swipeUpper(leftScroll, Math.abs(distanceY));
                        }
                    } else {
                        if (mScroller != null) {
                            mScroller.swipeDown(leftScroll, Math.abs(distanceY));
                        }
                    }
                }

                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (VERBOSE) {
                    Log.d(TAG, "onLongPress: ");
                }
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (VERBOSE) {
                    Log.d(TAG, "onFling: ");
                }

                // 快速左右滑动
                if (Math.abs(velocityX) > Math.abs(velocityY) * 1.5) {
                    if (velocityX < 0) {
                        if (mScroller != null) {
                            mScroller.swipeBack();
                        }
                    } else {
                        if (mScroller != null) {
                            mScroller.swipeFrontal();
                        }
                    }
                }
                return false;
            }
        });
        mGestureDetector.setOnDoubleTapListener(mDoubleTapListener);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mGestureDetector.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    /**
     * 双击监听器
     */
    private final GestureDetector.OnDoubleTapListener mDoubleTapListener = new GestureDetector.OnDoubleTapListener() {
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (VERBOSE) {
                Log.d(TAG, "onSingleTapConfirmed: ");
            }
            if (mMultiClickListener != null) {
                mMultiClickListener.onSurfaceSingleClick(e.getX(), e.getY());
            }
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (VERBOSE) {
                Log.d(TAG, "onDoubleTap: ");
            }
            if (mMultiClickListener != null) {
                mMultiClickListener.onSurfaceDoubleClick(e.getX(), e.getY());
            }
            return false;
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            if (VERBOSE) {
                Log.d(TAG, "onDoubleTapEvent: ");
            }
            return true;
        }
    };

    /**
     * 添加滑动回调
     * @param scroller
     */
    public void addOnTouchScroller(OnTouchScroller scroller) {
        mScroller = scroller;
    }

    /**
     * 滑动监听器
     */
    public interface OnTouchScroller {
        void swipeBack();
        void swipeFrontal();
        void swipeUpper(boolean startInLeft, float distance);
        void swipeDown(boolean startInLeft, float distance);
    }

    /**
     * 添加点击事件回调
     * @param listener
     */
    public void addMultiClickListener(OnMultiClickListener listener) {
        mMultiClickListener = listener;
    }

    /**
     * 点击事件监听器
     */
    public interface OnMultiClickListener {

        // 单击
        void onSurfaceSingleClick(float x, float y);

        // 双击
        void onSurfaceDoubleClick(float x, float y);
    }
}
