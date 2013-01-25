package com.trevorpage.tpsvg;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;


import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;


import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Path.Direction;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;


public class SVGParserRenderer extends DefaultHandler {

	private class Arc {
		float	angleExtent;


		float	angleStart;


		String	animId;


		RectF	bounds;


		public Arc(final RectF bounds, final float angleStart, final float angleExtent, final String animId) {
			this.bounds = bounds;
			this.angleStart = angleStart;
			this.angleExtent = angleExtent;
			this.animId = animId;
		}
	}


	private enum Attr {
			cx,
			cy,
			d,
			fill,
			fill_opacity,
			font_family,
			font_size,
			fx,
			fy,
			gradientTransform,
			height,
			href,
			id,
			novalue,
			opacity,
			points,
			r,
			stroke,
			stroke_fill,
			stroke_opacity,
			stroke_width,
			style,
			text_align,
			transform,
			width,
			x,
			x1,
			x2,
			y,
			y1,
			y2;

		public static Attr toAttr(final String str) {
			try {
				return valueOf(str.replace('-', '_'));
			}
			catch (final Exception e) {
				return novalue;
			}
		}
	}


	/**
	 * Class to encapsulate a gradient.
	 * Alternatively we could have a map of Shader, with the key being the ID.
	 * A Gradient object is made up in several stages: initially a Gradient is created
	 * and used to store any information from attributes within the <linearGradient> or
	 * <radialGradient> start tag. We may then have child elements such as <stop> which add
	 * further information like stop colours to the current gradient.
	 */

	private class Gradient {

		String				href		= null;


		String				id;


		boolean				isRadial	= false;


		Matrix				matrix;


		Shader				shader;


		// Using a simple array might be better
		ArrayList<Integer>	stopColours	= new ArrayList<Integer>(); // Could be a member of Gradient


		float				x1, y1, x2, y2, cx, cy, radius;


		public Gradient() {

		}


		public void setCoordinates(final float x1, final float y1, final float x2, final float y2) {
			this.x1 = x1;
			this.y1 = y1;
			this.x2 = x2;
			this.y2 = y2;
		}

	}


	/**
	 * Class that stores the current positions in all of the list structures when parsing begins
	 * for a given group element in the SVG file.
	 * 
	 * @author trev
	 */
	private class GroupJumpTo {
		int	arcsListPosition;


		// TODO: It would be much better I think if this object were to contain all code to
		// perform the 'jump to'. So, it could have a method jumpTo, which could accept the
		// group ID to jump to. Perhaps it could all be static and contain the jumpTo map within?
		int	bytecodePosition;


		int	idstringListPosition;


		int	matrixListPosition;


		int	pathListPosition;


		int	styleListPosition;


		int	textstringListPosition;


		public GroupJumpTo(final int bytecodePosition, final int pathListPosition,
				final int matrixListPosition, final int styleListPosition,
				final int textstringListPosition, final int idstringListPosition,
				final int arcsListPosition) {
			this.bytecodePosition = bytecodePosition;
			this.pathListPosition = pathListPosition;
			this.matrixListPosition = matrixListPosition;
			this.styleListPosition = styleListPosition;
			this.textstringListPosition = textstringListPosition;
			this.idstringListPosition = idstringListPosition;
			this.arcsListPosition = arcsListPosition;
		}
	}


	public static interface ILogSVGTiming {
		public void logSvgTiming(String path, long time_ms);
	}


	/**
	 * Used for parsing the 'd' attribute in a 'path' element. Is subtly different to ValueTokenizer
	 * in the following ways:
	 * 1. Individual letters (a-zA-Z) are tokenized rather than complete strings.
	 * 2. (And possibly more differences in future)
	 * This class could in theory be merged with ValueTokenizer and an argument flag implemented to
	 * control whether alpha characters are treated as individual tokens or string tokens, or could
	 * remain a separate class but could extend ValueTokenizer.
	 */

	private class PathTokenizer {

		private static final int	LTOK_END		= 5;


		private static final int	LTOK_LETTER		= 3;


		private static final int	LTOK_NUM_EXPO	= 9991;


		private static final int	LTOK_NUMBER		= 1;


		private static final int	LTOK_SPACE		= 4;


		private static final String	REGEXP_LETTER	= "([a-zA-Z_])";																		// Matches ONE character.


		// private static final String REGEXP_NUMBER = "([-+]?[0-9]*[\\.]?[0-9]+[eE]?[-]?[0-9]*)"; // [eE]?[-+]?[0-9]+
		// private static final String REGEXP_NUM_EXPO = "([-+]?[0-9]*[\\.]?[0-9]+[eE][-]?[0-9]+)";
		// private static final String REGEXP_NUMBER = "([-+]?[0-9]*[\\.]?[0-9]+)";
		private static final String	REGEXP_NUMBER	= "([-+]?[0-9]*[\\.]?[0-9]+([eE][-+]?[0-9]+)?)";


		private static final String	REGEXP_SPACE	= "([\\s+,\\(\\)]+)";


		private static final String	REGEXP_TOKENS	= /* REGEXP_NUM_EXPO + "|" + */REGEXP_NUMBER + "|" + REGEXP_LETTER + "|" + REGEXP_SPACE;


		private int					currentTok;


		private char				tokenChar;


		private float				tokenF;


		private String				tokenStr;


		private Matcher				tokMatcher;


		// TODO: Ensure that this Pattern only has to be compiled once.
		private final Pattern		tokRegExp		= Pattern.compile(REGEXP_TOKENS);


		int getToken(final String inputLine) {

			int resultTok = LTOK_END;

			if (inputLine != null) {
				tokMatcher = tokRegExp.matcher(inputLine);
			}

			if (tokMatcher != null) {

				do {
					resultTok = LTOK_END;
					if (tokMatcher.find()) {

						if (tokMatcher.start(LTOK_NUMBER) != -1) {
							resultTok = LTOK_NUMBER;
							try {
								tokenF = Float.parseFloat(tokMatcher.group(resultTok));

							}
							catch (final NumberFormatException e_i) {
								// Log.e(LOGTAG, "Number not parsed to float" );
							}
							break;

						}
						else if (tokMatcher.start(LTOK_LETTER) != -1) {
							resultTok = LTOK_LETTER;
							tokenStr = tokMatcher.group(resultTok);
							tokenChar = tokenStr.charAt(0);
						}
						else if (tokMatcher.start(LTOK_SPACE) != -1) {
							resultTok = LTOK_SPACE;
						}
					}
				}
				while (resultTok == LTOK_SPACE);
			}
			currentTok = resultTok;
			return resultTok;
		}

	}


	private class Properties {

		float		cx;


		float		cy;


		float		height;


		String		id;


		String		pathData;


		String		pointsData;


		float		radius;


		String		styleData;


		SvgStyle	svgStyle;


		String		transformData;


		float		width;


		float		x;


		float		x1;


		float		x2;


		String		xlink_href;


		float		y;


		float		y1;


		float		y2;
	}


	/**
	 * Style class holds the stroke and fill Paint objects for each path.
	 * It could later on also hold the Path too.
	 * It could also contain the Matrix. Such Matrix and Paint objects can always be null
	 * if not required, so there shouldn't be any memory penalty.
	 * Style class could be a subclass of e.g. svgPath class.
	 */
	public static class SvgStyle {

		Paint	fillPaint;


		boolean	hasFill;


		boolean	hasStroke;


		public float	masterOpacity, fillOpacity, strokeOpacity;


		Paint			strokePaint;


		/**
		 * Create a new SvgStyle object with all the default initial values applied in accordance with SVG
		 * standards.
		 * Useful reference for style initial properties and inheritance rules:
		 * http://www.w3.org/TR/SVG/painting.html
		 * TODO: Still need to ensure that all properties are to the correct initial values.
		 */
		public SvgStyle() {

			fillPaint = new Paint();
			strokePaint = new Paint();
			fillPaint.setStyle(Paint.Style.FILL);
			strokePaint.setStyle(Paint.Style.STROKE);
			fillPaint.setColor(0xff000000);
			strokePaint.setColor(0xff000000);
			masterOpacity = 1;
			fillOpacity = 1;
			strokeOpacity = 1;
			fillPaint.setAntiAlias(true);
			strokePaint.setAntiAlias(true);
			fillPaint.setStrokeWidth(1f);
			strokePaint.setStrokeWidth(1f);
			fillPaint.setTextAlign(Paint.Align.LEFT);
			strokePaint.setTextAlign(Paint.Align.LEFT);
			fillPaint.setTextSize(0.02f);
			strokePaint.setTextSize(0.02f);
			fillPaint.setTextScaleX(1f);
			strokePaint.setTextScaleX(1f);
			fillPaint.setTypeface(Typeface.DEFAULT);
			strokePaint.setTypeface(Typeface.DEFAULT);
			hasFill = true;
			hasStroke = false;
		}


