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

package net.atos.entng.statistics.aggregation.indicators;

import static org.entcore.common.neo4j.Neo4jResult.validResultHandler;
import static org.entcore.common.aggregation.MongoConstants.TRACE_FIELD_PROFILE;
import static org.entcore.common.aggregation.MongoConstants.TRACE_FIELD_STRUCTURES;
import static org.entcore.common.aggregation.MongoConstants.STATS_FIELD_DATE;
import static org.entcore.common.aggregation.MongoConstants.STATS_FIELD_GROUPBY;
import static net.atos.entng.statistics.services.StatisticsServiceMongoImpl.PROFILE_ID;
import static net.atos.entng.statistics.services.StatisticsServiceMongoImpl.STRUCTURES_ID;
import static net.atos.entng.statistics.aggregation.indicators.IndicatorConstants.STATS_FIELD_ACCOUNTS;
import static net.atos.entng.statistics.aggregation.indicators.IndicatorConstants.STATS_FIELD_ACTIVATED_ACCOUNTS;

import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import com.mongodb.client.model.Filters;
import org.bson.conversions.Bson;
import org.entcore.common.aggregation.MongoConstants.COLLECTIONS;
import org.entcore.common.aggregation.filters.dbbuilders.MongoDBBuilder;
import org.entcore.common.aggregation.indicators.Indicator;
import org.entcore.common.neo4j.Neo4j;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.mongodb.MongoQueryBuilder;
import fr.wseduc.mongodb.MongoUpdateBuilder;
import fr.wseduc.webutils.Either;

/**
 * Read data on (activated) accounts from Neo4j and write it to MongoDB in collection "stats"
 */
public class IndicatorCustomImpl extends Indicator {

	private MongoDb mongo;
	private Neo4j neo;

	protected static final Logger log = LoggerFactory.getLogger(IndicatorCustomImpl.class);

	public IndicatorCustomImpl() {
		super(STATS_FIELD_ACTIVATED_ACCOUNTS);
		mongo = MongoDb.getInstance();
		neo = Neo4j.getInstance();
	}

	@Override
	public void aggregate(final Handler<JsonObject> callBack) {
		final Date start = new Date();

		this.getAccounts(new Handler<Either<String, JsonArray>>() {
			@Override
			public void handle(Either<String, JsonArray> event) {
				if(event.isLeft()) {
					log.error(event.left().getValue());
					callBack.handle(new JsonObject());
					return;
				}

				try {
					JsonArray results = event.right().getValue();

					// If no documents found, write nothing
					if(results.size() == 0){
						callBack.handle(new JsonObject());
						return;
					}

					// Synchronization handler
					final AtomicInteger countDown = new AtomicInteger(results.size());
					Handler<Message<JsonObject>> synchroHandler = new Handler<Message<JsonObject>>() {
						@Override
						public void handle(Message<JsonObject> message) {
							if (!"ok".equals(message.body().getString("status"))){
								log.error("Error in method aggregate of IndicatorCustomImpl : "+message.body().toString());
							}

							if(countDown.decrementAndGet() == 0){
								final Date end = new Date();
								log.info("[Aggregation]{"+IndicatorCustomImpl.this.getKey()+"} Took ["+(end.getTime() - start.getTime())+"] ms");
								callBack.handle(new JsonObject().put("status", "ok"));
							}
						}
					};

					for (int i = 0; i < results.size(); i++) {
						JsonObject jo = results.getJsonObject(i);

						String structure = jo.getString("structure");
						String profile = jo.getString("profile");
						Number accounts = jo.getInteger("accounts");
						Number activatedAccounts = jo.getInteger("activatedAccounts");

						String date = MongoDb.formatDate(IndicatorCustomImpl.this.getWriteDate());

						Bson criteriaQuery = Filters.and(
								Filters.eq(STATS_FIELD_DATE, date),
								Filters.eq(STATS_FIELD_GROUPBY, TRACE_FIELD_STRUCTURES + "/" + TRACE_FIELD_PROFILE),
								Filters.eq(PROFILE_ID, profile),
								Filters.eq(STRUCTURES_ID, structure)
						);

						MongoUpdateBuilder update = new MongoUpdateBuilder()
							.set(STATS_FIELD_ACCOUNTS, accounts)
							.set(STATS_FIELD_ACTIVATED_ACCOUNTS, activatedAccounts);

						// Upsert data in MongoDB
						mongo.update(COLLECTIONS.stats.name(),
								MongoQueryBuilder.build(criteriaQuery),
								update.build(),
								true,
								true,
								synchroHandler);
					}
				} catch (Exception e) {
					log.error(e.getMessage(), e);
					callBack.handle(new JsonObject());
				}

			}
		});
	}

	private void getAccounts(Handler<Either<String, JsonArray>> handler) {
		// Number of accounts and activated accounts per profile and structure
		String query = "MATCH (u:User)-[:IN]->(:ProfileGroup)-[:DEPENDS]->(s:Structure)"
				+ " RETURN s.id AS structure, HEAD(u.profiles) AS profile, count(distinct u) AS accounts,"
				+ " count(distinct u.password) AS activatedAccounts ORDER by structure, profile";

		neo.execute(query, new JsonObject(), validResultHandler(handler));
	}


}
