package net.atos.entng.statistics.services;

import fr.wseduc.webutils.Either;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.elasticsearch.ElasticSearch;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static fr.wseduc.webutils.Utils.isNotEmpty;
import static net.atos.entng.statistics.aggregation.indicators.IndicatorConstants.STATS_FIELD_ACCOUNTS;
import static net.atos.entng.statistics.aggregation.indicators.IndicatorConstants.STATS_FIELD_ACTIVATED_ACCOUNTS;
import static net.atos.entng.statistics.aggregation.indicators.IndicatorConstants.STATS_FIELD_UNIQUE_VISITORS;
import static net.atos.entng.statistics.controllers.StatisticsController.*;
import static org.entcore.common.aggregation.MongoConstants.*;

public class StatisticsServiceESImpl implements StatisticsService {

	private static final Logger log = LoggerFactory.getLogger(StatisticsServiceESImpl.class);
	private final ElasticSearch es = ElasticSearch.getInstance();
	private String timezone;

	@Override
	public void getStats(List<String> schoolIds, JsonObject params, Handler<Either<String, JsonArray>> handler) {
		getStats(schoolIds, params, false, handler);
	}

	private void getStats(List<String> schoolIds, JsonObject params, boolean export, Handler<Either<String, JsonArray>> handler) {
		if(schoolIds == null || schoolIds.isEmpty()) {
			handler.handle(new Either.Left<>("schoolIds is null or empty"));
			return;
		}

		final String indicator = params.getString(PARAM_INDICATOR);
		final Long start = params.getLong(PARAM_START_DATE);
		final Long end = params.getLong(PARAM_END_DATE);

		final JsonObject range = new JsonObject()
				.put("range", new JsonObject()
						.put("date", new JsonObject()
								.put("gte", start).put("lt", end)));
		final JsonObject structures = new JsonObject();
		if (schoolIds.size() == 1) {
			structures.put("term", new JsonObject().put("structures", schoolIds.get(0)));
		} else {
			structures.put("terms", new JsonObject().put("structures", new JsonArray(schoolIds)));
		}

		final JsonArray filter = new JsonArray();
		final JsonObject dateHistogram = new JsonObject().put("field", TRACE_FIELD_DATE).put("interval", "month");
		if (isNotEmpty(timezone)) {
			dateHistogram.put("time_zone", timezone);
		}
		final JsonObject perMonth = new JsonObject()
				.put("date_histogram", dateHistogram);

		JsonObject groupBy = new JsonObject();
		AtomicBoolean groupByModule = new AtomicBoolean(false);

		final String module = params.getString(PARAM_MODULE);

		switch (indicator) {
			case TRACE_TYPE_SVC_ACCESS:
				if (isNotEmpty(module)) {
					filter.add(new JsonObject().put("term", new JsonObject().put(PARAM_MODULE, module)));
				} else {
					groupByModule.set(true);
					if (export) {
						filter.add(new JsonObject().put("term", new JsonObject().put("event-type", indicator)));
						perMonth.put("aggs", new JsonObject().put("group_by", groupBy
								.put("terms", new JsonObject().put("field", TRACE_FIELD_PROFILE))
								.put("aggs", new JsonObject().put("per_module", new JsonObject()
										.put("terms", new JsonObject().put("field", "module"))))));
						break;
					} else {
						perMonth.clear();
						perMonth.put("terms", new JsonObject().put("field", "module"));
					}
				}
			case TRACE_TYPE_CONNEXION:
			case TRACE_TYPE_ACTIVATION:
				filter.add(new JsonObject().put("term", new JsonObject().put("event-type", indicator)));
				perMonth.put("aggs", new JsonObject().put("group_by", groupBy
						.put("terms", new JsonObject().put("field", TRACE_FIELD_PROFILE))));
				break;
			case STATS_FIELD_UNIQUE_VISITORS:
				filter.add(new JsonObject().put("term", new JsonObject().put("event-type", TRACE_TYPE_CONNEXION)));
				perMonth.put("aggs", new JsonObject().put("group_by", groupBy
						.put("terms", new JsonObject().put("field", TRACE_FIELD_PROFILE))
						.put("aggs", new JsonObject()
								.put("unique_count", new JsonObject().put("cardinality", new JsonObject()
										.put("field", TRACE_FIELD_USER)
										.put("precision_threshold", 5000)
						)))
				));
				break;
			case STATS_FIELD_ACTIVATED_ACCOUNTS:
				filter.add(new JsonObject().put("term", new JsonObject().put("event-type", STATS_FIELD_ACCOUNTS)));
				perMonth.put("aggs", new JsonObject().put("group_by", groupBy
						.put("terms", new JsonObject().put("field", TRACE_FIELD_PROFILE))
						.put("aggs", new JsonObject()
								.put("activated_accounts", new JsonObject().put("sum", new JsonObject()
										.put("field", "activatedAccounts")
								))
								.put("accounts", new JsonObject().put("sum", new JsonObject()
										.put("field", "accounts")
								))
						)
				));
				break;
			default:
				handler.handle(new Either.Left<>("invalid.indicator"));
				return;
		}
		filter.add(structures).add(range);
		JsonObject search = new JsonObject()
				.put("size", 0)
				.put("query", new JsonObject().put("bool", new JsonObject().put("filter", filter)));
		if (export) {
			search.put("aggs", new JsonObject().put("per_structure", new JsonObject()
					.put("terms", new JsonObject().put("field", "structures").put("size", schoolIds.size()))
					.put("aggs", new JsonObject().put("per_month", perMonth))
			));
		} else {
			search.put("aggs", new JsonObject().put("per_month", perMonth));
		}
		// log.info(search.encodePrettily());

		es.search("events", search, ar -> {
			if (ar.succeeded()) {
				try {
					if (export) {
						handler.handle(new Either.Right<>(formatExport(
								ar.result(), indicator, groupByModule.get(), schoolIds, module)));
					} else {
						handler.handle(new Either.Right<>(format(ar.result(), indicator, groupByModule.get())));
					}
				} catch (Exception e) {
					log.error("Error formatting aggregation result.", e);
					handler.handle(new Either.Left<>(e.getMessage()));
				}
			} else {
				log.error("Error in aggregation.", ar.cause());
				handler.handle(new Either.Left<>(ar.cause().getMessage()));
			}
		});

	}

