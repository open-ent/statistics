/*
 * Copyright © "Open Digital Education" (SAS “WebServices pour l’Education”), 2014
 *
 * This program is published by "Open Digital Education" (SAS “WebServices pour l’Education”).
 * You must indicate the name of the software and the company in any production /contribution
 * using the software and indicate on the home page of the software industry in question,
 * "powered by Open Digital Education" with a reference to the website: https: //opendigitaleducation.com/.
 *
 * This program is free software, licensed under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of the License.
 *
 * You can redistribute this application and/or modify it since you respect the terms of the GNU Affero General Public License.
 * If you modify the source code and then use this modified source code in your creation, you must make available the source code of your modifications.
 *
 * You should have received a copy of the GNU Affero General Public License along with the software.
 * If not, please see : <http://www.gnu.org/licenses/>. Full compliance requires reading the terms of this license and following its directives.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package fr.wseduc.stats.aggregation.filters;

import static org.entcore.common.aggregation.MongoConstants.TRACE_FIELD_DATE;

import java.util.Date;

import org.bson.conversions.Bson;
import org.entcore.common.aggregation.filters.IndicatorFilter;

import io.vertx.core.json.JsonObject;

/**
 * Filters traces by a date interval [from, to).
 * <p>
 * Replaces org.entcore.common.aggregation.filters.mongo.DateFilter, which has no effect since the
 * vertx 4 migration: it calls Filters.and(builder, ...) on an immutable Bson and drops the result,
 * so every indicator aggregated the whole events collection. The bounds are injected into the
 * $match stage by {@link fr.wseduc.stats.aggregation.Indicators.DateFilteredIndicator}.
 */
public class DateRangeFilter implements IndicatorFilter {

	private final Date from, to;

	/**
	 * @param from : Lower bound (inclusive)
	 * @param to : Higher bound (not inclusive)
	 */
	public DateRangeFilter(Date from, Date to) {
		this.from = from;
		this.to = to;
	}

	/** No-op: a Bson cannot be extended in place, see {@link #applyTo(JsonObject)}. */
	@Override
	public void filter(Bson builder) {
	}

	/**
	 * Adds the date bounds to a $match stage. Event dates are stored as epoch milliseconds.
	 * @param match : content of the $match stage.
	 */
	public void applyTo(JsonObject match) {
		match.put(TRACE_FIELD_DATE, new JsonObject()
			.put("$gte", from.getTime())
			.put("$lt", to.getTime()));
	}

}
