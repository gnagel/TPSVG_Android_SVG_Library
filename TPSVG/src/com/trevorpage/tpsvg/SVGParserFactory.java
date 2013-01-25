package com.trevorpage.tpsvg;


import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;


import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;


public class SVGParserFactory {
	private static SVGParserFactoryLruMap	cache	= null;


	public static SVGParserRenderer create(final Context context, final int raw_resource) {
		if (null == cache) {
			int maxCacheLimit = 10;

			{
				final Resources resources = context.getResources();
				final int maxId = resources.getIdentifier("svg_factoryCacheLimit", "integer", context.getPackageName());
				if (0 != maxId) {
					maxCacheLimit = resources.getInteger(maxId);
				}
			}

			// Get memory class of this device, exceeding this amount will throw an OutOfMemory exception.
			final int memClass = ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryClass();

			// Use 1/8th of the available memory for this memory cache.
			final int cacheSize = 1024 * 1024 * memClass / 8;

			cache = new SVGParserFactoryLruMap(1, maxCacheLimit, cacheSize);
		}

		final Integer key = Integer.valueOf(raw_resource);
		SVGParserFactoryLruPair pair = cache.get(key);

		SVGParserRenderer value = null;
		if (null == pair || null == (value = pair.ref.get())) {
			value = new SVGParserRenderer(context, raw_resource);
			pair = new SVGParserFactoryLruPair(value);
			cache.put(key, pair);
		}

		return value;
	}
}


final class SVGParserFactoryLruMap extends LinkedHashMap<Integer, SVGParserFactoryLruPair>
{
	private static final long	serialVersionUID	= -4489363415011810955L;


	private int					_currentMemory;


	protected final int			_maxEntries, _maxMemory;


	public SVGParserFactoryLruMap(final int initialEntries, final int maxEntries, final int maxMemory)
	{
		super(initialEntries, 0.8f, true);
		_maxEntries = maxEntries;
		_maxMemory = maxMemory;
		_currentMemory = 0;
	}


	@Override
	public void clear() {
		super.clear();

		_currentMemory = 0;
	}


	@Override
	public SVGParserFactoryLruPair put(final Integer key, final SVGParserFactoryLruPair value) {
		if (null != value) {
			_currentMemory += value.size;
		}

		return super.put(key, value);
	}


	@Override
	public SVGParserFactoryLruPair remove(final Object key) {
		final SVGParserFactoryLruPair value = super.remove(key);
		if (null != value) {
			_currentMemory -= value.size;
		}

		return value;
	}


	@Override
	protected boolean removeEldestEntry(final Map.Entry<Integer, SVGParserFactoryLruPair> eldest)
	{
		if (size() > _maxEntries && _maxEntries != -1) {
			return true;
		}

		if (_currentMemory > _maxMemory) {
			return true;
		}

		final SVGParserFactoryLruPair value = eldest.getValue();
		if (null == value) {
			return true;
		}

		if (null == value.ref.get()) {
			return true;
		}

		return false;
	}

}


final class SVGParserFactoryLruPair {
	final WeakReference<SVGParserRenderer>	ref;


	final int								size;


	SVGParserFactoryLruPair(final SVGParserRenderer value)
	{
		this.ref = new WeakReference<SVGParserRenderer>(value);
		this.size = value.bytecodeArrSize();
	}
}