		/**
		 * Constructor that accepts an existing SvgStyle object to copy. It is this method that defines
		 * what style properties an element inherits from the parent, or are not inherited and are defaulted
		 * to some value. It is provided so that a stack of
		 * SvgStyle can be maintained, with each child inheriting from the parent initially but with
		 * various properties overridden afterwards whenever a graphical element explicitly provides style
		 * properties. It is this method that determines what inherits and what doesn't -- if any style must
		 * always default to something rather than inherit, then this method should set that property
		 * to an explicit value.
		 * 
		 * @param s
		 */
		public SvgStyle(final SvgStyle s) {

			this.fillPaint = new Paint(s.fillPaint);
			this.strokePaint = new Paint(s.fillPaint);
			this.fillPaint.setStyle(Paint.Style.FILL);
			this.strokePaint.setStyle(Paint.Style.STROKE);
			this.fillPaint.setColor(s.fillPaint.getColor());
			this.strokePaint.setColor(s.strokePaint.getColor());
			this.masterOpacity = s.masterOpacity;
			this.fillOpacity = s.fillOpacity;
			this.strokeOpacity = s.strokeOpacity;
			this.fillPaint.setAntiAlias(true);
			this.strokePaint.setAntiAlias(true);
			this.fillPaint.setStrokeWidth(1f);
			this.strokePaint.setStrokeWidth(1f);
			this.fillPaint.setTextAlign(s.fillPaint.getTextAlign());
			this.strokePaint.setTextAlign(s.strokePaint.getTextAlign());
			this.fillPaint.setTextSize(s.fillPaint.getTextSize());
			this.strokePaint.setTextSize(s.strokePaint.getTextSize());
			this.fillPaint.setTextScaleX(s.fillPaint.getTextScaleX());
			this.strokePaint.setTextScaleX(s.strokePaint.getTextScaleX());
			this.fillPaint.setTypeface(Typeface.DEFAULT);
			this.strokePaint.setTypeface(Typeface.DEFAULT);
			this.hasFill = s.hasFill;
			this.hasStroke = s.hasStroke;

		}

	}


	/**
	 * Represents a single line of text. Encapsulates data parsed from the SVG file needed
	 * to render the text to Canvas.
	 * 
	 * @author trev
	 */
	public class Textstring {
		public char[]			charBuf;


		public int				charLength;


		public StringBuilder	string;


		// TODO: Require an index into either a list of Strings or a single large char[] buffer.
		// The char[] buffer could be contained within this object - perhaps that makes most sense.
		public final float		x, y;


		public Textstring(final float x, final float y, final char[] src, final int srcPos, final int length) {
			this.x = x;
			this.y = y;
			this.charLength = length;
			this.charBuf = new char[length + 1];
			System.arraycopy(src, srcPos, this.charBuf, 0, length);
			this.charBuf[length] = 0;
			this.string = new StringBuilder();
			this.string.append(src, srcPos, length);

		}
	}


	/**
	 * Is used for tokenizing attribute values that contain a number of strings or numbers. Typical
	 * examples are:
	 * 1. Parsing the 'transform' attribute value.
	 * 2. Parsing the 'rgb(r,g,b) colour value.
	 * The types of token returned are LTOK_NUMBER and LTOK_STRING. Characters such as comma
	 * and parentheses are considered whitespace for this purpose and are ignored. For example, the
	 * string rgb(10,20,30) would result in an LTOK_STRING where tokenStr is "abc", followed by
	 * three LTOK_NUMBERS where tokenF is 10, 20 and then 30.
	 */

	private class ValueTokenizer {

		private static final int	LTOK_END		= 4;


		private static final int	LTOK_NUMBER		= 1;


		private static final int	LTOK_SPACE		= 3;


		private static final int	LTOK_STRING		= 2;


		private static final String	REGEXP_NUMBER	= "([-+]?[0-9]*[\\.]?[0-9]+)";


		private static final String	REGEXP_SPACE	= "([\\s+,\\(\\)]+)";


		private static final String	REGEXP_STRING	= "([a-zA-Z_]+)";											// Matches a string


		private static final String	REGEXP_TOKENS	= REGEXP_NUMBER + "|" + REGEXP_STRING + "|" + REGEXP_SPACE;


		private int					currentTok;


		private float				tokenF;


		private String				tokenStr;


		private Matcher				tokMatcher;


		// TODO: Ensure that this Pattern only has to be compiled once.
		private final Pattern		tokRegExp		= Pattern.compile(REGEXP_TOKENS);


		int getToken(final String inputLine) {

			int resultTok = LTOK_END;

			if (inputLine != null) {
				tokMatcher = tokRegExp.matcher(inputLine);
			}

			if (tokMatcher != null) {

				do {
					resultTok = LTOK_END;
					if (tokMatcher.find()) {

						if (tokMatcher.start(LTOK_NUMBER) != -1) {
							resultTok = LTOK_NUMBER;
							try {
								tokenF = Float.parseFloat(tokMatcher.group(resultTok));

							}
							catch (final NumberFormatException e_i) {
								// Log.e(LOGTAG, "Number not parsed to float" );
							}
							break;

						}
						else if (tokMatcher.start(LTOK_STRING) != -1) {
							resultTok = LTOK_STRING;
							tokenStr = tokMatcher.group(resultTok);
						}
						else if (tokMatcher.start(LTOK_SPACE) != -1) {
							resultTok = LTOK_SPACE;
						}
					}
				}
				while (resultTok == LTOK_SPACE);
			}
			currentTok = resultTok;
			return resultTok;
		}

	}


	private static final String	ASSETS_FONTS_ROOT_DIRECTORY	= "fonts";


	static float				currentX;


	static float				currentY;


	private static final byte	INST_ARC					= 8;


	private static final byte	INST_BEGINGROUP				= 3;


	/* Bytecode instruction set */
	private static final byte	INST_END					= 0;


	private static final byte	INST_ENDGROUP				= 4;


	private static final byte	INST_IDSTRING				= 7;


	private static final byte	INST_MATRIX					= 2;


	private static final byte	INST_PATH					= 1;


	private static final byte	INST_STYLE					= 5;


	private static final byte	INST_TEXTSTRING				= 6;


	static float				lastControlPointX			= 0;


	static float				lastControlPointY			= 0;


	public static ILogSVGTiming	LOG_TIMING					= null;


	private static final String	LOGTAG						= "SVGParserRenderer";


	private static final String	SPECIAL_ID_PREFIX_ANIM		= "_anim";


	private static final String	SPECIAL_ID_PREFIX_ARCPARAMS	= "_arcparams";


	private static final String	SPECIAL_ID_PREFIX_META		= "_meta";


	private static final String	STARTTAG_CIRCLE				= "circle";


	private static final String	STARTTAG_DEFS				= "defs";


	private static final String	STARTTAG_G					= "g";


	private static final String	STARTTAG_LINE				= "line";


	private static final String	STARTTAG_LINEARGRADIENT		= "linearGradient";


	private static final String	STARTTAG_PATH				= "path";


	private static final String	STARTTAG_POLYGON			= "polygon";


	private static final String	STARTTAG_RADIALGRADIENT		= "radialGradient";


	private static final String	STARTTAG_RECT				= "rect";


	private static final String	STARTTAG_STOP				= "stop";


	/* XML tags */
	private static final String	STARTTAG_SVG				= "svg";


	private static final String	STARTTAG_TEXT				= "text";


	private static final String	STARTTAG_TEXTPATH			= "textPath";


	private static final String	STARTTAG_TSPAN				= "tspan";


	/**
	 * Parse a basic data type of type <coordinate> or <length>.
	 * length ::= number ("em" | "ex" | "px" | "in" | "cm" | "mm" | "pt" | "pc" | "%")?
	 * Relevant parts of the SVG specification include:
	 * http://www.w3.org/TR/SVG/types.html#BasicDataTypes
	 * This method may be expanded later to handle some of the other basic SVG data types that use a
	 * similar syntax, e.g. <angle>.
	 * Limitations of this method:
	 * It's assumed for now that values will have either no units specified and can be assumed 'px',
	 * or will otherwise have 'px' unit identifier explicitly stated.
	 * Support for other units such as pt, pc, etc. will be added later when deemed necessary.
	 * Either some automatic translation could be done into the px space, or alternatively investigate
	 * whether Android's vector graphics functions can be made to accept units of different systems.
	 * For convenience, this method may or may not also handle the stripping of quotation marks
	 * from the value string - this is TBD.
	 */
	private static float parseCoOrdinate(String value) {
		float result = 0f;

		// Quick and dirty way to determine if the value appears to have a units suffix.
		if (value.charAt(value.length() - 1) >= 'a') {
			if (value.endsWith("px")) {
				value = value.substring(0, value.length() - 2);
			}
			else {
				// TODO: Add support in future for other units here.
				// TODO: Call our error reporting function.
			}
		}
		try {
			result = Float.parseFloat(value);
		}
		catch (final NumberFormatException nfe) {

		}
		return result;
	}


	ArrayList<Arc>							arcsList				= new ArrayList<Arc>();


	Iterator<Arc>							arcsListIterator;


	private byte[]							bytecodeArr;													// Holds the complete bytecode for an SVG image once parsed.


	private ArrayList<Byte>					bytecodeList;													// Expandable list used for initial creation of bytecode from parsing.


	private int								codePtr;


	Paint									currentFillPaint		= new Paint();


	private Gradient						currentGradient			= new Gradient();


	Paint									currentStrokePaint		= new Paint();


	private final boolean					ga_debug;


	ArrayList<Gradient>						gradientList			= new ArrayList<Gradient>();


	ArrayList<String>						idstringList			= new ArrayList<String>();


	Iterator<String>						idstringListIterator;


	private final Stack<Matrix>				matrixEvStack			= new Stack<Matrix>();					// Used for chaining transformations on nested nodes.


	private final boolean[]					matrixExistsAtDepth		= new boolean[20];


	// TODO:
	// Possibly use sequence like
	// new SVGParserRenderer(...).setApplicationNamespace(...).render();

	/*
	 * public SVGParserRenderer(Context context, int resourceID, Map<String, String> metaDataQueryMap) {
	 * //mMetaDataQueryMap = new HashMap<String, String>();
	 * mMetaDataQueryMap = metaDataQueryMap;
	 * parseImageFile(context, resourceID);
	 * }
	 * public SVGParserRenderer(Context context, File sourceFile, Map<String, String> metaDataQueryMap) throws FileNotFoundException {
	 * //mMetaDataQueryMap = new HashMap<String, String>();
	 * mMetaDataQueryMap = metaDataQueryMap;
	 * parseImageFile(context, sourceFile);
	 * }
	 * public SVGParserRenderer(Context context, InputStream sourceStream, Map<String, String> metaDataQueryMap) {
	 * //mMetaDataQueryMap = new HashMap<String, String>();
	 * mMetaDataQueryMap = metaDataQueryMap;
	 * parseImageFile(context, sourceStream);
	 * }
	 */

