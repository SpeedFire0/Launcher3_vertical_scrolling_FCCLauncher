/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.fcclauncher.util;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import com.android.fcclauncher.R;

/**
 * This class differs from the framework {@link android.widget.EdgeEffect}:
 *   1) It does not use PorterDuffXfermode
 *   2) The width to radius factor is smaller (0.5 instead of 0.75)
 */
public class LauncherEdgeEffect {
    private static final String TAG = "PagedView";
    private static final boolean DEBUG = false;
    // Time it will take the effect to fully recede in ms
    private static final int RECEDE_TIME = 600;

    // Time it will take before a pulled glow begins receding in ms
    private static final int PULL_TIME = 167;

    // Time it will take in ms for a pulled glow to decay to partial strength before release
    private static final int PULL_DECAY_TIME = 2000;

    private static final float MAX_ALPHA = 0.5f;

    private static final float MAX_GLOW_SCALE = 2.f;

    private static final float PULL_GLOW_BEGIN = 0.f;

    // Minimum velocity that will be absorbed
    private static final int MIN_VELOCITY = 100;
    // Maximum velocity, clamps at this value
    private static final int MAX_VELOCITY = 10000;

    private static final float EPSILON = 0.001f;

    private static final double ANGLE = Math.PI / 6;
    private static final float SIN = (float) Math.sin(ANGLE);
    private static final float COS = (float) Math.cos(ANGLE);

    private float mGlowAlpha;
    private float mGlowScaleY;
    private float mGlowScaleX;

    private float mGlowAlphaStart;
    private float mGlowAlphaFinish;
    private float mGlowScaleYStart;
    private float mGlowScaleYFinish;
    private float mGlowScaleXStart;
    private float mGlowScaleXFinish;

    private long mStartTime;
    private float mDuration;

    private final Interpolator mInterpolator;

    private static final int STATE_IDLE = 0;
    private static final int STATE_PULL = 1;
    private static final int STATE_ABSORB = 2;
    private static final int STATE_RECEDE = 3;
    private static final int STATE_PULL_DECAY = 4;

    private static final float PULL_DISTANCE_ALPHA_GLOW_FACTOR = 0.8f;
    private static final float MAX_GLOW_HEIGHT = 4.f;
    private static final int VELOCITY_GLOW_FACTOR = 6;

    private int mState = STATE_IDLE;

    private float mPullDistance;

    private final Rect mBounds = new Rect();
    private final Paint mPaint = new Paint();
    private float mRadius;
    private float mBaseGlowScale;
    private float mDisplacement = 0.5f;
    private float mTargetDisplacement = 0.5f;

    private final Drawable mEdge;
    private final Drawable mGlow;

    private final int mEdgeHeight;
    private final int mGlowHeight;
    private final int mGlowWidth;
    private final int mMaxEffectHeight;
    private int mWidth;
    private int mHeight;
    private float mEdgeAlpha;
    private float mEdgeScaleY;
    private float mEdgeAlphaStart;
    private float mEdgeAlphaFinish;
    private float mEdgeScaleYStart;
    private float mEdgeScaleYFinish;

    /**
     * Construct a new EdgeEffect with a theme appropriate for the provided context.
     */
    public LauncherEdgeEffect(Context context) {
        final Resources res = context.getResources();
        mPaint.setAntiAlias(true);
        mPaint.setStyle(Paint.Style.FILL);
        mInterpolator = new DecelerateInterpolator();

        mEdge = res.getDrawable(R.drawable.overscroll_edge);
        mGlow = res.getDrawable(R.drawable.overscroll_glow);
        mEdgeHeight = mEdge.getIntrinsicHeight();
        mGlowHeight = mGlow.getIntrinsicHeight();
        mGlowWidth = mGlow.getIntrinsicWidth();
        mMaxEffectHeight = (int) (Math.min(
                mGlowHeight * MAX_GLOW_HEIGHT * mGlowHeight / mGlowWidth * 0.6f,
                mGlowHeight * MAX_GLOW_HEIGHT) + 0.5f);
    }

