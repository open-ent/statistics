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

package fr.wseduc.stats.services;

import static org.entcore.common.aggregation.MongoConstants.STATS_FIELD_DATE;
import static org.entcore.common.aggregation.MongoConstants.STATS_FIELD_GROUPBY;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.entcore.common.service.impl.MongoDbCrudService;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.webutils.Either;

/**
 * Implémentation MongoDB du service de lecture des statistiques.
 *
 * <p>Le frontend (version 2.6.0) interroge {@code /stats/list} avec le contrat « PG »
 * ({@code indicator}, {@code from}/{@code to}, {@code entity}, {@code entitylevel},
 * {@code frequency}) et attend des lignes {@code StatsAccountsResponse}/{@code StatsAccessResponse}.
 * Ici on sert ces lignes depuis la collection Mongo {@code stats} (agrégée par
 * {@link org.entcore.common.aggregation}) : docs {@code groupedBy="structures/profil"}
 * (LOGIN/ACTIVATION/UNIQUE_VISITORS_*) et {@code "structures/module"} (ACCESS), regroupés
 * par période côté Java (volume faible par établissement).
 */
public class StatsServiceMongoImpl extends MongoDbCrudService implements StatsService {

	private final String collection;
	private final MongoDb mongo;

	public StatsServiceMongoImpl(final String collection) {
		super(collection);
		this.collection = collection;
		this.mongo = MongoDb.getInstance();
	}

	@Override
	public void listStats(MultiMap params, Handler<Either<String, JsonArray>> handler) {
		if (params == null) { handler.handle(new Either.Right<>(new JsonArray())); return; }

		final String indicator = orDefault(params.get("indicator"), "accounts"); // accounts | access
		final String entityLevel = orDefault(params.get("entitylevel"), "structure"); // structure | class
		final String frequency = orDefault(params.get("frequency"), "month"); // day | week | month
		final List<String> entities = params.getAll("entity");
		final String fromDay = dayPrefix(params.get("from"));
		final String toDay = dayPrefix(params.get("to"));

		final boolean isAccess = "access".equals(indicator);
		final String levelKey = "class".equals(entityLevel) ? "classes" : "structures"; // champ Mongo
		final String idField = levelKey + "_id";
		final String outIdField = "class".equals(entityLevel) ? "class_id" : "structure_id";
		// groupedBy Mongo : accounts -> "<lvl>/profil" ; access -> "<lvl>/module"
		final String groupedBy = levelKey + (isAccess ? "/module" : "/profil");

		final JsonObject query = new JsonObject().put(STATS_FIELD_GROUPBY, groupedBy);
		if (entities != null && !entities.isEmpty()) {
			query.put(idField, new JsonObject().put("$in", new JsonArray(entities)));
		}

		mongo.find(collection, query, (Message<JsonObject> msg) -> {
			if (!"ok".equals(msg.body().getString("status"))) {
				handler.handle(new Either.Left<>(msg.body().getString("message", "stats.list.error")));
				return;
			}
			final JsonArray docs = msg.body().getJsonArray("results", new JsonArray());
			// Regroupement par (entité, profil|module, période).
			final Map<String, JsonObject> rows = new LinkedHashMap<>();
			for (int k = 0; k < docs.size(); k++) {
				final JsonObject doc = docs.getJsonObject(k);
				final String day = dayOf(doc.getValue(STATS_FIELD_DATE));
				if (day == null) continue;
				if (fromDay != null && day.compareTo(fromDay) < 0) continue;
				if (toDay != null && day.compareTo(toDay) > 0) continue;

				final String entityId = doc.getString(idField);
				if (entityId == null) continue;
				final String period = periodKey(day, frequency);
				final String dim = isAccess ? doc.getString("module_id", "") : doc.getString("profil_id", "");
				final String rk = entityId + "|" + dim + "|" + period;

				JsonObject row = rows.get(rk);
				if (row == null) {
					row = new JsonObject()
							.put("id", rk)
							.put("platform_id", "")
							.put("date", periodDate(period, frequency))
							.put(outIdField, entityId);
					if (isAccess) {
						row.put("profile", "").put("module", dim).put("type", connectorType(dim))
								.put("access", 0L).put("unique_access", 0L).put("unique_access_minute", 0L);
					} else {
						row.put("profile", dim)
								.put("authentications", 0L).put("unique_visitors", 0L)
								.put("activations", 0L).put("activated", 0L).put("loaded", 0L)
								.put("sessions", 0L).put("device_type", "");
					}
					rows.put(rk, row);
				}

				if (isAccess) {
					row.put("access", row.getLong("access") + doc.getLong("ACCESS", 0L));
				} else {
					final long login = doc.getLong("LOGIN", 0L);
					final long act = doc.getLong("ACTIVATION", 0L);
					row.put("authentications", row.getLong("authentications") + login);
					row.put("sessions", row.getLong("sessions") + login);
					row.put("activations", row.getLong("activations") + act);
					row.put("activated", row.getLong("activated") + act);
					final long uv = doc.getLong(uniqueVisitorsField(frequency), 0L);
					if (uv > row.getLong("unique_visitors")) row.put("unique_visitors", uv);
				}
			}

			final JsonArray out = new JsonArray();
			for (JsonObject r : rows.values()) {
				if (isAccess && r.getLong("access") <= 0L) continue;
				out.add(r);
			}
			handler.handle(new Either.Right<>(out));
		});
	}

