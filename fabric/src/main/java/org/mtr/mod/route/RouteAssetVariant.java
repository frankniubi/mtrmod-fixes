package org.mtr.mod.route;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class RouteAssetVariant {

	private final int resolution;
	private final String language;
	private final Map<String, String> parameters;
	private final String canonicalParameters;

	private static final Pattern LANGUAGE_PATTERN = Pattern.compile("[A-Z0-9_-]{1,32}");
	private static final Pattern PARAMETER_NAME_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,31}");
	private static final Pattern PARAMETER_VALUE_PATTERN = Pattern.compile("[A-Za-z0-9._:+/-]{1,192}");

	public RouteAssetVariant(int resolution, String language, Map<String, String> parameters) {
		if (resolution < 0 || resolution > 3) {
			throw new IllegalArgumentException("Server route asset resolution must be between 0 and 3");
		}
		this.resolution = resolution;
		this.language = Objects.requireNonNull(language, "language").trim().toUpperCase(Locale.ROOT);
		if (!LANGUAGE_PATTERN.matcher(this.language).matches()) {
			throw new IllegalArgumentException("Invalid route asset language mode");
		}
		final TreeMap<String, String> sortedParameters = new TreeMap<>();
		Objects.requireNonNull(parameters, "parameters").forEach((name, value) -> {
			final String normalizedName = Objects.requireNonNull(name, "parameter name").trim().toLowerCase(Locale.ROOT);
			final String normalizedValue = Objects.requireNonNull(value, "parameter value").trim();
			if (!PARAMETER_NAME_PATTERN.matcher(normalizedName).matches() || !PARAMETER_VALUE_PATTERN.matcher(normalizedValue).matches()) {
				throw new IllegalArgumentException("Invalid route asset parameter");
			}
			if (sortedParameters.put(normalizedName, normalizedValue) != null) {
				throw new IllegalArgumentException("Duplicate route asset parameter: " + normalizedName);
			}
		});
		if (sortedParameters.isEmpty()) {
			throw new IllegalArgumentException("At least one route asset parameter is required");
		}
		this.parameters = Collections.unmodifiableMap(sortedParameters);
		final StringBuilder canonicalBuilder = new StringBuilder();
		sortedParameters.forEach((name, value) -> {
			if (canonicalBuilder.length() > 0) {
				canonicalBuilder.append(',');
			}
			canonicalBuilder.append(name).append('=').append(value);
		});
		canonicalParameters = canonicalBuilder.toString();
	}

	public static RouteAssetVariant parse(int resolution, String language, String parameters) {
		final TreeMap<String, String> parsedParameters = new TreeMap<>();
		for (final String parameter : Objects.requireNonNull(parameters, "parameters").split(",", -1)) {
			final int separator = parameter.indexOf('=');
			if (separator <= 0 || separator == parameter.length() - 1) {
				throw new IllegalArgumentException("Invalid route asset parameter: " + parameter);
			}
			final String name = parameter.substring(0, separator).trim().toLowerCase(Locale.ROOT);
			if (parsedParameters.put(name, parameter.substring(separator + 1).trim()) != null) {
				throw new IllegalArgumentException("Duplicate route asset parameter: " + name);
			}
		}
		return new RouteAssetVariant(resolution, language, parsedParameters);
	}

	public int getResolution() {
		return resolution;
	}

	public String getLanguage() {
		return language;
	}

	public Map<String, String> getParameters() {
		return parameters;
	}

	public String getCanonicalParameters() {
		return canonicalParameters;
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof RouteAssetVariant)) {
			return false;
		}
		final RouteAssetVariant that = (RouteAssetVariant) object;
		return resolution == that.resolution && language.equals(that.language) && parameters.equals(that.parameters);
	}

	@Override
	public int hashCode() {
		return Objects.hash(resolution, language, parameters);
	}
}