    /**
     * Set the size of this edge effect in pixels.
     *
     * @param width Effect width in pixels
     * @param height Effect height in pixels
     */
    public void setSize(int width, int height) {
/*        final float r = width * 0.5f / SIN;*/
        final float r = height * 0.5f / SIN;
        final float y = COS * r;
        final float h = r - y;
/*        final float or = height * 0.75f / SIN;*/
        final float or = width * 0.75f / SIN;
        final float oy = COS * or;
        final float oh = or - oy;

        mRadius = r;
        mBaseGlowScale = h > 0 ? Math.min(oh / h, 1.f) : 1.f;

        mBounds.set(mBounds.left, mBounds.top, width, (int) Math.min(height, h));
        if (DEBUG) Log.d(TAG, "mBounds(canvas): " + mBounds);
    }

    /**
     * Reports if this EdgeEffect's animation is finished. If this method returns false
     * after a call to {@link #draw(Canvas)} the host widget should schedule another
     * drawing pass to continue the animation.
     *
     * @return true if animation is finished, false if drawing should continue on the next frame.
     */
    public boolean isFinished() {
        return mState == STATE_IDLE;
    }

    /**
     * Immediately finish the current animation.
     * After this call {@link #isFinished()} will return true.
     */
    public void finish() {
        mState = STATE_IDLE;
    }

    /**
     * A view should call this when content is pulled away from an edge by the user.
     * This will update the state of the current visual effect and its associated animation.
     * The host view should always {@link android.view.View#invalidate()} after this
     * and draw the results accordingly.
     *
     * <p>Views using EdgeEffect should favor {@link #onPull(float, float)} when the displacement
     * of the pull point is known.</p>
     *
     * @param deltaDistance Change in distance since the last call. Values may be 0 (no change) to
     *                      1.f (full length of the view) or negative values to express change
     *                      back toward the edge reached to initiate the effect.
     */
    public void onPull(float deltaDistance) {
        onPull(deltaDistance, 0.5f);
    }

    /**
     * A view should call this when content is pulled away from an edge by the user.
     * This will update the state of the current visual effect and its associated animation.
     * The host view should always {@link android.view.View#invalidate()} after this
     * and draw the results accordingly.
     *
     * @param deltaDistance Change in distance since the last call. Values may be 0 (no change) to
     *                      1.f (full length of the view) or negative values to express change
     *                      back toward the edge reached to initiate the effect.
     * @param displacement The displacement from the starting side of the effect of the point
     *                     initiating the pull. In the case of touch this is the finger position.
     *                     Values may be from 0-1.
     */
    public void onPull(float deltaDistance, float displacement) {
        final long now = AnimationUtils.currentAnimationTimeMillis();
        mTargetDisplacement = displacement;
        if (mState == STATE_PULL_DECAY && now - mStartTime < mDuration) {
            return;
        }
        if (mState != STATE_PULL) {
/*            mGlowScaleY = Math.max(PULL_GLOW_BEGIN, mGlowScaleY);*/
            mGlowScaleX = Math.max(PULL_GLOW_BEGIN, mGlowScaleX);
        }
        mState = STATE_PULL;

        mStartTime = now;
        mDuration = PULL_TIME;

        mPullDistance += deltaDistance;

        final float absdd = Math.abs(deltaDistance);
        mGlowAlpha = mGlowAlphaStart = Math.min(MAX_ALPHA,
                mGlowAlpha + (absdd * PULL_DISTANCE_ALPHA_GLOW_FACTOR));

        if (mPullDistance == 0) {
/*            mGlowScaleY = mGlowScaleYStart = 0;*/
            mGlowScaleX = mGlowScaleXStart = 0;
        } else {
/*            final float scale = (float) (Math.max(0, 1 - 1 /
                    Math.sqrt(Math.abs(mPullDistance) * mBounds.height()) - 0.3d) / 0.7d);

            mGlowScaleY = mGlowScaleYStart = scale;*/
            final float scale = (float) (Math.max(0, 1 - 1 /
                    Math.sqrt(Math.abs(mPullDistance) * mBounds.width()) - 0.3d) / 0.7d);

            mGlowScaleX = mGlowScaleXStart = scale;
        }

        mGlowAlphaFinish = mGlowAlpha;
/*        mGlowScaleYFinish = mGlowScaleY;*/
        mGlowScaleXFinish = mGlowScaleX;
    }

