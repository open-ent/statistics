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

import static org.entcore.common.aggregation.MongoConstants.TRACE_FIELD_MODULE;
import static org.entcore.common.aggregation.MongoConstants.TRACE_FIELD_PROFILE;
import static org.entcore.common.aggregation.MongoConstants.TRACE_FIELD_STRUCTURES;
import static org.entcore.common.aggregation.MongoConstants.TRACE_FIELD_USER;
import static org.entcore.common.aggregation.MongoConstants.TRACE_TYPE_ACTIVATION;
import static org.entcore.common.aggregation.MongoConstants.TRACE_TYPE_CONNEXION;
import static org.entcore.common.aggregation.MongoConstants.TRACE_TYPE_SVC_ACCESS;
import static org.entcore.common.aggregation.MongoConstants.TRACE_TYPE_CONNECTOR;
import static net.atos.entng.statistics.aggregation.indicators.IndicatorConstants.STATS_FIELD_UNIQUE_VISITORS;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;

import org.bson.conversions.Bson;
import org.entcore.common.aggregation.MongoConstants.COLLECTIONS;
import org.entcore.common.aggregation.filters.IndicatorFilter;
import org.entcore.common.aggregation.filters.dbbuilders.MongoDBBuilder;
import org.entcore.common.aggregation.filters.mongo.IndicatorFilterMongoImpl;
import org.entcore.common.aggregation.groups.IndicatorGroup;
import org.entcore.common.aggregation.indicators.Indicator;
import org.entcore.common.aggregation.indicators.mongo.IndicatorMongoImpl;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import fr.wseduc.mongodb.MongoQueryBuilder;
import fr.wseduc.mongodb.MongoUpdateBuilder;

public class IndicatorFactory {

	// 1) Indicators that are incremented every day
	// UserAccount Activation
	public static Indicator getActivationIndicator(Collection<IndicatorFilter> filters, Date pWriteDate) {
        Collection<IndicatorGroup> indicatorGroups = new ArrayList<>();
        indicatorGroups.add(new IndicatorGroup(TRACE_FIELD_STRUCTURES).setArray(true)
                .addAndReturnChild(TRACE_FIELD_PROFILE)
        );
        
        IndicatorMongoImpl indicator = new IndicatorMongoImpl(TRACE_TYPE_ACTIVATION, filters, indicatorGroups){
            @Override
            protected void customizePipeline(JsonArray pipeline){
                int triggerSize = 0;
                // Remove "count" from stage "$group" in pipeline, add userCount instead, and add "userId" to field _id
                for (int i = 0; i < pipeline.size(); i++) {
                    JsonObject stage = pipeline.getJsonObject(i);
                    JsonObject group = stage.getJsonObject("$group", null);
                    if(group != null) {
                        group.remove("count");
                        JsonObject id = group.getJsonObject("_id", null);
                        if(id != null && id.size() == 0) {
                            id.put(TRACE_FIELD_USER, "$"+TRACE_FIELD_USER);
                        } else if(id != null && id.size() > 0) {// We are in a "structures" stats query 
                            id.put(TRACE_FIELD_STRUCTURES, "$"+TRACE_FIELD_STRUCTURES).put(TRACE_FIELD_PROFILE, "$"+TRACE_FIELD_PROFILE).put(TRACE_FIELD_USER, "$"+TRACE_FIELD_USER);
                            triggerSize = 1;
                        }
                        group.put("userCount", new JsonObject().put("$min", 1));
                        break;
                    }
                }

                // Add another "$group" stage in pipeline, to count unique activation per user
                JsonObject groupByActiv = new JsonObject();
                if(triggerSize == 0){
                    groupByActiv.put("$group", new JsonObject()
                    .put("_id", new JsonObject())
                    .put("count", new JsonObject().put("$sum", "$userCount")));
                } else {
                    groupByActiv.put("$group", new JsonObject()
                    .put("_id", new JsonObject().put(TRACE_FIELD_STRUCTURES, "$_id."+TRACE_FIELD_STRUCTURES).put(TRACE_FIELD_PROFILE, "$_id."+TRACE_FIELD_PROFILE))
                    .put("count", new JsonObject().put("$sum", "$userCount")));
                }
                pipeline.add(groupByActiv);
            }
            
        };
        
        indicator.setWriteDate(pWriteDate);
        return indicator;
    }