	// Lists and stacks for executable code
	ArrayList<Matrix>						matrixList				= new ArrayList<Matrix>();


	// Used by code evaluator:
	Iterator<Matrix>						matrixListIterator;


	float[]									matrixValues			= new float[9];


	private Context							mContext;


	private String							mCurrentElement;


	private String							mPrivateDataCurrentKey;


	private final HashMap<String, String>	mPrivateDataMap;


	private String							mPrivateDataNamespace	= "msdroid:";


	private Properties						mProperties;


	private float							mRootSvgHeight			= 100;


	private float							mRootSvgWidth			= 100;


	// private Typeface ttfFont1;
	private final Stack<SvgStyle>			mStyleParseStack		= new Stack<SvgStyle>();


	ArrayList<Paint>						paintStack				= new ArrayList<Paint>();


	ArrayList<Path>							pathList				= new ArrayList<Path>();


	Iterator<Path>							pathListIterator;


	ArrayList<SvgStyle>						styleList				= new ArrayList<SvgStyle>();


	Iterator<SvgStyle>						styleListIterator;


	HashMap<String, GroupJumpTo>			subtreeJumpMap			= new HashMap<String, GroupJumpTo>();


	// Data structures used during parsing
	private int								tagDepth;


	ArrayList<Textstring>					textstringList			= new ArrayList<Textstring>();


	Iterator<Textstring>					textstringListIterator;


	Matrix									workingMatrix			= new Matrix();


	public SVGParserRenderer() {
		mPrivateDataMap = new HashMap<String, String>();
		ga_debug = false;
	}


	public SVGParserRenderer(final Context context, final File sourceFile) throws FileNotFoundException {
		mPrivateDataMap = new HashMap<String, String>();
		ga_debug = isDebug(context);

		parseImageFile(context, sourceFile);
	}


	public SVGParserRenderer(final Context context, final InputStream sourceStream) {
		mPrivateDataMap = new HashMap<String, String>();
		ga_debug = isDebug(context);

		parseImageFile(context, sourceStream);
	}


	public SVGParserRenderer(final Context context, final int resourceID) {
		mPrivateDataMap = new HashMap<String, String>();
		ga_debug = isDebug(context);

		parseImageFile(context, resourceID);
	}


	public void ___setAnimHandler(final ITpsvgController animHandler) {
		// this.animHandler = animHandler;
		// parseImageFile(context,resourceID);
	}


	private void addArc(final Arc arc) {
		// addIdIfContainsSpecialPrefix();// At the moment, Arc itself contains the ID
		arcsList.add(arc);
		addInstruction(INST_ARC);
	}


	private void addBeginGroup(final String id) {
		addIdIfContainsSpecialPrefix();
		// All groups need to have Matrix added before them, even if empty Matrix
		addTransform();
		addInstruction(INST_BEGINGROUP);
		// Did the attributes for this <g> element include an id= attribute?
		if (id != "") {

			subtreeJumpMap.put(id, new GroupJumpTo(bytecodeList.size() - 2, pathList.size(),
					matrixList.size() - 1, styleList.size(),
					textstringList.size(), idstringList.size(),
					arcsList.size()));

		}
	}


	private void addEndGroup() {
		addInstruction(INST_ENDGROUP);
	}


	private void addIdIfContainsSpecialPrefix() {
		if (mProperties.id.toLowerCase().startsWith(SPECIAL_ID_PREFIX_ANIM)) {
			idstringList.add(mProperties.id);
			addInstruction(INST_IDSTRING);
		}
	}


	private void addInstruction(final byte inst) {
		bytecodeList.add(inst);
	}


	private void addPath(final Path p) {
		// This may well have a lot more arguments for stuff that's specific to the path
		// i.e. contained within the <path.../> element but can't be expressed in the Path
		// object, e.g. its ID.
		addIdIfContainsSpecialPrefix();
		// TODO: Should we be doing this check? What if the evaluator expects there to be a
		// Style object?
		if (this.mProperties.svgStyle != null) {
			addStyle();
		}

		// The transform MUST be the item that goes immediately before the path in the instruction
		// code sequence, as the evaluator expects this. So the order is style, transform, path.

		// TODO: Previously I only added a Matrix for a Path if that Path has a transform. However,
		// this means that any transform belonging specifically to a previous path would still
		// be applied. So at the moment I have to add a Matrix for every Path, which might
		// be wasteful as most Paths might have an identity Matrix (no transform). One
		// way of optimising this could be to add an identity tranform ONLY if the previous
		// path had a non-identity transform.
		// (I think what I meant by this is when you have two path elements in parallel, not nested.)
		// (Or maintain a matrix stack in the evaluator.)
		// Note: The parser uses a Matrix stack. The evaluator uses a Matrix list.

		// if(this.mProperties.transformData!=null){
		addTransform();
		// }

		this.pathList.add(p);
		addInstruction(INST_PATH);
	}


	private void addStyle() {
		this.styleList.add(mProperties.svgStyle);
		// styleList.add(styleParseStack.peek());
		addInstruction(INST_STYLE);
	}


	private void addText() {
		addIdIfContainsSpecialPrefix();
		addInstruction(INST_TEXTSTRING);
	}


	private void addTransform() {
		// this.matrixList.add(transform());
		final Matrix cm = transform(); // matrixList.get(matrixList.size()-1);

		// new:
		if (!matrixEvStack.empty()) {
			cm.postConcat(matrixEvStack.peek());
		}
		matrixEvStack.push(cm);

		this.matrixList.add(matrixEvStack.peek());
		matrixExistsAtDepth[tagDepth] = true;
		addInstruction(INST_MATRIX);
	}


	/**
	 * This method converts an SVG arc to an Android Canvas arc.
	 * Based on an example found on StackOverflow which in turn was based on:
	 * http://www.java2s.com/Code/Java/2D-Graphics-GUI/AgeometricpathconstructedfromstraightlinesquadraticandcubicBeziercurvesandellipticalarc.htm
	 * The example initially used turned out to have an error with the coef calculation, so it is a TODO to revisit this.
	 * 
	 * @param path
	 * @param rx
	 * @param ry
	 * @param theta
	 * @param largeArcFlag
	 * @param sweepFlag
	 * @param x
	 * @param y
	 */