    /**
     * Call when the object is released after being pulled.
     * This will begin the "decay" phase of the effect. After calling this method
     * the host view should {@link android.view.View#invalidate()} and thereby
     * draw the results accordingly.
     */
    public void onRelease() {
        mPullDistance = 0;

        if (mState != STATE_PULL && mState != STATE_PULL_DECAY) {
            return;
        }

        mState = STATE_RECEDE;
        mGlowAlphaStart = mGlowAlpha;
/*        mGlowScaleYStart = mGlowScaleY;*/
        mGlowScaleXStart = mGlowScaleX;

        mGlowAlphaFinish = 0.f;
/*        mGlowScaleYFinish = 0.f;*/
        mGlowScaleXFinish = 0.f;

        mStartTime = AnimationUtils.currentAnimationTimeMillis();
        mDuration = RECEDE_TIME;
    }

    /**
     * Call when the effect absorbs an impact at the given velocity.
     * Used when a fling reaches the scroll boundary.
     *
     * <p>When using a {@link android.widget.Scroller} or {@link android.widget.OverScroller},
     * the method <code>getCurrVelocity</code> will provide a reasonable approximation
     * to use here.</p>
     *
     * @param velocity Velocity at impact in pixels per second.
     */
    public void onAbsorb(int velocity) {
        mState = STATE_ABSORB;
        velocity = Math.min(Math.max(MIN_VELOCITY, Math.abs(velocity)), MAX_VELOCITY);

        mStartTime = AnimationUtils.currentAnimationTimeMillis();
        mDuration = 0.15f + (velocity * 0.02f);

        // The glow depends more on the velocity, and therefore starts out
        // nearly invisible.
        mGlowAlphaStart = 0.3f;
/*        mGlowScaleYStart = Math.max(mGlowScaleY, 0.f);*/
        mGlowScaleXStart = Math.max(mGlowScaleX, 0.f);

        // Growth for the size of the glow should be quadratic to properly
        // respond
        // to a user's scrolling speed. The faster the scrolling speed, the more
        // intense the effect should be for both the size and the saturation.
/*        mGlowScaleYFinish = Math.min(0.025f + (velocity * (velocity / 100) * 0.00015f) / 2, 1.f);*/
        mGlowScaleXFinish = Math.min(0.025f + (velocity * (velocity / 100) * 0.00015f) / 2, 1.f);
        // Alpha should change for the glow as well as size.
        mGlowAlphaFinish = Math.max(
                mGlowAlphaStart, Math.min(velocity * VELOCITY_GLOW_FACTOR * .00001f, MAX_ALPHA));
        mTargetDisplacement = 0.5f;
    }

    /**
     * Set the color of this edge effect in argb.
     *
     * @param color Color in argb
     */
    public void setColor(int color) {
        mPaint.setColor(color);
    }

    /**
     * Return the color of this edge effect in argb.
     * @return The color of this edge effect in argb
     */
    public int getColor() {
        return mPaint.getColor();
    }

