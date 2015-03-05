/*
 * Copyright (C) 2012 Sven Dawitz for the CyanogenMod Project
 * Copyright (C) 2013 Peter Gregus for GravityBox Project (C3C076@xda)
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

package ma.wanam.xsense.battery;

import ma.wanam.xsense.battery.BatteryInfoManager.BatteryData;
import ma.wanam.xsense.battery.BatteryInfoManager.BatteryStatusListener;
import ma.wanam.xsense.battery.StatusBarIconManager.ColorInfo;
import ma.wanam.xsense.battery.StatusBarIconManager.IconManagerListener;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;

public class CmCircleBattery extends ImageView implements IconManagerListener, BatteryStatusListener {

	private Handler mHandler;

	// state variables
	private boolean mAttached; // whether or not attached to a window
	private static boolean mIsCharging; // whether or not device is currently
										// charging
	private static int mLevel; // current battery level
	private int mAnimOffset; // current level of charging animation
	private boolean mIsAnimating; // stores charge-animation status to reliably
									// remove callbacks
	private int mDockLevel; // current dock battery level
	private boolean mDockIsCharging;// whether or not dock battery is currently
									// charging
	private boolean mIsDocked = false; // whether or not dock battery is
										// connected
	private boolean mPercentage; // whether to show percentage

	private int mCircleSize; // draw size of circle. read rather complicated
								// from
								// another status bar icon, so it fits the icon
								// size
								// no matter the dps and resolution
	private RectF mRectLeft; // contains the precalculated rect used in
								// drawArc(), derived from mCircleSize
	private RectF mRectRight; // contains the precalculated rect used in
								// drawArc() for dock battery
	private Float mTextLeftX; // precalculated x position for drawText() to
								// appear centered
	private Float mTextY; // precalculated y position for drawText() to appear
							// vertical-centered
	private Float mTextRightX; // precalculated x position for dock battery
								// drawText()

	// quiet a lot of paint variables. helps to move cpu-usage from actual
	// drawing to initialization
	private Paint mPaintFont;
	private Paint mPaintGray;
	private Paint mPaintSystem;
	private Paint mPaintRed;

	private static int circleTextColor = Color.WHITE;

	public int getCircleTextColor() {
		return circleTextColor;
	}

	public void setCircleTextColor(int circleTextColor) {
		CmCircleBattery.circleTextColor = circleTextColor;
	}

	// runnable to invalidate view via mHandler.postDelayed() call
	private final Runnable mInvalidate = new Runnable() {
		public void run() {
			if (mAttached) {
				invalidate();
			}
		}
	};

	// keeps track of current battery level and charger-plugged-state
	@Override
	public void onBatteryStatusChanged(BatteryData batteryData) {
		mLevel = batteryData.level;
		mIsCharging = batteryData.charging;

		if (mAttached) {
			LayoutParams l = getLayoutParams();
			l.width = mCircleSize + getPaddingLeft() + (mIsDocked ? mCircleSize + getPaddingLeft() : 0);
			setLayoutParams(l);

			invalidate();
		}
	}

	/***
	 * Start of CircleBattery implementation
	 */
	public CmCircleBattery(Context context) {
		this(context, null);
	}

	public CmCircleBattery(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public CmCircleBattery(Context context, int circleBatteryColor) {
		this(context, null, 0, circleBatteryColor);
	}

	public CmCircleBattery(Context context, AttributeSet attrs, int defStyle) {
		this(context, attrs, defStyle, Color.WHITE);
	}

	public CmCircleBattery(Context context, AttributeSet attrs, int defStyle, int circleBatteryColor) {
		super(context, attrs, defStyle);

		mHandler = new Handler();

		// initialize and setup all paint variables
		// stroke width is later set in initSizeBasedStuff()
		Resources res = getResources();

		mPaintFont = new Paint();
		mPaintFont.setAntiAlias(true);
		mPaintFont.setDither(true);
		mPaintFont.setStyle(Paint.Style.STROKE);

		mPaintGray = new Paint(mPaintFont);
		mPaintSystem = new Paint(mPaintFont);
		mPaintRed = new Paint(mPaintFont);

		mPaintFont.setColor(circleBatteryColor);
		mPaintSystem.setColor(mPaintFont.getColor());
		// could not find the darker definition anywhere in resources
		// do not want to use static 0x404040 color value. would break theming.
		mPaintGray.setColor(res.getColor(android.R.color.darker_gray));
		mPaintRed.setColor(res.getColor(android.R.color.holo_red_light));

		// font needs some extra settings
		mPaintFont.setTextAlign(Align.CENTER);
		mPaintFont.setFakeBoldText(true);
		mPercentage = false;

	}

	public void setPercentage(boolean enable) {
		mPercentage = enable;
		if (mAttached) {
			invalidate();
		}
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		if (!mAttached) {
			mAttached = true;
			mHandler.postDelayed(mInvalidate, 250);
		}
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		if (mAttached) {
			mAttached = false;
			mRectLeft = null; // makes sure, size based variables get
								// recalculated on next attach
			mCircleSize = 0; // makes sure, mCircleSize is reread from icons on
								// next attach
		}
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		if (mCircleSize == 0) {
			initSizeMeasureIconHeight();
		}

		setMeasuredDimension(mCircleSize + getPaddingLeft() + (mIsDocked ? mCircleSize + getPaddingLeft() : 0),
				mCircleSize);
	}

	private void drawCircle(Canvas canvas, int level, int animOffset, float textX, RectF drawRect) {
		Paint usePaint = mPaintSystem;

		// turn red at 14% - same level android battery warning appears
		if (level <= 14) {
			usePaint = mPaintRed;
		}

		// pad circle percentage to 100% once it reaches 97%
		// for one, the circle looks odd with a too small gap,
		// for another, some phones never reach 100% due to hardware design
		int padLevel = level;
		if (padLevel >= 97) {
			padLevel = 100;
		}

		// draw thin gray ring first
		canvas.drawArc(drawRect, 270, 360, false, mPaintGray);
		// draw colored arc representing charge level
		canvas.drawArc(drawRect, 270 + animOffset, 3.6f * padLevel, false, usePaint);
		// if chosen by options, draw percentage text in the middle
		// always skip percentage when 100, so layout doesnt break
		mPaintFont.setColor(getCircleTextColor());
		if (level < 100 && mPercentage) {
			mPaintFont.setColor((level <= 14) ? usePaint.getColor() : getCircleTextColor());
			canvas.drawText(Integer.toString(level), textX, mTextY, mPaintFont);
		}

	}

	@Override
	protected void onDraw(Canvas canvas) {
		if (mRectLeft == null) {
			initSizeBasedStuff();
		}

		updateChargeAnim();

		if (mIsDocked) {
			drawCircle(canvas, mDockLevel, (mDockIsCharging ? mAnimOffset : 0), mTextLeftX, mRectLeft);
			drawCircle(canvas, mLevel, (mIsCharging ? mAnimOffset : 0), mTextRightX, mRectRight);
		} else {
			drawCircle(canvas, mLevel, (mIsCharging ? mAnimOffset : 0), mTextLeftX, mRectLeft);
		}
	}

	/***
	 * updates the animation counter cares for timed callbacks to continue
	 * animation cycles uses mInvalidate for delayed invalidate() callbacks
	 */
	private void updateChargeAnim() {
		if (!(mIsCharging || mDockIsCharging) || (mLevel >= 97 && mDockLevel >= 97)) {
			if (mIsAnimating) {
				mIsAnimating = false;
				mAnimOffset = 0;
				mHandler.removeCallbacks(mInvalidate);
			}
			return;
		}

		mIsAnimating = true;

		if (mAnimOffset > 360) {
			mAnimOffset = 0;
		} else {
			mAnimOffset += 3;
		}

		mHandler.removeCallbacks(mInvalidate);
		mHandler.postDelayed(mInvalidate, 50);
	}

	/***
	 * initializes all size dependent variables sets stroke width and text size
	 * of all involved paints YES! i think the method name is appropriate
	 */
	private void initSizeBasedStuff() {
		if (mCircleSize == 0) {
			initSizeMeasureIconHeight();
		}

		mPaintFont.setTextSize(mCircleSize / 1.8f);

		float strokeWidth = mCircleSize / 9.5f;
		mPaintRed.setStrokeWidth(strokeWidth);
		mPaintSystem.setStrokeWidth(strokeWidth);
		mPaintGray.setStrokeWidth(strokeWidth / 3.5f);

		// calculate rectangle for drawArc calls
		int pLeft = getPaddingLeft();
		mRectLeft = new RectF(pLeft + strokeWidth / 2.0f, 0 + strokeWidth / 2.0f, mCircleSize - strokeWidth / 2.0f
				+ pLeft, mCircleSize - strokeWidth / 2.0f);
		int off = pLeft + mCircleSize;
		mRectRight = new RectF(mRectLeft.left + off, mRectLeft.top, mRectLeft.right + off, mRectLeft.bottom);

		// calculate Y position for text
		Rect bounds = new Rect();
		mPaintFont.getTextBounds("99", 0, "99".length(), bounds);
		mTextLeftX = mCircleSize / 2.0f + getPaddingLeft();
		mTextRightX = mTextLeftX + off;
		// the +1 at end of formular balances out rounding issues. works out on
		// all resolutions
		mTextY = mCircleSize / 2.0f + (bounds.bottom - bounds.top) / 2.0f - strokeWidth / 2.0f + 1;

		// force new measurement for wrap-content xml tag
		measure(0, 0);
	}

	/***
	 * Use exactly the same size as stock battery icon
	 */
	private void initSizeMeasureIconHeight() {
		final Resources res = getResources();
		mCircleSize = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, res.getDisplayMetrics());
	}

	public void setColor(int color) {
		mPaintSystem.setColor(color);
		mPaintFont.setColor(color);
		invalidate();
	}

	public void setLowProfile(boolean lightsOut) {
		final int alpha = lightsOut ? 127 : 255;
		mPaintSystem.setAlpha(alpha);
		mPaintGray.setAlpha(alpha);
		mPaintFont.setAlpha(alpha);
		mPaintRed.setAlpha(alpha);
		invalidate();
	}

	@Override
	public void onIconManagerStatusChanged(int flags, ColorInfo colorInfo) {
		if ((flags & StatusBarIconManager.FLAG_ICON_COLOR_CHANGED) != 0) {
			setColor(colorInfo.coloringEnabled ? colorInfo.iconColor[0] : colorInfo.defaultIconColor);
		} else if ((flags & StatusBarIconManager.FLAG_LOW_PROFILE_CHANGED) != 0) {
			setLowProfile(colorInfo.lowProfile);
		} else if ((flags & StatusBarIconManager.FLAG_ICON_ALPHA_CHANGED) != 0) {
			setAlpha(colorInfo.alphaTextAndBattery);
		}
	}

}