	private JsonArray format(JsonObject result, String indicator, boolean groupByModule) {
		final DateFormat df = new SimpleDateFormat("yyyy-MM-dd 00:00.00.000");
		final JsonArray months = result.getJsonObject("aggregations")
				.getJsonObject("per_month").getJsonArray("buckets");
		final JsonArray results = new JsonArray();
		for (Object o: months) {
			final JsonObject j = (JsonObject) o;
			final String dateMonth;
			if (groupByModule) {
				dateMonth = j.getString("key");
			} else {
				dateMonth = df.format(new Date(j.getLong("key")));
			}
			final JsonArray buckets = j.getJsonObject("group_by").getJsonArray("buckets");
			for (Object o2: buckets) {
				final JsonObject j2 = (JsonObject) o2;
				final String profile = j2.getString("key");
				final int count;
				JsonObject res = new JsonObject();
				if (STATS_FIELD_UNIQUE_VISITORS.equals(indicator)) {
					count = j2.getJsonObject("unique_count").getInteger("value");
				} else if (STATS_FIELD_ACTIVATED_ACCOUNTS.equals(indicator)) {
					count = j2.getJsonObject("activated_accounts").getInteger("value");
					res.put(STATS_FIELD_ACCOUNTS, j2.getJsonObject("accounts").getInteger("value"));
				} else {
					count = j2.getInteger("doc_count");
				}
				results.add(res
						.put(groupByModule ? "module_id" : "date", dateMonth)
						.put("profil_id", profile)
						.put(indicator, count)
				);
			}
		}
		return results;
	}

	private JsonArray formatExport(JsonObject result, String indicator, boolean groupByModule,
			List<String> structures, String module) {
		final DateFormat df = new SimpleDateFormat("yyyy-MM");
		final JsonArray structs = result.getJsonObject("aggregations")
				.getJsonObject("per_structure").getJsonArray("buckets");
		final JsonArray results = new JsonArray();
		for (Object os: structs) {
			final JsonObject js = (JsonObject) os;
			final String structure = js.getString("key");
			if (!structures.contains(structure)) {
				continue;
			}
			final JsonArray months = js.getJsonObject("per_month").getJsonArray("buckets");
			for (Object o : months) {
				final JsonObject j = (JsonObject) o;
				final String dateMonth = df.format(new Date(j.getLong("key")));
				final JsonArray buckets = j.getJsonObject("group_by").getJsonArray("buckets");
				if (groupByModule) {
					for (Object o2 : buckets) {
						final JsonObject j2 = (JsonObject) o2;
						final String profile = j2.getString("key");
						final JsonArray modules = j2.getJsonObject("per_module").getJsonArray("buckets");
						for (Object m: modules) {
							final JsonObject app = (JsonObject) m;
							JsonObject res = new JsonObject();
							final int count = app.getInteger("doc_count");
							res
									.put("module_id", app.getString("key"))
									.put("date", dateMonth)
									.put("profil_id", profile)
									.put("indicatorValue", count)
									.put("structures_id", structure);
							results.add(res);
						}
					}
				} else {
					for (Object o2 : buckets) {
						final JsonObject j2 = (JsonObject) o2;
						final String profile = j2.getString("key");
						final int count;
						JsonObject res = new JsonObject();
						if (STATS_FIELD_UNIQUE_VISITORS.equals(indicator)) {
							count = j2.getJsonObject("unique_count").getInteger("value");
						} else if (STATS_FIELD_ACTIVATED_ACCOUNTS.equals(indicator)) {
							count = j2.getJsonObject("activated_accounts").getInteger("value");
							res.put(STATS_FIELD_ACCOUNTS, j2.getJsonObject("accounts").getInteger("value"));
						} else {
							count = j2.getInteger("doc_count");
						}
						if (isNotEmpty(module)) {
							res.put("module_id", module);
						}
						res
								.put("date", dateMonth)
								.put("profil_id", profile)
								.put("indicatorValue", count)
								.put("structures_id", structure);
						results.add(res);
					}
				}
			}
		}
		return results;
	}

	@Override
	public void getStatsForExport(List<String> schoolIds, JsonObject params, Handler<Either<String, JsonArray>> handler) {
		getStats(schoolIds, params, true,  handler);
	}

	public void setTimezone(String timezone) {
		this.timezone = timezone;
	}

}