    /**
     * Draw into the provided canvas. Assumes that the canvas has been rotated
     * accordingly and the size has been set. The effect will be drawn the full
     * width of X=0 to X=width, beginning from Y=0 and extending to some factor <
     * 1.f of height.
     *
     * @param canvas Canvas to draw into
     * @return true if drawing should continue beyond this frame to continue the
     *         animation
     */
    public boolean draw(Canvas canvas) {


        update();

/*        final float centerX = mBounds.centerX();
        final float centerY = mBounds.height() - mRadius;*/
        if (DEBUG)  Log.d(TAG, "Canvas_draw1: " + canvas.getClipBounds());
        final float centerX = mBounds.width() - mRadius;
        final float centerY = mBounds.centerY();


/*        canvas.scale(1.f, Math.min(mGlowScaleY, 1.f) * mBaseGlowScale, centerX, 0);

        final float displacement = Math.max(0, Math.min(mDisplacement, 1.f)) - 0.5f;
        float translateX = mBounds.width() * displacement / 2;
        mPaint.setAlpha((int) (0xff * mGlowAlpha));
        canvas.drawCircle(centerX + translateX, centerY, mRadius, mPaint);*/

        /*TODO: SF. Need to somehow adapt these settings for vertical edge overscroll effect. Currently it does not work properly. Need help*/

        canvas.scale(Math.min(mGlowScaleX, 1.f) * mBaseGlowScale, 1.f,  0, centerY);
//        Log.d(TAG, "Canvas_draw2: " + canvas.getClipBounds());
        final float displacement = Math.max(0, Math.min(mDisplacement, 1.f)) - 0.5f;
        float translateY = mBounds.height() * displacement / 2;
        mPaint.setAlpha((int) (0xff * mGlowAlpha));
        canvas.drawCircle(centerX, centerY + translateY, mRadius, mPaint);
/*        canvas.drawCircle(2000, 2000, 5000, mPaint);*/
        if (DEBUG) Log.d(TAG, "Canvas_DRAWCIRCLE. centerX: " + centerX + ", centerY: " + centerY + ", translateY: " + translateY + ", mRadius: " + mRadius + ", mPaint: " + mPaint);
/*        boolean oneLastFrame = false;
        if (mState == STATE_RECEDE && mGlowScaleY == 0) {
            mState = STATE_IDLE;
            oneLastFrame = true;
        }*/
        boolean oneLastFrame = false;
        if (mState == STATE_RECEDE && mGlowScaleX == 0) {
            mState = STATE_IDLE;
            oneLastFrame = true;
        }

        return mState != STATE_IDLE || oneLastFrame;




/*
https://chromium.googlesource.com/android_tools/+/18728e9dd5dd66d4f5edf1b792e77e/sdk/sources/android-19/android/widget/EdgeEffect.java
    update();
    mGlow.setAlpha((int) (Math.max(0, Math.min(mGlowAlpha, 1)) * 255));
    int glowBottom = (int) Math.min(
            mGlowHeight * mGlowScaleY * mGlowHeight / mGlowWidth * 0.6f,
            mGlowHeight * MAX_GLOW_HEIGHT);
    if (mWidth < mMinWidth) {
        // Center the glow and clip it.
        int glowLeft = (mWidth - mMinWidth)/2;
        mGlow.setBounds(glowLeft, 0, mWidth - glowLeft, glowBottom);
    } else {
        // Stretch the glow to fit.
        mGlow.setBounds(0, 0, mWidth, glowBottom);
    }
    mGlow.draw(canvas);
    mEdge.setAlpha((int) (Math.max(0, Math.min(mEdgeAlpha, 1)) * 255));
    int edgeBottom = (int) (mEdgeHeight * mEdgeScaleY);
    if (mWidth < mMinWidth) {
        // Center the edge and clip it.
        int edgeLeft = (mWidth - mMinWidth)/2;
        mEdge.setBounds(edgeLeft, 0, mWidth - edgeLeft, edgeBottom);
    } else {
        // Stretch the edge to fit.
        mEdge.setBounds(0, 0, mWidth, edgeBottom);
    }
    mEdge.draw(canvas);
    if (mState == STATE_RECEDE && glowBottom == 0 && edgeBottom == 0) {
        mState = STATE_IDLE;
    }
    return mState != STATE_IDLE;
}*/

    /**
     * Return the maximum height that the edge effect will be drawn at given the original
     * {@link #setSize(int, int) input size}.
     * @return The maximum height of the edge effect
     */
/*    public int getMaxHeight() {
        return (int) (mBounds.height() * MAX_GLOW_SCALE + 0.5f);
    }*/

    /*
        update();

        final int edgeHeight = mEdge.getIntrinsicHeight();
        final int glowHeight = mGlow.getIntrinsicHeight();

        final float distScale = (float) mHeight / mWidth;

        mGlow.setAlpha((int) (Math.max(0, Math.min(mGlowAlpha, 1)) * 255));
        // Width of the image should be 3 * the width of the screen.
        // Should start off screen to the left.
        mGlow.setBounds(-mWidth, 0, mWidth * 2, (int) Math.min(glowHeight * mGlowScaleY * distScale * 0.6f, mHeight * MAX_GLOW_HEIGHT));
        mGlow.draw(canvas);

        mEdge.setAlpha((int) (Math.max(0, Math.min(mEdgeAlpha, 1)) * 255));
        mEdge.setBounds(0, 0, mWidth, (int) (edgeHeight * mEdgeScaleY));
        mEdge.draw(canvas);

        return mState != STATE_IDLE;
*/

    }
    public int getMaxWidth() {
        return (int) (mBounds.width() * MAX_GLOW_SCALE + 0.5f);
    }


