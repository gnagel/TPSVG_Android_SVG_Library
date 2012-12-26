package com.trevorpage.tpsvg;


import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;


public class SVGView extends View {

	@SuppressWarnings("unused")
	private static final String	LOGTAG				= SVGView.class.getSimpleName();


	Canvas						mCanvas;


	private ITpsvgController	mController;


	private final Paint			mDrawPaint			= new Paint();


	boolean						mEntireRedrawNeeded	= false;


	private boolean				mFill				= false;


	Bitmap						mRenderBitmap		= null;


	private int					mRotation			= 0;


	private SVGParserRenderer	mSvgImage;


	String						subtree				= null;


	// Tried using WeakReference<Bitmap> to avoid View-Bitmap memory leak issues, but this seems
	// to lead to very frequent GC of the bitmaps, leading to terrible performance penalty.
	// WeakReference<Bitmap> bm;

	public SVGView(final Context context) {
		super(context);
		init(context);
	}


	public SVGView(final Context context, final AttributeSet attrs) {
		this(context, attrs, 0);
	}


	public SVGView(final Context context, final AttributeSet attrs, final int defStyle) {
		super(context, attrs, defStyle);
		init(context);

		if (isInEditMode()) {
			return;
		}

		int raw_resource = 0;
		if (null != attrs) {
			final TypedArray array = context.obtainStyledAttributes(attrs, R.styleable.SVGView);
			raw_resource = array.getResourceId(R.styleable.SVGView_android_src, 0);
			array.recycle();
		}

		if (0 == raw_resource) {
			return;
		}

		final SVGParserRenderer image = new SVGParserRenderer(context, raw_resource);
		this.setSVGRenderer(image, null);
		this.setBackgroundColor(Color.TRANSPARENT);
	}


	private void assertValidSvgImage() {
		if (mSvgImage == null) {
			throw new IllegalStateException("The parsed SVG image object needs to be specified first.");
		}
	}


	public void bindController(final ITpsvgController controller) {
		assertValidSvgImage();

		mController = controller;
		// TODO: This is potentially going to be done multiple times, once for each child SVGView of the
		// widget. I question at the moment if / why the controller should be bound to the individual SVGViews
		// and not directly to the SVGParserRenderer.
		mController.setSourceDocumentHeight(mSvgImage.getDocumentHeight());
		mController.setSourceDocumentWidth(mSvgImage.getDocumentWidth());
		mSvgImage.obtainSVGPrivateData(mController);
	}


	private int chooseDimension(final int mode, final int size) {
		if (mode == MeasureSpec.AT_MOST || mode == MeasureSpec.EXACTLY) {
			return size;
		}
		else { // (mode == MeasureSpec.UNSPECIFIED)
			return getPreferredSize();
		}
	}


	// ------------- Initial canvas size setup and scaling ---------------------

	public boolean getFill() {
		return mFill;
	}


	// in case there is no size specified
	private int getPreferredSize() {
		return 270;
	}


	private void init(final Context context) {
		setDrawingCacheEnabled(false);
		mDrawPaint.setAntiAlias(false);
		mDrawPaint.setFilterBitmap(false);
		mDrawPaint.setDither(false);
		mRotation = context.getResources().getConfiguration().orientation;
	}


	/**
	 * This could be called from non-UI thread.
	 */
	public void invalidateBitmap() {
		mEntireRedrawNeeded = true;
		super.postInvalidate();
	}


	@Override
	protected void onDraw(final Canvas canvas) {
		assertValidSvgImage();

		if (mRenderBitmap == null) {
			mRenderBitmap = Bitmap.createBitmap(getMeasuredWidth(), getMeasuredHeight(), Bitmap.Config.ARGB_8888);
			mEntireRedrawNeeded = true;
			mCanvas = new Canvas(mRenderBitmap);
			// mCanvas.rotate(degrees, px, py)
		}

		if (mEntireRedrawNeeded) {
			mEntireRedrawNeeded = false;
			mRenderBitmap.eraseColor(android.graphics.Color.TRANSPARENT);
			final Canvas c = new Canvas(mRenderBitmap);

			// hacky rotation idea test
			/*
			 * if (getWidth() != getHeight() && mRotation == 1) {
			 * c.rotate(90, getWidth() / 2, getHeight() / 2);
			 * c.scale(getWidth() / getHeight(), getHeight() / getWidth(), getWidth() / 2, getHeight() / 2);
			 * }
			 */

			mSvgImage.paintImage(c, subtree, this, mController, mFill);
		}

		canvas.drawBitmap(mRenderBitmap, 0f, 0f, mDrawPaint);
	}


	@Override
	protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
		/*
		 * int widthMode = MeasureSpec.getMode(widthMeasureSpec);
		 * int widthSize = MeasureSpec.getSize(widthMeasureSpec);
		 * int heightMode = MeasureSpec.getMode(heightMeasureSpec);
		 * int heightSize = MeasureSpec.getSize(heightMeasureSpec);
		 * int chosenWidth = chooseDimension(widthMode, widthSize);
		 * int chosenHeight = chooseDimension(heightMode, heightSize);
		 * // keeps the width equal to the height regardless of the MeasureSpec
		 * // restrictions.
		 * int chosenDimension = Math.min(chosenWidth, chosenHeight);
		 * setMeasuredDimension(chosenDimension, chosenDimension);
		 * //setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec) );
		 */

		super.onMeasure(widthMeasureSpec, heightMeasureSpec);
	}


	@Override
	protected void onSizeChanged(final int w, final int h, final int oldw, final int oldh) {
		mRenderBitmap = null;
		super.onSizeChanged(w, h, oldw, oldh);
	}


	public void setFill(final boolean fill) {
		mFill = fill;
	}


	/**
	 * Specify the particular subtree (or 'node') of the original SVG XML file that this view
	 * shall render. The default is null, which results in the entire SVG image being rendered.
	 * 
	 * @param nodeId
	 */
	public void setSubtree(final String subtreeId) {
		subtree = subtreeId;
	}


	public void setSVGRenderer(final SVGParserRenderer image, final String subtreeTagName) {
		mSvgImage = image;
		setSubtree(subtreeTagName);
	}
}