	public/* static */final void arcTo(final Path path, float rx, float ry, float theta, final boolean largeArcFlag, final boolean sweepFlag, final float x, final float y) {
		// Ensure radii are valid
		if (rx == 0 || ry == 0) {
			path.lineTo(x, y);
			return;
		}
		// Get the current (x, y) coordinates of the path
		// Point2D p2d = path.getCurrentPoint();

		final float x0 = currentX; // (float) p2d.getX();
		final float y0 = currentY; // (float) p2d.getY();
		// Compute the half distance between the current and the final point
		final float dx2 = (x0 - x) / 2.0f;
		final float dy2 = (y0 - y) / 2.0f;
		// Convert theta from degrees to radians
		theta = (float) Math.toRadians(theta % 360f);

		//
		// Step 1 : Compute (x1, y1)
		//
		final float x1 = (float) (Math.cos(theta) * dx2 + Math.sin(theta)
				* dy2);
		final float y1 = (float) (-Math.sin(theta) * dx2 + Math.cos(theta)
				* dy2);
		// Ensure radii are large enough
		rx = Math.abs(rx);
		ry = Math.abs(ry);
		float Prx = rx * rx;
		float Pry = ry * ry;
		final float Px1 = x1 * x1;
		final float Py1 = y1 * y1;
		final double d = Px1 / Prx + Py1 / Pry;
		if (d > 1) {
			rx = Math.abs((float) (Math.sqrt(d) * rx));
			ry = Math.abs((float) (Math.sqrt(d) * ry));
			Prx = rx * rx;
			Pry = ry * ry;
		}

		//
		// Step 2 : Compute (cx1, cy1)
		//
		double sign = largeArcFlag == sweepFlag ? -1d : 1d;

		// float coef = (float) (sign * Math
		// .sqrt(((Prx * Pry) - (Prx * Py1) - (Pry * Px1))
		// / ((Prx * Py1) + (Pry * Px1))));

		double sq = (Prx * Pry - Prx * Py1 - Pry * Px1)
				/ (Prx * Py1 + Pry * Px1);

		sq = sq < 0 ? 0 : sq;
		final float coef = (float) (sign * Math.sqrt(sq));

		final float cx1 = coef * (rx * y1 / ry);
		final float cy1 = coef * -(ry * x1 / rx);

		//
		// Step 3 : Compute (cx, cy) from (cx1, cy1)
		//
		final float sx2 = (x0 + x) / 2.0f;
		final float sy2 = (y0 + y) / 2.0f;
		final float cx = sx2
				+ (float) (Math.cos(theta) * cx1 - Math.sin(theta)
						* cy1);
		final float cy = sy2
				+ (float) (Math.sin(theta) * cx1 + Math.cos(theta)
						* cy1);

		//
		// Step 4 : Compute the angleStart (theta1) and the angleExtent (dtheta)
		//
		final float ux = (x1 - cx1) / rx;
		final float uy = (y1 - cy1) / ry;
		final float vx = (-x1 - cx1) / rx;
		final float vy = (-y1 - cy1) / ry;
		float p, n;
		// Compute the angle start
		n = (float) Math.sqrt(ux * ux + uy * uy);
		p = ux; // (1 * ux) + (0 * uy)
		sign = uy < 0 ? -1d : 1d;
		float angleStart = (float) Math.toDegrees(sign * Math.acos(p / n));
		// Compute the angle extent
		n = (float) Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy));
		p = ux * vx + uy * vy;
		sign = ux * vy - uy * vx < 0 ? -1d : 1d;
		float angleExtent = (float) Math.toDegrees(sign * Math.acos(p / n));
		if (!sweepFlag && angleExtent > 0) {
			angleExtent -= 360f;
		}
		else if (sweepFlag && angleExtent < 0) {
			angleExtent += 360f;
		}
		angleExtent %= 360f;
		angleStart %= 360f;

		// Arc2D.Float arc = new Arc2D.Float();
		final float _x = cx - rx;
		final float _y = cy - ry;
		final float _width = rx * 2.0f;
		final float _height = ry * 2.0f;

		final RectF bounds = new RectF(_x, _y, _x + _width, _y + _height);

		// if(animHandler!=null){
		// animHandler.arcParams(mProperties.id, path, angleStart, angleExtent, bounds);
		//
		// }
		// else{
		// path.addArc(bounds, angleStart, angleExtent);
		addArc(new Arc(bounds, angleStart, angleExtent, mProperties.id));
		// }
	}


	public int bytecodeArrSize() {
		return null == bytecodeArr ? 0 : bytecodeArr.length;
	}


	@Override
	public void characters(final char[] ch, final int start, final int length) throws SAXException {
		super.characters(ch, start, length);

		if (mCurrentElement.equalsIgnoreCase(STARTTAG_TEXT)) {
			text_characters(ch, start, length);
		}

		else if (mCurrentElement.equalsIgnoreCase(STARTTAG_TSPAN)) {
			tspan_characters(ch, start, length);
		}

		else if (mPrivateDataCurrentKey != null) {
			mPrivateDataMap.put(mPrivateDataCurrentKey, new String(ch, start, length));
		}

	}


	private void circle() {
		final Path p = new Path();
		p.addCircle(mProperties.cx, mProperties.cy, mProperties.radius, Direction.CW);
		currentX = mProperties.cx;
		currentY = mProperties.cy;
		addPath(p);
	}


	/**
	 * Is called when all of the SVG XML file has been parsed. It performs cross-referencing and
	 * copying of data due to xlink:hrefs. For example, LinearGradient and RadialGradient use
	 * xlink:href a lot because gradients often reference stop colours from another gradient ID.
	 * This task has to be performed after parsing and not during because it's possible to have
	 * both backward and forward references.
	 * Actually - this is now called when the 'defs' close tag is encountered. The gradients need
	 * to be all fully set up before we start generating paths! This assumes for now that there's
	 * only one defs block... which is bound to be a wrong thing to assume. Also, it's probably
	 * wrong to assume that a defs block can't occur again in the document. Something to be careful
	 * of and improve later.
	 * We might need to implement pointers for things like gradientList to keep tabs on which ones
	 * were newly added by a given defs block.
	 * Also, xrefs between gradients could be at a greater depth than 1, which means that we'd need
	 * to do multiple passes over the list.
	 */
	private void completeHrefs() {
		// Process the gradients
		final Iterator<Gradient> gi = gradientList.iterator();
		Gradient g;
		int idx;
		while (gi.hasNext()) {
			g = gi.next();
			if (g.href != null) {
				idx = this.getGradientByIdString(g.href.substring(1));
				if (idx != -1) {
					final Gradient xlinkGrad = this.gradientList.get(idx);
					g.stopColours = xlinkGrad.stopColours;

				}

				// TODO: Can't we just insert an object reference to the same shader?

				final int[] ia = new int[g.stopColours.size()];

				for (int i = 0; i < ia.length; i++) {
					ia[i] = g.stopColours.get(i);
				}

				if (g.isRadial) {
					g.shader = new RadialGradient(
							g.cx,
							g.cy,
							g.radius,
							ia,
							null,
							Shader.TileMode.CLAMP
							);
				}
				else { // linear
					g.shader = new LinearGradient(
							g.x1,
							g.y1,
							g.x2,
							g.y2,
							ia,
							null,
							Shader.TileMode.CLAMP
							);
				}

				// The shader needs to have a matrix even if no transform was specified in the attributes
				// for the gradient. This is because the gradient's Matrix, even if 'empty', is needed
				// to concatenate the current cumulative transform to during evaluation/drawing.
				if (g.matrix != null) {
					g.shader.setLocalMatrix(g.matrix);
				}
				else {
					g.shader.setLocalMatrix(new Matrix());
				}

			}

		}

	}


	@Override
	public void endElement(final String uri, final String localName, final String name)
			throws SAXException {
		super.endElement(uri, localName, name);

		mPrivateDataCurrentKey = "";
		mCurrentElement = "";

		if (localName.equalsIgnoreCase(STARTTAG_G)) {
			addEndGroup();
		}

		else if (localName.equalsIgnoreCase(STARTTAG_LINEARGRADIENT)) {
			finaliseLinearGradient();
		}
		else if (localName.equalsIgnoreCase(STARTTAG_RADIALGRADIENT)) {
			finaliseRadialGradient();
		}
		else if (localName.equalsIgnoreCase(STARTTAG_DEFS)) {
			completeHrefs();
		}

		tagDepth--;
		if (matrixExistsAtDepth[tagDepth]) {
			this.matrixEvStack.pop();
		}

		mStyleParseStack.pop();
	}


	private void finaliseLinearGradient() {

		// Only apply a shader now if this gradient element itself provided stop colours in its
		// attributes. If it didn't, then hopefully it references another gradient's colours via a href
		// attribute, in which case the cross-referencing will be done later.

		if (currentGradient.stopColours.size() > 0) {
			final int[] ia = new int[currentGradient.stopColours.size()];

			for (int i = 0; i < ia.length; i++) {
				ia[i] = currentGradient.stopColours.get(i);
			}

			currentGradient.shader = new LinearGradient(
					currentGradient.x1,
					currentGradient.y1,
					currentGradient.x2,
					currentGradient.y2,
					ia,
					null,
					Shader.TileMode.CLAMP
					);

			// The shader needs to have a matrix even if no transform was specified in the attributes
			// for the gradient. This is because the gradient's Matrix, even if 'empty', is needed
			// to concatenate the current cumulative transform to during evaluation/drawing.
			if (currentGradient.matrix != null) {
				currentGradient.shader.setLocalMatrix(currentGradient.matrix);
			}
			else {
				currentGradient.shader.setLocalMatrix(new Matrix());
			}

		}

		currentGradient.isRadial = false;
		gradientList.add(currentGradient);
		currentGradient = new Gradient();

	}


	// The valueTokenizer is used for:
	// - parsing 'transform' attribute string
	// - parsing rgb(r,g,b) colour string

	private void finaliseRadialGradient() {

		if (currentGradient.stopColours.size() > 0) {

			final int[] ia = new int[currentGradient.stopColours.size()];

			for (int i = 0; i < ia.length; i++) {
				ia[i] = currentGradient.stopColours.get(i);
			}

			currentGradient.shader = new RadialGradient(
					currentGradient.cx,
					currentGradient.cy,
					currentGradient.radius,
					ia,
					null,
					Shader.TileMode.CLAMP
					);

			// The shader needs to have a matrix even if no transform was specified in the attributes
			// for the gradient. This is because the gradient's Matrix, even if 'empty', is needed
			// to concatenate the current cumulative transform to during evaluation/drawing.
			if (currentGradient.matrix != null) {
				currentGradient.shader.setLocalMatrix(currentGradient.matrix);
			}
			else {
				currentGradient.shader.setLocalMatrix(new Matrix());
			}

		}

		currentGradient.isRadial = true;
		gradientList.add(currentGradient);
		currentGradient = new Gradient();

	}


	/**
	 * Obtain the height specified in the SVG image file. It should be specified in
	 * the image's root svg element.
	 * 
	 * @return
	 */
	public int getDocumentHeight() {
		return Math.round(mRootSvgHeight);
	}


	// ----------------------------------------------------------------------------------
	// Scaling and measurement related methods

	/**
	 * Obtain the width specified in the SVG image file. It should be specified in
	 * the image's root svg element.
	 * 
	 * @return
	 */
	public int getDocumentWidth() {
		return Math.round(mRootSvgWidth);
	}


	/*
	 * Search the gradientList for the Gradient with specified string ID.
	 * Index into the gradientList is returned. If not found, -1 is returned.
	 */
	private int getGradientByIdString(final String searchId) {
		for (int idx = 0; idx < gradientList.size(); idx++) {
			if (gradientList.get(idx).id.equals(searchId)) {
				return idx;
			}
		}
		return -1;
	}


	public String getPrivateDataValue(final String key) {
		return mPrivateDataMap.get(key);
	}


	// -------------------------------------------------------------------------------------
	// Code-sequence build functions

	/**
	 * Obtain a Tyepface from the assets fonts directory for the given name.
	 * The fonts in the assets directory need to be lowercase. The actual font
	 * looked for will be fontFamilyName appended with .ttf.
	 * If the font name contains space characters, the corresponding characters
	 * in the filename of the assets font should be underscores.
	 * 
	 * @param fontFamilyName
	 *            Font name. Will be made lowercase.
	 * @return A Typeface, or null if not found in assets.
	 */
	private Typeface getTypeface(String fontFamilyName) {
		fontFamilyName = fontFamilyName.replace(' ', '_');
		try {
			return Typeface.createFromAsset(mContext.getAssets(),
					ASSETS_FONTS_ROOT_DIRECTORY + "/" +
							fontFamilyName.toLowerCase() + ".ttf");
		}
		catch (final Exception e) {
			return null;
		}

	}


	private void gradientStop() {
		gradientStyle(); // This will add colour to stopColours

	}


	private void gradientStyle() {
		final Map<String, String> map = new HashMap<String, String>();
		parseAttributeValuePairsIntoMap(map);
		String value;
		int stopColour = 0;
		boolean haveStopColour = false;

		if (null != (value = map.get("stop-opacity"))) {
			final float opacity = parseAttrValueFloat(value);
			stopColour |= (int) (opacity * 255) << 24;
			haveStopColour = true;
		}
		else {
			// TODO: If it's possible for gradients, inherit the opacity if it's not explicitly
			// stated in the gradient's attributes.
			stopColour = 0xff000000;
		}

		if (null != (value = map.get("stop-color"))) {
			stopColour |= parseColour(value);
			haveStopColour = true;
		}

		if (haveStopColour) {
			currentGradient.stopColours.add(stopColour);
		}

	}


	private boolean isDebug(final Context context) {
		final Resources resources = context.getResources();
		final int ga_debugId = resources.getIdentifier("ga_debug", "bool", context.getPackageName());
		return Boolean.valueOf(ga_debugId != 0 && resources.getBoolean(ga_debugId));
	}


	private void line() {
		final Path p = new Path();
		p.moveTo(mProperties.x1, mProperties.y1);
		p.lineTo(mProperties.x2, mProperties.y2);
		currentX = mProperties.x2;
		currentY = mProperties.y2;
		addPath(p);
	}


	private void linearGradient() {
		final Gradient g = currentGradient; // new Gradient();
		// TODO: xlink stuff here
		/*
		 * g.setLinear(
		 * mProperties.x1,
		 * mProperties.y1,
		 * mProperties.x2,
		 * mProperties.y2,
		 * );
		 */

		// Note: Cannot assume that the linearGradient element contains the coordinates,
		// or anything for that matter. The spec says that the xlink:href can be used to
		// inherit any properties not defined in this element.

		g.setCoordinates(
				mProperties.x1,
				mProperties.y1,
				mProperties.x2,
				mProperties.y2
				);

		g.id = mProperties.id;

		// Best way to deal with xlink might be to do an entire copy of the referenced
		// Gradient first, and then apply over the top any properties that are
		// explicitly defined for this Gradient.
		if (mProperties.xlink_href != null) {

			// The hrefs have to be dealt with in a second pass, because forward references are possible!

			// It'll have the form #abcd where abcd is the ID
			// int idx = this.getGradientByIdString(mProperties.xlink_href.substring(1));
			// if(idx!=-1){
			// Gradient xlinkGrad = this.gradientList.get(idx);
			// currentGradient.stopColours = xlinkGrad.stopColours;
			// }
			currentGradient.href = mProperties.xlink_href;

		}
		else {
			currentGradient.href = null;
		}

		currentGradient.matrix = transform();
	}


	public void obtainSVGPrivateData(final ITpsvgController controller) {
		final Iterator<Map.Entry<String, String>> it = mPrivateDataMap.entrySet().iterator();
		while (it.hasNext()) {
			final Map.Entry<String, String> pairs = it.next();
			controller.onSVGPrivateData(pairs.getKey(), pairs.getValue());
			// it.remove(); // avoids a ConcurrentModificationException
		}
	}


	public Drawable paintDrawable(final Context context, final float max_height) {
		final int view_height = (int) Math.floor(max_height);
		final int view_width = (int) Math.floor(max_height / mRootSvgHeight * mRootSvgWidth);

		final Bitmap bitmap = Bitmap.createBitmap(view_width, view_height, Bitmap.Config.ARGB_8888);
		final Canvas canvas = new Canvas(bitmap);

		paintImageToCanvas(
				canvas,
				null,
				null,
				view_width,
				view_height,
				false);

		return new BitmapDrawable(bitmap);
	}


	public void paintImage(final Canvas canvas, final String groupNodeId, final View view, final ITpsvgController animHandler) {
		paintImage(canvas, groupNodeId, view, animHandler, false);
	}


	public void paintImage(final Canvas canvas, final String groupNodeId, final View view, final ITpsvgController animHandler, final boolean fill) {

		// animHandler = animHandler;
		final int view_width = view.getWidth();
		final int view_height = view.getHeight();
		// setCanvasScaleToSVG(canvas, view);

		paintImageToCanvas(canvas, groupNodeId, animHandler, view_width, view_height, fill);
	}


	private void paintImageToCanvas(final Canvas canvas, final String groupNodeId, final ITpsvgController animHandler, final int view_width, final int view_height, final boolean fill) {
		float uniformScaleFactor;
		if (fill) {
			uniformScaleFactor = Math.max(view_width / mRootSvgWidth, view_height / mRootSvgHeight);
		}
		else {
			uniformScaleFactor = Math.min(view_width / mRootSvgWidth, view_height / mRootSvgHeight);
		}
		canvas.scale(uniformScaleFactor, uniformScaleFactor);

		final float excessY = view_height / mRootSvgHeight - uniformScaleFactor;
		final float excessX = view_width - uniformScaleFactor * mRootSvgWidth;

		final Path workingPath = new Path();
		final Path carryPath = new Path();
		int gDepth = 1;

		codePtr = 0;
		// TBD: I think that previous Matrix is remaining attached to something, so have to instance
		// a new one each time
		// currentMatrix.reset();
		workingMatrix = new Matrix();
		matrixListIterator = matrixList.listIterator();
		pathListIterator = pathList.listIterator();
		styleListIterator = styleList.listIterator();
		textstringListIterator = textstringList.listIterator();
		idstringListIterator = idstringList.listIterator();
		arcsListIterator = arcsList.iterator();
		boolean doSpecialIdCallbackForNextElement = false;
		int animIteration;
		String animId;
		final Matrix animMatrix = new Matrix();
		Matrix shaderMatrix = new Matrix();

		if (groupNodeId != null) {
			// TODO: It would be better if the GroupJumpTo object did all of this for us, or
			// even better if all the data structures were somehow grouped together into a parent
			// class and this was one of its methods.
			if (subtreeJumpMap.containsKey(groupNodeId)) {
				final GroupJumpTo jumpTo = subtreeJumpMap.get(groupNodeId);
				codePtr = jumpTo.bytecodePosition;// -1;
				matrixListIterator = matrixList.listIterator(jumpTo.matrixListPosition);
				pathListIterator = pathList.listIterator(jumpTo.pathListPosition);
				styleListIterator = styleList.listIterator(jumpTo.styleListPosition);
				textstringListIterator = textstringList.listIterator(jumpTo.textstringListPosition);
				idstringListIterator = idstringList.listIterator(jumpTo.idstringListPosition);
				arcsListIterator = arcsList.listIterator(jumpTo.arcsListPosition);
			}
		}

		// WARNING - currentFillPaint and currentStrokePaint make references to things such as
		// Gradients and their associated shaders.
		// Paint currentFillPaint = new Paint();
		// Paint currentStrokePaint = new Paint();
		// Matrix currentMatrix = new Matrix();
		// SvgStyle currentStyle = null;

		if (bytecodeArr == null) {
			return;
		}

		while (bytecodeArr[codePtr] != INST_END && gDepth > 0) {

			switch (bytecodeArr[codePtr]) {

				case INST_PATH:
					workingPath.rewind();
					workingPath.addPath(pathListIterator.next());
					workingPath.addPath(carryPath);
					workingPath.transform(workingMatrix);
					carryPath.rewind();

					// p = pathListIterator.next();
					// If we assume a Matrix is included for every path, even if it's an empty matrix,
					// then we always pop the matrix on path creation. On the other hand if
					// Matrix is only inserted for some paths then we somehow need to know whether
					// to pop the matrix or just peek at the tip cumulative matrix.
					// Could have a flag:
					// pathHasMatrix -- set if a path instruction immediately follows matrix inst.

					animId = null;
					animIteration = 0;

					do {

						if (doSpecialIdCallbackForNextElement == true) {
							if (animId == null) {
								animId = idstringListIterator.next();
							}
							if (animHandler != null) {
								animMatrix.reset();

								// test - for 9-patch anchor idea
								if (animId.equals("_animanchorright")) {
									animMatrix.postTranslate(excessX, 0);
								}

								doSpecialIdCallbackForNextElement =
										animHandler.animElement(animId, animIteration++, animMatrix, currentStrokePaint, currentFillPaint);
								workingPath.transform(animMatrix);

							}
							else {
								doSpecialIdCallbackForNextElement = false;
							}
						}

						shaderMatrix = null;
						if (currentFillPaint != null) {

							Matrix copyShaderMatrix = null;
							if (currentFillPaint.getShader() != null) {
								shaderMatrix = new Matrix();
								currentFillPaint.getShader().getLocalMatrix(shaderMatrix);
								copyShaderMatrix = new Matrix(shaderMatrix); // Deep copy.
								copyShaderMatrix.postConcat(workingMatrix);
								currentFillPaint.getShader().setLocalMatrix(copyShaderMatrix);
							}

							canvas.drawPath(workingPath, currentFillPaint);
							if (shaderMatrix != null) {
								currentFillPaint.getShader().setLocalMatrix(shaderMatrix); // Restore shader's original Matrix
							}
						}

						if (currentStrokePaint != null) {

							workingMatrix.getValues(matrixValues);
							final float storedStrokeWidth = currentStrokePaint.getStrokeWidth();
							currentStrokePaint.setStrokeWidth(storedStrokeWidth * (Math.abs(matrixValues[Matrix.MSCALE_Y]) + Math.abs(matrixValues[Matrix.MSCALE_X]) / 2));
							// Paint scaledPaint = new Paint(currentStrokePaint);
							// scaledPaint.setStrokeWidth(scaledPaint.getStrokeWidth() * ( ( Math.abs(matrixValues[Matrix.MSCALE_Y]) + Math.abs(matrixValues[Matrix.MSCALE_X]) ) / 2 ) );

							// //float curStrkWidth = scaledPaint.getStrokeWidth();
							// //float newStrkWidth = ( Math.abs(f[Matrix.MSCALE_Y]) + Math.abs(f[Matrix.MSCALE_X]) ) / 2.0f ;
							// //newStrkWidth = curStrkWidth * newStrkWidth;
							// //scaledPaint.setStrokeWidth(newStrkWidth);

							Matrix copyShaderMatrix = null;

							// TODO: Does this block now go after the canvas.drawPath?
							if (currentStrokePaint.getShader() != null) {
								shaderMatrix = new Matrix();
								currentStrokePaint.getShader().getLocalMatrix(shaderMatrix);
								copyShaderMatrix = new Matrix(shaderMatrix); // Deep copy.
								copyShaderMatrix.postConcat(workingMatrix);
								currentStrokePaint.getShader().setLocalMatrix(copyShaderMatrix);
							}

							canvas.drawPath(workingPath, currentStrokePaint);
							currentStrokePaint.setStrokeWidth(storedStrokeWidth);
						}

					}
					while (doSpecialIdCallbackForNextElement == true);
					break;

				case INST_MATRIX:
					workingMatrix = matrixListIterator.next();
					break;

				case INST_BEGINGROUP:
					// Similar notes regarding Matrix for path.
					// If last instruction was a matrix instruction, then set flag:
					// groupHasMatrix
					// Actually, no. Assume a matrix for all groups.
					// Assume that a Matrix instruction went before this one!
					gDepth++;
					break;

				case INST_ENDGROUP:
					gDepth--;
					if (gDepth == 1 && groupNodeId != null) {
						// The loop will now terminate and finish rendering, because the specified SVG
						// image fragment has been drawn.
						gDepth = 0;
					}
					break;

				case INST_STYLE:
					final SvgStyle currentStyle = styleListIterator.next();
					if (currentStyle.hasStroke) {
						// IMPORTANT: Making copy as opposed to a reference. This enables
						// currentStrokePaint to be modified without risk of making changes to
						// things that strokePaint references, e.g. Gradients.
						// Same applies to currentFillPaint.
						// currentStrokePaint = new Paint(s.strokePaint);
						currentStrokePaint = currentStyle.strokePaint;
					}
					else {
						currentStrokePaint = null;
					}
					if (currentStyle.hasFill) {
						// currentFillPaint = new Paint(s.fillPaint);
						currentFillPaint = currentStyle.fillPaint;
					}
					else {
						currentFillPaint = null;
					}
					break;

				case INST_TEXTSTRING:
					final Textstring ts = textstringListIterator.next();
					workingMatrix.getValues(matrixValues);
					// We might have already got the values for currentMatrix before, to save
					// on this operation.
					// Paint scaledPaint = new Paint(currentStrokePaint);
					// scaledPaint.setStrokeWidth(scaledPaint.getStrokeWidth() * ( ( f[Matrix.MSCALE_Y] + f[Matrix.MSCALE_X] ) / 2 ) );

					animId = null;
					animIteration = 0;
					animMatrix.reset();
					do {

						if (doSpecialIdCallbackForNextElement == true) {
							if (animId == null) {
								animId = idstringListIterator.next();
							}
							if (animHandler != null) {
								// animMatrix.reset(); //Matrix animMatrix = new Matrix();
								doSpecialIdCallbackForNextElement =
										animHandler.animTextElement(animId, animIteration++, animMatrix, null, ts, ts.x + matrixValues[Matrix.MTRANS_X], ts.y + matrixValues[Matrix.MTRANS_Y]);
								// p.transform(animMatrix);
							}
							else {
								doSpecialIdCallbackForNextElement = false;
							}
						}

						canvas.save();
						canvas.concat(animMatrix);
						canvas.concat(workingMatrix);

						if (currentStrokePaint != null) {

							canvas.drawText(ts.string, 0, ts.string.length(), ts.x, ts.y, currentStrokePaint);
							// canvas.drawText(ts.string, 0, ts.string.length(), ts.x + matrixValues[Matrix.MTRANS_X], ts.y + matrixValues[Matrix.MTRANS_Y], currentStrokePaint);
						}
						if (currentFillPaint != null) {

							canvas.drawText(ts.string, 0, ts.string.length(), ts.x, ts.y, currentFillPaint);
							// canvas.drawText(ts.string, 0, ts.string.length(), ts.x + matrixValues[Matrix.MTRANS_X], ts.y + matrixValues[Matrix.MTRANS_Y], currentFillPaint);

						}

						canvas.restore();
					}
					while (doSpecialIdCallbackForNextElement == true);

					break;

				case INST_IDSTRING:
					doSpecialIdCallbackForNextElement = true;
					break;

				case INST_ARC:
					final Arc arc = arcsListIterator.next();
					// Path path = new Path();
					if (animHandler != null) {
						animHandler.arcParams(arc.animId, carryPath, arc.angleStart, arc.angleExtent, arc.bounds);
					}
					else {
						carryPath.addArc(arc.bounds, arc.angleStart, arc.angleExtent);
					}
					break;

			}
			codePtr++;

		}
	}


	// ------------------------------------------------------------------------------
	// Associated with user's handler

	private void parseAttributes(final Attributes attributes) {

		String v;

		mProperties.transformData = null;
		mProperties.styleData = null;
		mProperties.id = "";

		final AttributesImpl attrImpl = new AttributesImpl(attributes); // quicker to cast?

		final SvgStyle s = mProperties.svgStyle; // styleParseStack.peek(); // new Style();

		// Opacity: Not sure if the 'opacity' attribute (as opposed to fill-opacity or stroke-opacity
		// attributes) is supposed to inherit, so for now reset it to 1 each time. Remove this later
		// if it needs to inherit. Also, fill-opacity and stroke-opacity do inherit for time being.
		s.masterOpacity = 1;
		s.fillOpacity = 1;
		s.strokeOpacity = 1;

		// During execution of the loop, the length of attrImpl will expand if a <style> attribute
		// exists. A <style> tag's value itself contains a list of semicolon-separated key/value pairs.
		// The <style>'s key/value pairs are converted into attribute key/values,
		// taking advantage of AttributesImpl class .addAttribute method in doing so. The reason for
		// 'inflating' <style> into attributes is because graphical style attributes
		// can be present in an SVG file as either normal attributes, or contained as key/values
		// in the value of a <style> tag; we need to be able to process both situations.

		for (int n = 0; n < attrImpl.getLength(); n++) {

			v = attrImpl.getValue(n).trim(); // Value could contain prefix/suffix spaces; remove them.
			switch (Attr.toAttr(attrImpl.getLocalName(n))) {

				case x:
					mProperties.x = parseCoOrdinate(v);
					break;

				case y:
					mProperties.y = parseCoOrdinate(v);
					break;

				case x1:
					mProperties.x1 = parseCoOrdinate(v);
					break;

				case y1:
					mProperties.y1 = parseCoOrdinate(v);
					break;

				case x2:
					mProperties.x2 = parseCoOrdinate(v);
					break;

				case y2:
					mProperties.y2 = parseCoOrdinate(v);
					break;

				case cx:
					mProperties.cx = parseCoOrdinate(v);
					break;

				case cy:
					mProperties.cy = parseCoOrdinate(v);
					break;

				case r:
					mProperties.radius = parseCoOrdinate(v);
					break;

				case width:
					mProperties.width = parseCoOrdinate(v);
					break;

				case height:
					mProperties.height = parseCoOrdinate(v);
					break;

				case d:
					mProperties.pathData = v;
					break;

				case transform:
					mProperties.transformData = v;
					break;

				case gradientTransform:
					mProperties.transformData = v;
					break;

				case id:
					mProperties.id = v;
					break;

				case href:
					mProperties.xlink_href = v;
					break;

				// ------- Graphical style attributes -------

				case style:
					mProperties.styleData = v;
					parseAttributeValuePairsIntoSaxAttributesImpl(attrImpl);
					// The number of attribute key/value pairs has now been increased.
					break;

				case font_size:
					s.strokePaint.setTextSize(parseCoOrdinate(v));
					s.fillPaint.setTextSize(parseCoOrdinate(v));
					break;

				case font_family:
					final Typeface typeface = getTypeface(v);
					// if (typeface != null) {
					s.strokePaint.setTypeface(typeface);
					s.fillPaint.setTypeface(typeface);
					// }
					break;

				case fill:
					if (!v.equals("none")) {
						if (v.startsWith("url")) {
							// Assume the form fill:url(#[ID_STRING])
							final int gradientIdx = this.getGradientByIdString(v.substring(5, v.length() - 1));
							// TODO: This is giving the Paint a *reference* to one of the Shader objects
							// in the gradientList. This is possibly okay, but because we need to
							// apply the current Path's Matrix as the gradient's local Matrix during
							// rendering, we have to be careful not to permanently modify the shader,
							// as otherwise any changes (Matrix transformation etc) will affect
							// subsequent uses of that Shader.
							s.fillPaint.setShader(gradientList.get(gradientIdx).shader);
						}

						else {
							// Set the colour, while preserving the alpha (in case alpha is to be inherited rather
							// than being explicity set in the element's own style attributes)
							final int alpha = s.fillPaint.getAlpha();
							s.fillPaint.setColor(parseColour(v));
							s.fillPaint.setAlpha(alpha);
						}
						s.fillPaint.setStyle(Paint.Style.FILL);
						// TODO: Should only need do this once in Style constructor!
						s.hasFill = true;
					}
					else { // The attribute is fill="none".
						s.hasFill = false;
					}
					break;

				case opacity:
					s.masterOpacity = parseAttrValueFloat(v);
					s.fillPaint.setAlpha((int) (s.masterOpacity * s.fillOpacity * 255));
					s.strokePaint.setAlpha((int) (s.masterOpacity * s.strokeOpacity * 255));
					break;

				case fill_opacity: {
					final float opacity = parseAttrValueFloat(v);
					s.fillOpacity = opacity;
					s.fillPaint.setAlpha((int) (opacity * s.masterOpacity * 255));
				}
					break;

				case stroke_opacity: {
					final float opacity = parseAttrValueFloat(v);
					s.strokeOpacity = opacity;
					s.strokePaint.setAlpha((int) (opacity * s.masterOpacity * 255));
				}
					break;

				case stroke:
					if (!v.equals("none")) {
						if (v.startsWith("url")) {
							// Assume the form fill:url(#[ID_STRING])
							final int gradientIdx = this.getGradientByIdString(v.substring(5, v.length() - 1));
							// TODO: See comments further above (in 'fill') regarding Shader.
							s.strokePaint.setShader(gradientList.get(gradientIdx).shader);
						}
						else {
							// Set the colour, while preserving the alpha (in case alpha is to be inherited rather
							// than being explicity set in the element's own style attributes)
							final int alpha = s.strokePaint.getAlpha();
							s.strokePaint.setColor(parseColour(v));
							s.strokePaint.setAlpha(alpha);
						}
						s.strokePaint.setStyle(Paint.Style.STROKE); // TODO: Should only have to do once in the constructor!
						s.hasStroke = true;
					}
					else { // The attribute is stroke="none".
						s.hasStroke = false;
					}
					break;

				case stroke_width:
					s.strokePaint.setStrokeWidth(parseCoOrdinate(v));
					break;

				case points:
					mProperties.pointsData = v;
					break;

				case text_align:
					Paint.Align align = Paint.Align.LEFT;
					if (v.startsWith("center")) {
						align = Paint.Align.CENTER;
					}
					else if (v.startsWith("right")) {
						align = Paint.Align.RIGHT;
					}

					s.strokePaint.setTextAlign(align);
					s.fillPaint.setTextAlign(align);
					break;

				default:
					break;

			}
		}

		// mProperties.style = s;
	}


	// ------------------------------------------------------------------------------

	private void parseAttributeValuePairsIntoMap(final Map<String, String> map) {
		// Typical format is:
		// font-size:40px;font-style:normal;font-variant:normal; ...
		final Pattern keyValuePattern = Pattern.compile("([\\w-#]+\\s*):(\\s*[\\w-#\\.\\(\\)\\s,]*)"); // basically need to change this to match virtually anything on each side of the : except for a few symbols
		final Matcher keyValueMatcher = keyValuePattern.matcher(mProperties.styleData);
		while (keyValueMatcher.find()) {
			map.put(keyValueMatcher.group(1), keyValueMatcher.group(2));
		}
	}


	private int parseAttributeValuePairsIntoSaxAttributesImpl(final AttributesImpl attr) {
		int quantityAdded = 0;
		final Pattern keyValuePattern = Pattern.compile("([\\w-#]+\\s*):(\\s*[\\w-#\\.\\(\\)\\s,]*)"); // basically need to change this to match virtually anything on each side of the : except for a few symbols
		final Matcher keyValueMatcher = keyValuePattern.matcher(mProperties.styleData);
		while (keyValueMatcher.find()) {
			attr.addAttribute("", keyValueMatcher.group(1), "", "", keyValueMatcher.group(2));
			quantityAdded++;
		}
		return quantityAdded;
	}


	private float parseAttrValueFloat(final String value) {
		float f = 0f;
		try {
			f = Float.parseFloat(value);
		}
		catch (final Exception e) {

		}
		return f;
	}


	/**
	 * Parse a basic data type of type &lt;color&gt;. See SVG specification section:
	 * http://www.w3.org/TR/SVG/types.html#BasicDataTypes.
	 * TODO: This method needs error checking and reporting.
	 */
	private int parseColour(final String value) {

		int result = 0xffffff;

		// Handle colour values that are in the format "rgb(r,g,b)"
		if (value.startsWith("rgb")) {
			int r, g, b;
			final ValueTokenizer t = new ValueTokenizer();
			t.getToken(value.substring(3));
			if (t.currentTok == ValueTokenizer.LTOK_NUMBER) {
				r = (int) t.tokenF;
				t.getToken(null);
				if (t.currentTok == ValueTokenizer.LTOK_NUMBER) {
					g = (int) t.tokenF;
					t.getToken(null);
					if (t.currentTok == ValueTokenizer.LTOK_NUMBER) {
						b = (int) t.tokenF;
						result = (r << 16) + (g << 8) + b;
					}
				}
			}
		}

		// Handle colour values that are in the format #123abc. (Assume that's what it is,
		// if the length is seven characters).
		else if (value.length() == 7) {
			try {
				result = Integer.parseInt(value.substring(1, 7), 16);
			}
			catch (final NumberFormatException e) {
				result = 0xff0000;
			}
		}

		return result;
	}


	public void parseImageFile(final Context context, final File sourceFile) throws FileNotFoundException {
		final long start_time = System.currentTimeMillis();

		final InputStream inStream = new FileInputStream(sourceFile);
		parseImageFile(context, inStream);

		final long end_time = System.currentTimeMillis();

		if (null != LOG_TIMING) {
			LOG_TIMING.logSvgTiming(sourceFile.getPath(), end_time - start_time);
		}
	}


	public void parseImageFile(final Context context, final InputStream inStream) {
		mContext = context;
		tagDepth = 0;
		codePtr = 0;
		mProperties = new Properties();

		this.gradientList.clear();
		this.matrixList.clear();
		matrixEvStack.clear();
		this.paintStack.clear();
		this.pathList.clear();
		this.styleList.clear();
		this.arcsList.clear();

		bytecodeList = new ArrayList<Byte>();
		// mMetaDataQueryMap.clear();

		// Initialise the style stack with a first entry containing all of the default values.

		final SvgStyle s = new SvgStyle();

		mStyleParseStack.add(s);

		// TODO: Possibly stuff for constructor
		// InputStream inStream = null;
		// Resources res = context.getResources();

		// inStream = res.openRawResource(/*R.raw.gaugetest20*/ resourceID);
		final SAXParserFactory spf = SAXParserFactory.newInstance();
		try {
			final SAXParser sp = spf.newSAXParser();
			final XMLReader xr = sp.getXMLReader();
			xr.setContentHandler(this);
			xr.parse(new InputSource(inStream));
		}
		catch (final Exception e) {

		}

		addInstruction(INST_END); // could also go in endDocument if it exists.
		bytecodeArr = new byte[bytecodeList.size()];
		for (int i = 0; i < bytecodeList.size(); i++) {
			bytecodeArr[i] = bytecodeList.get(i);
		}

		bytecodeList = null; // TODO: test this doesn't break anything
		mContext = null;
	}


	public void parseImageFile(final Context context, final int resourceID) {
		final long start_time = System.currentTimeMillis();

		final Resources resources = context.getResources();
		final InputStream inStream = resources.openRawResource(resourceID);
		parseImageFile(context, inStream);

		final long end_time = System.currentTimeMillis();

		if (null != LOG_TIMING) {
			LOG_TIMING.logSvgTiming(resources.getResourceName(resourceID), end_time - start_time);
		}
	}


	private void path() {
		float rx, ry, x_axis_rotation, x, y, x1, y1, x2, y2;
		boolean firstElement = true, carry = false, large_arc_flag, sweep_flag;
		final PathTokenizer t = new PathTokenizer();
		t.getToken(mProperties.pathData);
		final Path p = new Path();
		char currentCommandLetter = '?';

		do {
			if (t.currentTok == PathTokenizer.LTOK_LETTER) {
				currentCommandLetter = t.tokenChar;
				t.getToken(null);
			}

			// If the current token is not alpha (a letter) but a number, then it's an implied command,
			// i.e. assume last used command letter.

			switch (currentCommandLetter) {

				case 'M':
				case 'm':
					x = t.tokenF;
					t.getToken(null);
					y = t.tokenF;
					// A relative moveto command, 'm', is interpreted as an absolute
					// moveto (M) if it's the first element.
					if (currentCommandLetter == 'm' && firstElement == false) {
						x += currentX;
						y += currentY;
					}
					p.moveTo(x, y);
					currentX = x;
					currentY = y;
					if (currentCommandLetter == 'M') {
						currentCommandLetter = 'L';
					}
					else {
						currentCommandLetter = 'l';
					}
					break;

				case 'L':
				case 'l':
					x = t.tokenF;
					t.getToken(null);
					y = t.tokenF;
					if (currentCommandLetter == 'l') {
						x += currentX;
						y += currentY;
					}
					p.lineTo(x, y);
					currentX = x;
					currentY = y;
					break;

				case 'H':
				case 'h':
					x = t.tokenF;
					if (currentCommandLetter == 'h') {
						x += currentX;
					}
					p.lineTo(x, currentY);
					currentX = x;
					break;

				case 'V':
				case 'v':
					y = t.tokenF;
					if (currentCommandLetter == 'v') {
						y += currentY;
					}
					p.lineTo(currentX, y);
					currentY = y;
					break;

				case 'z':
					// TODO: Having some trouble implementing 'z' / close. Need to revisit.
					p.close();
					carry = true;
					break;

				case 'A':
				case 'a':
					rx = t.tokenF;
					t.getToken(null);
					ry = t.tokenF;
					t.getToken(null);
					x_axis_rotation = t.tokenF;
					t.getToken(null);
					large_arc_flag = t.tokenF == 0f ? false : true;
					t.getToken(null);
					sweep_flag = t.tokenF == 0f ? false : true;
					t.getToken(null);
					x = t.tokenF;
					t.getToken(null);
					y = t.tokenF;
					if (currentCommandLetter == 'a') {
						x += currentX;
						y += currentY;
					}
					arcTo(p, rx, ry, x_axis_rotation, large_arc_flag, sweep_flag, x, y);
					currentX = x;
					currentY = y;
					break;

				case 'C':
				case 'c':
					x1 = t.tokenF;
					t.getToken(null);
					y1 = t.tokenF;
					t.getToken(null);
					x2 = t.tokenF;
					t.getToken(null);
					y2 = t.tokenF;
					t.getToken(null);
					x = t.tokenF;
					t.getToken(null);
					y = t.tokenF;
					if (currentCommandLetter == 'c') {
						x += currentX;
						y += currentY;

						x1 += currentX;
						y1 += currentY;
						x2 += currentX;
						y2 += currentY;
					}
					// TODO: Could alternatively make use of rCubicTo if it's to be relative.
					p.cubicTo(x1, y1, x2, y2, x, y);
					lastControlPointX = x2;
					lastControlPointY = y2;
					currentX = x;
					currentY = y;
					break;

				case 'S':
				case 's':
					x2 = t.tokenF;
					t.getToken(null);
					y2 = t.tokenF;
					t.getToken(null);
					x = t.tokenF;
					t.getToken(null);
					y = t.tokenF;
					if (currentCommandLetter == 's') {
						x += currentX;
						y += currentY;
						x2 += currentX;
						y2 += currentY;
					}
					x1 = 2 * currentX - lastControlPointX;
					y1 = 2 * currentY - lastControlPointY;
					// TODO: Could alternatively make use of rCubicTo if it's a relative command.
					p.cubicTo(x1, y1, x2, y2, x, y);
					currentX = x;
					currentY = y;
					break;

				case 'Q':
				case 'q':
					// TODO: To be completed!
					break;

				case 'T':
				case 't':
					// TODO: To be completed!
					break;

				default:
					carry = true;
					break;

			}

			firstElement = false;
			if (!carry) {
				t.getToken(null);
			}
			carry = false;

		}
		while (t.currentTok != PathTokenizer.LTOK_END);

		addPath(p);
	}


	private void polygon() {
		float x, y;
		final PathTokenizer t = new PathTokenizer();
		t.getToken(mProperties.pointsData);
		final Path p = new Path();

		x = t.tokenF;
		t.getToken(null);
		y = t.tokenF;
		t.getToken(null);
		p.moveTo(x, y);

		do {
			x = t.tokenF;
			t.getToken(null);
			y = t.tokenF;
			t.getToken(null);
			p.lineTo(x, y);

		}
		while (t.currentTok != PathTokenizer.LTOK_END);

		p.close();
		addPath(p);
	}


	private void radialGradient() {
		final Gradient g = currentGradient;

		// TODO: xlink stuff here
		/*
		 * g.setRadial(
		 * // cx, cy and also fx, fy are present in Inkscape output, but both are same
		 * // coordinates usually. Will just use cx and cy for now.
		 * mProperties.cx,
		 * mProperties.cy,
		 * mProperties.r,
		 * ?,
		 * null,
		 * Shader.TileMode.CLAMP
		 * );
		 */

		/*
		 * g.setCoordinates(
		 * mProperties.x1,
		 * mProperties.y1,
		 * mProperties.x2,
		 * mProperties.y2
		 * );
		 */
		g.cx = mProperties.cx;
		g.cy = mProperties.cy;
		g.radius = mProperties.radius;

		// Best way to deal with xlink might be to do an entire copy of the referenced
		// Gradient first, and then apply over the top any properties that are
		// explicitly defined for this Gradient.
		if (mProperties.xlink_href != null) {
			// It'll have the form #abcd where abcd is the ID
			// int idx = this.getGradientByIdString(mProperties.xlink_href.substring(1));
			// if(idx!=-1){
			// Gradient xlinkGrad = this.gradientList.get(idx);
			// currentGradient.stopColours = xlinkGrad.stopColours;
			// }
			currentGradient.href = mProperties.xlink_href;
		}
		else {
			currentGradient.href = null;
		}

		// if(mProperties.transformData!=null){
		currentGradient.matrix = transform();
		// }

		g.id = mProperties.id;

	}


	private void rect() {
		final Path p = new Path();
		p.addRect(mProperties.x, mProperties.y, mProperties.x + mProperties.width, mProperties.y + mProperties.height, Path.Direction.CW);
		currentX = mProperties.x;
		currentY = mProperties.y;
		addPath(p);
	}


	private void setCanvasScaleToSVG(final Canvas canvas, final View view) {
		canvas.scale(view.getWidth() / mRootSvgWidth, view.getHeight() / mRootSvgHeight);
	}


	// ------------------------------------------------------------------------------
	// Code Evaluator

	public void setPrivateDataNamespace(final String namespace) {
		mPrivateDataNamespace = namespace;
	}


	@Override
	public void startDocument() throws SAXException {
		super.startDocument();
	}


	/**
	 * Requires optimisation - lots of if / else that could be removed.
	 */
	@Override
	public void startElement(final String uri, final String localName, final String qName,
			final Attributes attributes) throws SAXException {

		super.startElement(uri, localName, qName, attributes);

		matrixExistsAtDepth[tagDepth] = false;
		mCurrentElement = localName;
		mProperties.svgStyle = new SvgStyle(mStyleParseStack.peek());

		if (mPrivateDataNamespace != null && qName.startsWith(mPrivateDataNamespace)) {
			mPrivateDataCurrentKey = localName;
		}
		else {
			mPrivateDataCurrentKey = null;
		}

		if (localName.equalsIgnoreCase(STARTTAG_SVG)) {
			parseAttributes(attributes);
			svg();
		}
		else if (localName.equalsIgnoreCase(STARTTAG_G)) {
			parseAttributes(attributes);
			addBeginGroup(mProperties.id);
		}
		else if (localName.equalsIgnoreCase(STARTTAG_PATH)) {
			parseAttributes(attributes);
			path();
		}
		else if (localName.equalsIgnoreCase(STARTTAG_RECT)) {
			parseAttributes(attributes);
			rect();
		}
		else if (localName.equalsIgnoreCase(STARTTAG_LINE)) {
			parseAttributes(attributes);
			line();
		}
		else if (localName.equalsIgnoreCase(STARTTAG_POLYGON)) {
			parseAttributes(attributes);
			polygon();
		}
		else if (localName.equalsIgnoreCase(STARTTAG_CIRCLE)) {
			parseAttributes(attributes);
			circle();
		}
		else if (localName.equalsIgnoreCase(STARTTAG_TEXT)) {
			parseAttributes(attributes);
			text_element();
		}
		else if (localName.equalsIgnoreCase(STARTTAG_TSPAN)) {
			parseAttributes(attributes);
			tspan_element();
		}
		else if (localName.equalsIgnoreCase(STARTTAG_LINEARGRADIENT)) {
			parseAttributes(attributes);
			linearGradient();
		}
		else if (localName.equalsIgnoreCase(STARTTAG_RADIALGRADIENT)) {
			parseAttributes(attributes);
			radialGradient();
		}
		else if (localName.equalsIgnoreCase(STARTTAG_STOP)) {
			parseAttributes(attributes);
			gradientStop();
		}

		mStyleParseStack.add(mProperties.svgStyle);
		tagDepth++;
	}


	private void svg() {
		mRootSvgHeight = mProperties.height;
		mRootSvgWidth = mProperties.width;
	}


	/**
	 * To be called from the XML character data handler when we are inside an element associated
	 * with display of text ('text', 'tspan', 'tref', 'textPath').
	 */
	private void text_characters(final char[] src, final int srcPos, final int length) {
		// addStyle();
		this.textstringList.add(new Textstring(mProperties.x, mProperties.y, src, srcPos, length));
		// Assume for now that all textstrings have a matrix
		// if (mProperties.transformData != null) {
		// addTransform();
		// }
		addText();
	}


	private void text_element() {
		addStyle();
		// if (mProperties.transformData != null) {
		addTransform();
		// }
	}


	private Matrix transform() {

		float f1, f2;
		final Matrix m = new Matrix();
		final ValueTokenizer t = new ValueTokenizer();

		if (mProperties.transformData != null) {
			t.getToken(mProperties.transformData);
			do {
				if (t.currentTok == ValueTokenizer.LTOK_STRING) {

					if (t.tokenStr.equalsIgnoreCase("translate")) {
						t.getToken(null);
						f1 = t.tokenF;
						t.getToken(null);
						f2 = t.tokenF;
						// Possibly use .postTranslate to apply over a previous transformation

						m.postTranslate(f1, f2);

					}

					else if (t.tokenStr.equalsIgnoreCase("rotate")) {
						t.getToken(null);
						f1 = t.tokenF;
						// Possibly use .postTranslate to apply over a previous transformation

						m.postRotate(f1);

					}

					else if (t.tokenStr.equalsIgnoreCase("matrix")) {
						final float f[] = new float[9];
						t.getToken(null);
						f[0] = t.tokenF;
						t.getToken(null);
						f[3] = t.tokenF;
						t.getToken(null);
						f[1] = t.tokenF;
						t.getToken(null);
						f[4] = t.tokenF;
						t.getToken(null);
						f[2] = t.tokenF;
						t.getToken(null);
						f[5] = t.tokenF;
						f[6] = 0;
						f[7] = 0;
						f[8] = 1;
						final Matrix post = new Matrix();
						post.setValues(f);
						m.postConcat(post);

						// m.getValues(f);
						// m.MTRANS_X is 2
						// m.MTRANS_Y is 5

						// m.setValues(f);
					}
				}

				t.getToken(null);

			}
			while (t.currentTok != ValueTokenizer.LTOK_END);
			mProperties.transformData = null;
		}
		return m;
	}


	private void tspan_characters(final char[] src, final int srcPos, final int length) {
		this.textstringList.add(new Textstring(mProperties.x, mProperties.y, src, srcPos, length));
		// Assume for now that all textstrings have a matrix
		// if (mProperties.transformData != null) {
		// addTransform();
		// }
		addText();
	}


	private void tspan_element() {
		addStyle();
		// if (mProperties.transformData != null) {
		addTransform();
		// }
	}

}
