/*******************************************************************************
 * Copyright 2014 uniVocity Software Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.univocity.parsers.common;

import com.univocity.parsers.common.fields.*;
import com.univocity.parsers.common.processor.*;
import com.univocity.parsers.conversions.*;

import java.util.*;

/**
 * The base class for {@link RowProcessor} and {@link RowWriterProcessor} implementations that support value conversions provided by {@link Conversion} instances.
 *
 * @author uniVocity Software Pty Ltd - <a href="mailto:parsers@univocity.com">parsers@univocity.com</a>
 */
public abstract class DefaultConversionProcessor implements ConversionProcessor {

	private Map<Class<?>, Conversion[]> conversionsByType;
	private FieldConversionMapping conversions;
	private boolean conversionsInitialized;

	private int[] fieldIndexes;
	private boolean fieldsReordered;

	RowProcessorErrorHandler errorHandler = NoopRowProcessorErrorHandler.instance;
	ParsingContext context;

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public final FieldSet<Integer> convertIndexes(Conversion... conversions) {
		return getConversions().applyConversionsOnFieldIndexes(conversions);
	}

	@Override
	@SuppressWarnings({"rawtypes", "unchecked"})
	public final void convertAll(Conversion... conversions) {
		getConversions().applyConversionsOnAllFields(conversions);
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public final FieldSet<String> convertFields(Conversion... conversions) {
		return getConversions().applyConversionsOnFieldNames(conversions);
	}

	private FieldConversionMapping getConversions() {
		if (conversions == null) {
			conversions = new FieldConversionMapping();
		}
		return conversions;
	}

	private void initializeConversions(String[] row, ParsingContext context) {
		conversionsInitialized = true;

		this.fieldIndexes = null;
		this.fieldsReordered = false;
		this.conversionsInitialized = false;

		String[] contextHeaders = context.headers();
		if (contextHeaders != null && contextHeaders.length > 0) {
			conversions.prepareExecution(false, contextHeaders);
		} else {
			conversions.prepareExecution(false, row);
		}

		this.fieldIndexes = context.extractedFieldIndexes();
		this.fieldsReordered = context.columnsReordered();
	}

	/**
	 * Executes the sequences of conversions defined using {@link DefaultConversionProcessor#convertFields(Conversion...)}, {@link DefaultConversionProcessor#convertIndexes(Conversion...)} and {@link DefaultConversionProcessor#convertAll(Conversion...)}, for every field in the given row.
	 *
	 * <p>Each field will be transformed using the {@link Conversion#execute(Object)} method.
	 * <p>In general the conversions will process a String and convert it to some object value (such as booleans, dates, etc).
	 *
	 * @param row     the parsed record with its individual records as extracted from the original input.
	 * @param context the current state of the parsing process.
	 *
	 * @return an row of Object instances containing the values obtained after the execution of all conversions.
	 * <p> Fields that do not have any conversion defined will just be copied to the object array into their original positions.
	 */
	public final Object[] applyConversions(String[] row, ParsingContext context) {
		boolean keepRow = true;
		Object[] objectRow = new Object[row.length];
		boolean[] convertedFlags = conversionsByType != null ? new boolean[row.length] : null;
		System.arraycopy(row, 0, objectRow, 0, row.length);

		if (conversions != null) {
			if (!conversionsInitialized) {
				initializeConversions(row, context);
			}

			final int length = !fieldsReordered && fieldIndexes == null ? objectRow.length : fieldIndexes.length;

			for (int i = 0; i < length; i++) {
				try {
					if (!fieldsReordered) {
						if (fieldIndexes == null) {
							objectRow[i] = conversions.applyConversions(i, row[i], convertedFlags);
						} else {
							int index = fieldIndexes[i];
							objectRow[index] = conversions.applyConversions(index, row[index], convertedFlags);
						}
					} else {
						objectRow[i] = conversions.applyConversions(fieldIndexes[i], row[i], convertedFlags);
					}
				} catch (Throwable ex) {
					keepRow = false;
					handleConversionError(ex, objectRow, i);
				}
			}

		}

		if (keepRow && convertedFlags != null) {
			keepRow = applyConversionsByType(false, objectRow, convertedFlags);
		}

		if (keepRow) {
			return objectRow;
		}

		return null;
	}

	/**
	 * Executes the sequences of reverse conversions defined using {@link DefaultConversionProcessor#convertFields(Conversion...)}, {@link DefaultConversionProcessor#convertIndexes(Conversion...)} and {@link DefaultConversionProcessor#convertAll(Conversion...)}, for every field in the given row.
	 *
	 * <p>Each field will be transformed using the {@link Conversion#revert(Object)} method.
	 * <p>In general the conversions will process an Object (such as a Boolean, Date, etc), and convert it to a String representation.
	 *
	 * @param executeInReverseOrder flag to indicate whether the conversion sequence should be executed in the reverse order of its declaration.
	 * @param row                   the row of objects that will be converted
	 * @param headers               All field names used to produce records in a given destination. May be null if no headers have been defined in {@link CommonSettings#getHeaders()}
	 * @param indexesToWrite        The indexes of the headers that are actually being written. May be null if no fields have been selected using {@link CommonSettings#selectFields(String...)} or {@link CommonSettings#selectIndexes(Integer...)}
	 *
	 * @return {@code true} if the the row should be discarded
	 */
	public final boolean reverseConversions(boolean executeInReverseOrder, Object[] row, String[] headers, int[] indexesToWrite) {
		boolean keepRow = true;
		boolean[] convertedFlags = conversionsByType != null ? new boolean[row.length] : null;
		if (conversions != null) {
			if (!conversionsInitialized) {
				conversionsInitialized = true;
				conversions.prepareExecution(true, headers != null ? headers : new String[row.length]);
				this.fieldIndexes = indexesToWrite;
			}

			final int last = fieldIndexes == null ? row.length : fieldIndexes.length;

			for (int i = 0; i < last; i++) {
				try {
					if (fieldIndexes == null || fieldIndexes[i] == -1) {
						row[i] = conversions.reverseConversions(executeInReverseOrder, i, row[i], convertedFlags);
					} else {
						int index = fieldIndexes[i];
						row[index] = conversions.reverseConversions(executeInReverseOrder, index, row[index], convertedFlags);
					}
				} catch (Throwable ex) {
					keepRow = false;
					handleConversionError(ex, row, i);
				}
			}
		}

		if (keepRow && convertedFlags != null) {
			keepRow = applyConversionsByType(true, row, convertedFlags);
		}

		return keepRow;
	}

	private boolean applyConversionsByType(boolean reverse, Object[] row, boolean[] convertedFlags) {
		boolean keepRow = true;
		for (int i = 0; i < row.length; i++) {
			if (convertedFlags[i]) {
				continue; //conversions already applied. Prevent default type conversion.
			}
			try {
				row[i] = applyTypeConversion(reverse, row[i]);
			} catch (Throwable ex) {
				keepRow = false;
				handleConversionError(ex, row, i);
			}
		}
		return keepRow;
	}

	protected final void handleConversionError(Throwable ex, Object[] row, int column) {
		DataProcessingException error;
		if (ex instanceof DataProcessingException) {
			error = (DataProcessingException) ex;
			error.setRow(row);
			error.setColumnIndex(column);
		} else {
			error = new DataProcessingException("Error processing data conversions", column, row, ex);
		}
		error.markAsNonFatal();
		error.setContext(context);
		errorHandler.handleError(error, row, context);
	}

	@Override
	public final void convertType(Class<?> type, Conversion... conversions) {
		ArgumentUtils.noNulls("Type to convert", type);
		ArgumentUtils.noNulls("Sequence of conversions to apply over data of type " + type.getSimpleName(), conversions);

		if (conversionsByType == null) {
			conversionsByType = new HashMap<Class<?>, Conversion[]>();
		}

		conversionsByType.put(type, conversions);
	}

	private Object applyTypeConversion(boolean revert, Object input) {
		if (conversionsByType == null || input == null) {
			return input;
		}

		Conversion[] conversionSequence = conversionsByType.get(input.getClass());
		if (conversionSequence == null) {
			return input;
		}

		if (revert) {
			for (int i = 0; i < conversionSequence.length; i++) {
				input = conversionSequence[i].revert(input);
			}
		} else {
			for (int i = 0; i < conversionSequence.length; i++) {
				input = conversionSequence[i].execute(input);
			}
		}
		return input;
	}
}
