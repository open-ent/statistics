/*
 * Copyright © Région Nord Pas de Calais-Picardie, 2016.
 *
 * This file is part of OPEN ENT NG. OPEN ENT NG is a versatile ENT Project based on the JVM and ENT Core Project.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation (version 3 of the License).
 *
 * For the sake of explanation, any module that communicate over native
 * Web protocols, such as HTTP, with OPEN ENT NG is outside the scope of this
 * license and could be license under its own terms. This is merely considered
 * normal use of OPEN ENT NG, and does not fall under the heading of "covered work".
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package net.atos.entng.statistics.services;

import static net.atos.entng.statistics.DateUtils.formatTimestamp;
import static net.atos.entng.statistics.controllers.StatisticsController.*;
import static net.atos.entng.statistics.aggregation.indicators.IndicatorConstants.STATS_FIELD_ACTIVATED_ACCOUNTS;
import static net.atos.entng.statistics.aggregation.indicators.IndicatorConstants.STATS_FIELD_ACCOUNTS;
import static org.entcore.common.aggregation.MongoConstants.TRACE_TYPE_SVC_ACCESS;
import static org.entcore.common.aggregation.MongoConstants.TRACE_FIELD_MODULE;
import static org.entcore.common.aggregation.MongoConstants.TRACE_FIELD_PROFILE;
import static org.entcore.common.aggregation.MongoConstants.TRACE_FIELD_STRUCTURES;
import static org.entcore.common.aggregation.MongoConstants.STATS_FIELD_DATE;
import static org.entcore.common.aggregation.MongoConstants.STATS_FIELD_GROUPBY;
import static org.entcore.common.aggregation.MongoConstants.TRACE_TYPE_CONNECTOR;

import java.util.List;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.conversions.Bson;
import org.entcore.common.mongodb.MongoDbResult;
import org.entcore.common.service.impl.MongoDbCrudService;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

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

	private static final JsonObject sortByDateProfile = new JsonObject().put(STATS_FIELD_DATE, 1).put(PROFILE_ID, 1);
	private static final JsonObject sortByStructureDateProfile = new JsonObject()
		.put(STRUCTURES_ID, 1).put(STATS_FIELD_DATE, 1).put(PROFILE_ID, 1);

	public StatisticsServiceMongoImpl(String collection) {
		super(collection);
		this.collection = collection;
		this.mongo = MongoDb.getInstance();
	}

	@Override
	public void getStats(final List<String> schoolIds, final JsonObject params, JsonArray mobileClientIds, final Handler<Either<String, JsonArray>> handler) {
		this.getStatistics(schoolIds, params, handler, false);
	}

	@Override
	public void getStatsForExport(final List<String> schoolIds, final JsonObject params, JsonArray mobileClientIds, final Handler<Either<String, JsonArray>> handler) {
		this.getStatistics(schoolIds, params, handler, true);
	}


	private void getStatistics(final List<String> schoolIds, final JsonObject params, final Handler<Either<String, JsonArray>> handler, boolean isExport) {
		if(schoolIds==null || schoolIds.isEmpty()) {
			throw new IllegalArgumentException("schoolIds is null or empty");
		}

		String indicator = params.getString(PARAM_INDICATOR);
		Long start = params.getLong(PARAM_START_DATE);
		Long end = params.getLong(PARAM_END_DATE);

		boolean isActivatedAccountsIndicator = STATS_FIELD_ACTIVATED_ACCOUNTS.equals(indicator);
		boolean isAccessIndicator = TRACE_TYPE_SVC_ACCESS.equals(indicator);
		boolean isConnector = TRACE_TYPE_CONNECTOR.equals(indicator);
		String groupedBy = (isAccessIndicator || isConnector) ? "module/structures/profil" : "structures/profil";
		Bson criteriaQuery = Filters.and(
				Filters.eq(STATS_FIELD_GROUPBY, groupedBy),
				Filters.gte(STATS_FIELD_DATE, formatTimestamp(start)),
				Filters.lt(STATS_FIELD_DATE, formatTimestamp(end)),
				Filters.exists(indicator, true)
		);

		String module = params.getString(PARAM_MODULE);
		boolean moduleIsEmpty = module==null || module.trim().isEmpty();
		boolean isAccessAllModules = isAccessIndicator && moduleIsEmpty;
		if(isAccessIndicator && !moduleIsEmpty || isConnector && !moduleIsEmpty) {
			criteriaQuery = Filters.and(criteriaQuery, Filters.eq(MODULE_ID, module));
		}

		if(schoolIds.size() > 1) {
			criteriaQuery = Filters.and(criteriaQuery, Filters.in(STRUCTURES_ID, schoolIds));
		}
		else {
			criteriaQuery = Filters.and(criteriaQuery, Filters.eq(STRUCTURES_ID, schoolIds.get(0)));

			// When getting data for only one module, a "find" is enough (no need to aggregate data)
			if(!isExport && !isAccessAllModules) {
				JsonObject projection = new JsonObject();
				projection.put("_id", 0)
					.put(indicator, 1)
					.put(PROFILE_ID, 1)
					.put(STATS_FIELD_DATE, 1);
				if(isActivatedAccountsIndicator) {
					projection.put(STATS_FIELD_ACCOUNTS, 1);
				}

				mongo.find(collection, MongoQueryBuilder.build(criteriaQuery), sortByDateProfile,
						projection, MongoDbResult.validResultsHandler(handler));
				return;
			}
		}


		// Aggregate data
		final JsonObject aggregation = new JsonObject();
		JsonArray pipeline = new JsonArray();
		aggregation
			.put("aggregate", collection)
			.put("allowDiskUse", true)
			.put("cursor", new JsonObject().put("batchSize", Integer.MAX_VALUE))
			.put("pipeline", pipeline);

		pipeline.add(new JsonObject().put("$match", MongoQueryBuilder.build(criteriaQuery)));

		JsonObject id = new JsonObject().put(PROFILE_ID, "$"+PROFILE_ID);
		if(isAccessAllModules && !isExport) {
			// Case : get JSON data for indicator "access to all modules"
			id.put(MODULE_ID, "$"+MODULE_ID);
		}
		else {
			id.put(STATS_FIELD_DATE, "$"+STATS_FIELD_DATE);
		}

		JsonObject group = new JsonObject()
			.put("_id", id)
			.put(indicator, new JsonObject().put("$sum", "$"+indicator));
		if(isActivatedAccountsIndicator) {
			group.put(STATS_FIELD_ACCOUNTS, new JsonObject().put("$sum", "$"+STATS_FIELD_ACCOUNTS));
		}
		JsonObject groupBy = new JsonObject().put("$group", group);

		pipeline.add(groupBy);

		Bson projection = Projections.fields(
				Projections.excludeId(),
				Projections.computed(PROFILE_ID, "$_id." + PROFILE_ID));
		if (isActivatedAccountsIndicator) {
			projection = Projections.fields(projection, Projections.include(STATS_FIELD_ACCOUNTS));
		}

		if (!isExport) {
			projection = Projections.fields(projection, Projections.include(indicator));

			if (isAccessAllModules) {
				projection = Projections.fields(projection, Projections.computed(MODULE_ID, "$_id." + MODULE_ID));
			} else {
				projection = Projections.fields(projection, Projections.computed(STATS_FIELD_DATE, "$_id." + STATS_FIELD_DATE));
			}

			// Sum stats for all structure_ids
			pipeline.add(new JsonObject().put("$project", MongoQueryBuilder.build(projection)));
		} else {
			// Projection : keep 'yyyy-MM' from 'yyyy-MM-dd HH:mm.ss.SSS'
			BasicDBList substrParams = new BasicDBList();
			substrParams.add("$_id." + STATS_FIELD_DATE);
			substrParams.add(0);
			substrParams.add(7);
			BasicDBObject dateSubstring = new BasicDBObject("$substr", substrParams);

			projection = Projections.fields(
					projection,
					Projections.computed(STATS_FIELD_DATE, dateSubstring),
					Projections.computed("indicatorValue", "$" + indicator) // Replace indicatorName by label "indicatorValue"
			);

			JsonObject sort = sortByStructureDateProfile;

			// Export stats for each structure_id
			id.put(STRUCTURES_ID, "$" + STRUCTURES_ID);
			projection = Projections.fields(projection, Projections.computed(STRUCTURES_ID, "$_id." + STRUCTURES_ID));

			if (isAccessIndicator) {
				if (isAccessAllModules) {
					sort = sort.copy().put(MODULE_ID, 1);
				}

				// Export stats for each module_id
				id.put(MODULE_ID, "$" + MODULE_ID);
				projection = Projections.fields(projection, Projections.computed(MODULE_ID, "$_id." + MODULE_ID));
			}


			pipeline.add(new JsonObject().put("$project", MongoQueryBuilder.build(projection)));
			pipeline.add(new JsonObject().put("$sort", sort));
		}

		mongo.command(aggregation.toString(), new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> message) {
				if ("ok".equals(message.body().getString("status")) && message.body().getJsonObject("result", new JsonObject()).getInteger("ok") == 1){
					JsonObject messageResult = message.body().getJsonObject("result", new JsonObject());
					JsonArray results = messageResult.getJsonObject("cursor", new JsonObject()).getJsonArray("firstBatch", new JsonArray());
					handler.handle(new Either.Right<String, JsonArray>(results));
				} else {
					String error = message.body().toString();
					handler.handle(new Either.Left<String, JsonArray>(error));
				}
			}
		});
	}

}
