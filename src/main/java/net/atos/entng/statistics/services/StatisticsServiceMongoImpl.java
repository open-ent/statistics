package net.atos.entng.statistics.services;

import static net.atos.entng.statistics.DateUtils.formatTimestamp;
import static net.atos.entng.statistics.controllers.StatisticsController.*;
import static org.entcore.common.aggregation.MongoConstants.TRACE_TYPE_SVC_ACCESS;
import static org.entcore.common.aggregation.MongoConstants.TRACE_FIELD_MODULE;
import static org.entcore.common.aggregation.MongoConstants.TRACE_FIELD_PROFILE;
import static org.entcore.common.aggregation.MongoConstants.TRACE_FIELD_STRUCTURES;
import static org.entcore.common.aggregation.MongoConstants.STATS_FIELD_DATE;
import static org.entcore.common.aggregation.MongoConstants.STATS_FIELD_GROUPBY;

import java.util.List;

import org.entcore.common.mongodb.MongoDbResult;
import org.entcore.common.service.impl.MongoDbCrudService;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.QueryBuilder;

import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.mongodb.MongoQueryBuilder;
import fr.wseduc.webutils.Either;

public class StatisticsServiceMongoImpl extends MongoDbCrudService implements StatisticsService {

	private final String collection;
	private final MongoDb mongo;

	public static final String MODULE_ID = TRACE_FIELD_MODULE + "_id";
	public static final String PROFILE_ID = TRACE_FIELD_PROFILE + "_id";
	public static final String STRUCTURES_ID = TRACE_FIELD_STRUCTURES + "_id";

	private static final JsonObject sortByDateProfile = new JsonObject().putNumber(STATS_FIELD_DATE, 1).putNumber(PROFILE_ID, 1);
	private static final JsonObject sortByStructureDateProfile = new JsonObject()
		.putNumber(STRUCTURES_ID, 1).putNumber(STATS_FIELD_DATE, 1).putNumber(PROFILE_ID, 1);

	public StatisticsServiceMongoImpl(String collection) {
		super(collection);
		this.collection = collection;
		this.mongo = MongoDb.getInstance();
	}

	@Override
	public void getStats(final List<String> schoolIds, final JsonObject params, final Handler<Either<String, JsonArray>> handler) {
		this.getStatistics(schoolIds, params, handler, false);
	}

	@Override
	public void getStatsForExport(final List<String> schoolIds, final JsonObject params, final Handler<Either<String, JsonArray>> handler) {
		this.getStatistics(schoolIds, params, handler, true);
	}