	@Override
	public void listStatsExport(MultiMap params, String language, Handler<Either<String, JsonArray>> handler) {
		listStats(params, handler);
	}

	// --- Helpers ---

	private static String orDefault(String s, String def) {
		return (s == null || s.isEmpty()) ? def : s;
	}

	/** Préfixe jour (YYYY-MM-DD) d'une date ISO de paramètre, ou null. */
	private static String dayPrefix(String iso) {
		return (iso == null || iso.length() < 10) ? null : iso.substring(0, 10);
	}

	/** Jour (YYYY-MM-DD) d'une valeur de date Mongo (String "YYYY-MM-DD ..." ou {$date}). */
	private static String dayOf(Object dateValue) {
		if (dateValue instanceof String) {
			final String s = (String) dateValue;
			if (s.length() >= 10 && Character.isDigit(s.charAt(0))) return s.substring(0, 10);
			return null;
		}
		if (dateValue instanceof JsonObject) {
			final Object d = ((JsonObject) dateValue).getValue("$date");
			if (d instanceof String && ((String) d).length() >= 10) return ((String) d).substring(0, 10);
		}
		return null;
	}

	/** Clé de période selon la fréquence demandée. */
	private static String periodKey(String day, String freq) {
		if ("day".equals(freq)) return day;
		if ("month".equals(freq)) return day.substring(0, 7);
		// week : année-semaine ISO
		try {
			final LocalDate d = LocalDate.parse(day);
			final int wy = d.get(IsoFields.WEEK_BASED_YEAR);
			final int wk = d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
			return wy + "-W" + (wk < 10 ? "0" : "") + wk;
		} catch (RuntimeException e) {
			return day;
		}
	}

	/** Date représentative d'une période (lisible par le frontend). */
	private static String periodDate(String period, String freq) {
		if ("day".equals(freq)) return period;
		if ("month".equals(freq)) return period + "-01";
		try {
			final String[] p = period.split("-W");
			final LocalDate monday = LocalDate.now()
					.with(IsoFields.WEEK_BASED_YEAR, Integer.parseInt(p[0]))
					.with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, Integer.parseInt(p[1]))
					.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
			return monday.toString();
		} catch (RuntimeException e) {
			return period;
		}
	}

	private static String uniqueVisitorsField(String freq) {
		if ("day".equals(freq)) return "UNIQUE_VISITORS_DAY";
		if ("week".equals(freq)) return "UNIQUE_VISITORS_WEEK";
		return "UNIQUE_VISITORS_MONTH";
	}

	/** Distingue connecteurs externes (CONNECTOR) des applications internes (ACCESS). */
	private static String connectorType(String module) {
		if (module == null) return "ACCESS";
		switch (module) {
			case "Matomo":
			case "Moodle":
			case "Pronote":
			case "Gar":
			case "Mediacentre":
			case "Wordpress":
			case "Esidoc":
				return "CONNECTOR";
			default:
				return "ACCESS";
		}
	}
}