    private void update() {

        final long time = AnimationUtils.currentAnimationTimeMillis();
        final float t = Math.min((time - mStartTime) / mDuration, 1.f);

        final float interp = mInterpolator.getInterpolation(t);

        mGlowAlpha = mGlowAlphaStart + (mGlowAlphaFinish - mGlowAlphaStart) * interp;
        mGlowScaleY = mGlowScaleYStart + (mGlowScaleYFinish - mGlowScaleYStart) * interp;
        mGlowScaleX = mGlowScaleXStart + (mGlowScaleXFinish - mGlowScaleXStart) * interp;
        mDisplacement = (mDisplacement + mTargetDisplacement) / 2;

        if (t >= 1.f - EPSILON) {
            switch (mState) {
                case STATE_ABSORB:
                    mState = STATE_RECEDE;
                    mStartTime = AnimationUtils.currentAnimationTimeMillis();
                    mDuration = RECEDE_TIME;

                    mGlowAlphaStart = mGlowAlpha;
                    mGlowScaleYStart = mGlowScaleY;
                    mGlowScaleXStart = mGlowScaleX;

                    // After absorb, the glow should fade to nothing.
                    mGlowAlphaFinish = 0.f;
                    mGlowScaleYFinish = 0.f;
                    mGlowScaleXFinish = 0.f;

                    break;
                case STATE_PULL:
                    mState = STATE_PULL_DECAY;
                    mStartTime = AnimationUtils.currentAnimationTimeMillis();
                    mDuration = PULL_DECAY_TIME;

                    mGlowAlphaStart = mGlowAlpha;
                    mGlowScaleYStart = mGlowScaleY;
                    mGlowScaleXStart = mGlowScaleX;

                    // After pull, the glow should fade to nothing.
                    mGlowAlphaFinish = 0.f;
                    mGlowScaleYFinish = 0.f;
                    mGlowScaleXFinish = 0.f;
                    break;
                case STATE_PULL_DECAY:
                    mState = STATE_RECEDE;
                    break;
                case STATE_RECEDE:
                    mState = STATE_IDLE;
                    break;
            }
        }

/*        final long time = AnimationUtils.currentAnimationTimeMillis();
        final float t = Math.min((time - mStartTime) / mDuration, 1.f);

        final float interp = mInterpolator.getInterpolation(t);

        mEdgeAlpha = mEdgeAlphaStart + (mEdgeAlphaFinish - mEdgeAlphaStart) * interp;
        mEdgeScaleY = mEdgeScaleYStart + (mEdgeScaleYFinish - mEdgeScaleYStart) * interp;
        mGlowAlpha = mGlowAlphaStart + (mGlowAlphaFinish - mGlowAlphaStart) * interp;
        mGlowScaleY = mGlowScaleYStart + (mGlowScaleYFinish - mGlowScaleYStart) * interp;

        if (t >= 1.f - EPSILON) {
            switch (mState) {
                case STATE_ABSORB:
                    mState = STATE_RECEDE;
                    mStartTime = AnimationUtils.currentAnimationTimeMillis();
                    mDuration = RECEDE_TIME;

                    mEdgeAlphaStart = mEdgeAlpha;
                    mEdgeScaleYStart = mEdgeScaleY;
                    mGlowAlphaStart = mGlowAlpha;
                    mGlowScaleYStart = mGlowScaleY;

                    // After absorb, the glow and edge should fade to nothing.
                    mEdgeAlphaFinish = 0.f;
                    mEdgeScaleYFinish = 0.f;
                    mGlowAlphaFinish = 0.f;
                    mGlowScaleYFinish = 0.f;
                    break;
                case STATE_PULL:
                    mState = STATE_PULL_DECAY;
                    mStartTime = AnimationUtils.currentAnimationTimeMillis();
                    mDuration = PULL_DECAY_TIME;

                    mEdgeAlphaStart = mEdgeAlpha;
                    mEdgeScaleYStart = mEdgeScaleY;
                    mGlowAlphaStart = mGlowAlpha;
                    mGlowScaleYStart = mGlowScaleY;

                    // After pull, the glow and edge should fade to nothing.
                    mEdgeAlphaFinish = 0.f;
                    mEdgeScaleYFinish = 0.f;
                    mGlowAlphaFinish = 0.f;
                    mGlowScaleYFinish = 0.f;
                    break;
                case STATE_PULL_DECAY:
                    // When receding, we want edge to decrease more slowly
                    // than the glow.
                    float factor = mGlowScaleYFinish != 0 ? 1 / (mGlowScaleYFinish * mGlowScaleYFinish) : Float.MAX_VALUE;
                    mEdgeScaleY = mEdgeScaleYStart + (mEdgeScaleYFinish - mEdgeScaleYStart) * interp * factor;
                    break;
                case STATE_RECEDE:
                    mState = STATE_IDLE;
                    break;
            }
        }*/
    }
}
