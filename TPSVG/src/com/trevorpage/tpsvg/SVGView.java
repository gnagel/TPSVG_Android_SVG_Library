package com.trevorpage.tpsvg;


import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;


public class SVGView extends View {
	@SuppressWarnings("unused")
	private static final String	LOGTAG	= SVGView.class.getSimpleName();


	public static Bitmap loadBitmapFromView(final Context context, final int raw_resource) {
		Bitmap bitmap = null;
		final SVGView view = new SVGView(context);
		view.setImageSvg(raw_resource);

		bitmap = view.getBitmap();
		if (null != bitmap) {
			return bitmap;
		}

		view.measure(
				MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
				MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
		view.layout(
				0,
				0,
				view.getMeasuredWidth(),
				view.getMeasuredHeight());

		bitmap = Bitmap.createBitmap(
				view.getMeasuredWidth(),
				view.getMeasuredHeight(),
				Bitmap.Config.ARGB_8888);

		final Canvas canvas = new Canvas(bitmap);
		view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
		view.draw(canvas);
		return bitmap;
	}


	public static Drawable loadDrawableFromView(final Context context, final int raw_resource) {
		final Bitmap bitmap = loadBitmapFromView(context, raw_resource);
		return new BitmapDrawable(bitmap);
	}


	private Canvas				mCanvas;


	private ITpsvgController	mController;


	private final Paint			mDrawPaint			= new Paint();


	private boolean				mEntireRedrawNeeded	= false;


	private boolean				mFill				= false;


	private Bitmap				mRenderBitmap		= null;


	private int					mRotation			= 0;


	private SVGParserRenderer	mSvgImage			= null;


	// Tried using WeakReference<Bitmap> to avoid View-Bitmap memory leak issues, but this seems
	// to lead to very frequent GC of the bitmaps, leading to terrible performance penalty.
	// WeakReference<Bitmap> bm;

	private String				subtree				= null;


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
			mSvgImage = new SVGParserRenderer();
			return;
		}

		int raw_resource = 0;
		if (null != attrs) {
			final TypedArray array = context.obtainStyledAttributes(attrs, R.styleable.SVGView);
			raw_resource = array.getResourceId(R.styleable.SVGView_android_src, 0);
			array.recycle();
		}

		if (0 != raw_resource) {
			setImageSvg(raw_resource);
		}
	}


	private void assertValidSvgImage() {
		if (mSvgImage == null && !isInEditMode()) {
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


	public Bitmap getBitmap() {
		// Cache the SVG to a bitmap
		setDrawingCacheEnabled(true);

		// this is the important code :)
		// Without it the view will have a dimension of 0,0 and the bitmap will be null
		measure(
				MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
				MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
		layout(0, 0, getMeasuredWidth(), getMeasuredHeight());

		buildDrawingCache(true);
		final Bitmap drawing_cache = getDrawingCache();
		final Bitmap bitmap = null == drawing_cache ? null : Bitmap.createBitmap(drawing_cache);

		// Clear drawing cache
		setDrawingCacheEnabled(false);

		return bitmap;
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


	public void setImageSvg(final int raw_resource) {
		final SVGParserRenderer image = SVGParserFactory.create(getContext(), raw_resource);
		this.setSVGRenderer(image, null);
		this.setBackgroundColor(Color.TRANSPARENT);
	}


	/**
	 * Specify the particular subtree (or 'node') of the original SVG XML file that this view
	 * shall render. The default is null, which results in the entire SVG image being rendered.
	 * 
	 * @param nodeId
	 */
	@Deprecated
	public void setSubtree(final String subtreeId) {
		subtree = subtreeId;
	}


	@Deprecated
	public void setSVGRenderer(final SVGParserRenderer image, final String subtreeTagName) {
		mSvgImage = image;
		setSubtree(subtreeTagName);
	}
}