	// Connections
	public static Indicator getConnectionIndicator(Collection<IndicatorFilter> filters, Date pWriteDate) {
		Collection<IndicatorGroup> indicatorGroups = new ArrayList<IndicatorGroup>();
		indicatorGroups.add(new IndicatorGroup(TRACE_FIELD_STRUCTURES).setArray(true)
			.addAndReturnChild(TRACE_FIELD_PROFILE));

		Indicator indicator = new IndicatorMongoImpl(TRACE_TYPE_CONNEXION, filters, indicatorGroups);
		indicator.setWriteDate(pWriteDate);
		return indicator;
	}

	// Access to applications
	public static Indicator getAccessIndicator(Collection<IndicatorFilter> filters, Date pWriteDate) {
		Collection<IndicatorGroup> indicatorGroups = new ArrayList<IndicatorGroup>();
		indicatorGroups.add(new IndicatorGroup(TRACE_FIELD_MODULE)
			.addAndReturnChild(new IndicatorGroup(TRACE_FIELD_STRUCTURES).setArray(true))
			.addAndReturnChild(TRACE_FIELD_PROFILE));

		Indicator indicator = new IndicatorMongoImpl(TRACE_TYPE_SVC_ACCESS, filters, indicatorGroups);
		indicator.setWriteDate(pWriteDate);
		return indicator;
	}
	public static Indicator getConnectorsIndicator(Collection<IndicatorFilter> filters, Date pWriteDate) {
		Collection<IndicatorGroup> indicatorGroups = new ArrayList<IndicatorGroup>();
		indicatorGroups.add(new IndicatorGroup(TRACE_FIELD_MODULE)
				.addAndReturnChild(new IndicatorGroup(TRACE_FIELD_STRUCTURES).setArray(true))
				.addAndReturnChild(TRACE_FIELD_PROFILE));

		Indicator indicator = new IndicatorMongoImpl(TRACE_TYPE_CONNECTOR, filters, indicatorGroups);
		indicator.setWriteDate(pWriteDate);
		return indicator;
	}

	// 2) Indicators that are recomputed every day
	// Accounts and activated accounts
	public static Indicator getActivatedAccountsIndicator(Date pWriteDate) {
		IndicatorCustomImpl indicator = new IndicatorCustomImpl();
		indicator.setWriteDate(pWriteDate);
		return indicator;
	}

	// Unique visitors
	public static Indicator getUniqueVisitorsIndicator(Collection<IndicatorFilter> filters, Date pWriteDate) {
		Collection<IndicatorGroup> indicatorGroups = new ArrayList<IndicatorGroup>();
		final IndicatorGroup profileIg = new IndicatorGroup(TRACE_FIELD_STRUCTURES).setArray(true)
			.addAndReturnChild(TRACE_FIELD_PROFILE);
		indicatorGroups.add(profileIg);

		IndicatorMongoImpl indicator = new IndicatorMongoImpl(TRACE_TYPE_CONNEXION, filters, indicatorGroups){
			@Override
			protected void customizePipeline(JsonArray pipeline){
				// Remove "count" from stage "$group" in pipeline, and add "userId" to field _id
				for (int i = 0; i < pipeline.size(); i++) {
					JsonObject stage = pipeline.getJsonObject(i);
					JsonObject group = stage.getJsonObject("$group", null);
					if(group != null) {
						group.remove("count");
						JsonObject id = group.getJsonObject("_id", null);
						if(id != null) {
							id.put(TRACE_FIELD_USER, "$"+TRACE_FIELD_USER);
						}
						break;
					}
				}

				// Add another "$group" stage in pipeline, to count unique visitors
				JsonObject groupBy = new JsonObject().put("$group", new JsonObject()
				.put("_id", getGroupByObject(new JsonObject(), profileIg))
				.put("count", new JsonObject().put("$sum", 1)));
				pipeline.add(groupBy);
			}

			@Override
			// Set the indicator's value (instead of incrementing it)
			protected void writeAction(Bson criteriaQuery, int resultsCount, Handler<Message<JsonObject>> handler){
				mongo.update(COLLECTIONS.stats.name(),
						MongoQueryBuilder.build(criteriaQuery),
						new MongoUpdateBuilder().set(this.getWriteKey(), resultsCount).build(),
						true,
						true,
						handler);
			}


		};
		indicator.setWriteKey(STATS_FIELD_UNIQUE_VISITORS);
		indicator.setWriteDate(pWriteDate);

		return indicator;
	}

	// Build the "_id" object for stage "$group"
	private static JsonObject getGroupByObject(JsonObject accumulator, IndicatorGroup group){
		if(group == null)
			return accumulator;

		return getGroupByObject(accumulator, group.getParent()).put(group.getKey(), "$_id."+group.getKey());
	}


}