	private void getStatistics(final List<String> schoolIds, final JsonObject params, final Handler<Either<String, JsonArray>> handler, boolean isExport) {
		if(schoolIds==null || schoolIds.isEmpty()) {
			throw new IllegalArgumentException("schoolIds is null or empty");
		}

		String indicator = params.getString(PARAM_INDICATOR);
		Long start = (Long) params.getNumber(PARAM_START_DATE);
		Long end = (Long) params.getNumber(PARAM_END_DATE);

		boolean isAccessIndicator = TRACE_TYPE_SVC_ACCESS.equals(indicator);
		String groupedBy = isAccessIndicator ? "module/structures/profil" : "structures/profil";
		final QueryBuilder criteriaQuery = QueryBuilder.start(STATS_FIELD_GROUPBY).is(groupedBy)
				.and(STATS_FIELD_DATE).greaterThanEquals(formatTimestamp(start)).lessThan(formatTimestamp(end))
				.and(indicator).exists(true);

		String module = params.getString(PARAM_MODULE);
		boolean moduleIsEmpty = module==null || module.trim().isEmpty();
		boolean allModules = isAccessIndicator && moduleIsEmpty;
		if(isAccessIndicator && !moduleIsEmpty) {
			criteriaQuery.and(MODULE_ID).is(module);
		}

		if(schoolIds.size() > 1) {
			criteriaQuery.and(STRUCTURES_ID).in(schoolIds);
		}
		else {
			criteriaQuery.and(STRUCTURES_ID).is(schoolIds.get(0));

			// When getting data for only one module, a "find" is enough (no need to aggregate data)
			if(!isExport && !allModules) {
				JsonObject projection = new JsonObject();
				projection.putNumber("_id", 0)
					.putNumber(indicator, 1)
					.putNumber(PROFILE_ID, 1)
					.putNumber(STATS_FIELD_DATE, 1);

				mongo.find(collection, MongoQueryBuilder.build(criteriaQuery), sortByDateProfile,
						projection, MongoDbResult.validResultsHandler(handler));
				return;
			}
		}


		// Aggregate data
		final JsonObject aggregation = new JsonObject();
		JsonArray pipeline = new JsonArray();
		aggregation
			.putString("aggregate", collection)
			.putBoolean("allowDiskUse", true)
			.putArray("pipeline", pipeline);

		pipeline.addObject(new JsonObject().putObject("$match", MongoQueryBuilder.build(criteriaQuery)));

		JsonObject id = new JsonObject().putString(PROFILE_ID, "$"+PROFILE_ID);
		if(!allModules && !isExport) { // Do not group by date when getting data for the case "access to all modules"
			id.putString(STATS_FIELD_DATE, "$"+STATS_FIELD_DATE);
		}
		JsonObject groupBy = new JsonObject().putObject("$group", new JsonObject()
			.putObject("_id", id)
			.putObject(indicator, new JsonObject().putString("$sum", "$"+indicator)));
		pipeline.addObject(groupBy);

		QueryBuilder projection = QueryBuilder.start("_id").is(0)
				.and(PROFILE_ID).is("$_id."+PROFILE_ID);

		if(!isExport) {
			projection.and(indicator).is(1);
			if(!allModules) {
				projection.and(STATS_FIELD_DATE).is("$_id."+STATS_FIELD_DATE);
			}
			else {
				projection.and(MODULE_ID).is("$_id."+MODULE_ID);
			}

			// Sum stats for all structure_ids
			pipeline.addObject(new JsonObject().putObject("$project", MongoQueryBuilder.build(projection)));
		}
		else {
			// Projection : keep 'yyyy-MM' from 'yyyy-MM-dd HH:mm.ss.SSS'
			DBObject dateSubstring = new BasicDBObject();
			BasicDBList dbl = new BasicDBList();
			dbl.add("$_id."+STATS_FIELD_DATE);
			dbl.add(0);
			dbl.add(7);
			dateSubstring.put("$substr", dbl);

			projection.and(STATS_FIELD_DATE).is(dateSubstring)
				.and("indicatorValue").is("$"+indicator); // Replace indicatorName by label "indicatorValue", so that the mustache template can be generic


			JsonObject sort = sortByStructureDateProfile;

			// Export stats for each structure_id
			id.putString(STRUCTURES_ID, "$"+STRUCTURES_ID);
			projection.and(STRUCTURES_ID).is("$_id."+STRUCTURES_ID);

			if(isAccessIndicator) {
				if (allModules) {
					sort = sort.copy().putNumber(MODULE_ID, 1);
				}

				// Export stats for each module_id
				id.putString(MODULE_ID, "$"+MODULE_ID);
				projection.and(MODULE_ID).is("$_id."+MODULE_ID);
			}

			pipeline.addObject(new JsonObject().putObject("$project", MongoQueryBuilder.build(projection)));
			pipeline.addObject(new JsonObject().putObject("$sort", sort));
		}

		mongo.command(aggregation.toString(), new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> message) {
				if ("ok".equals(message.body().getString("status")) && message.body().getObject("result", new JsonObject()).getInteger("ok") == 1){
					JsonArray result = message.body().getObject("result").getArray("result");
					handler.handle(new Either.Right<String, JsonArray>(result));
				} else {
					String error = message.body().toString();
					handler.handle(new Either.Left<String, JsonArray>(error));
				}
			}
		});
	}

}
