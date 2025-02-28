package net.atos.entng.statistics.services;

import fr.wseduc.mongodb.MongoDb;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.elasticsearch.BulkRequestOld;
import org.entcore.common.elasticsearch.ElasticSearch;

public class ElasticSearchEventsSync implements Handler<Long> {

	private final MongoDb mongo = MongoDb.getInstance();
	private final ElasticSearch es = ElasticSearch.getInstance();
	private static final int BULK_SIZE = 10000;
	public static final String EVENTS = "events";
	private static final Logger log = LoggerFactory.getLogger(ElasticSearchEventsSync.class);
	private final long timeout;

	public ElasticSearchEventsSync(Vertx vertx) {
		this.timeout = vertx.getOrCreateContext().config().getLong("delete-users-timeout", 300000L);
	}

	@Override
	public void handle(Long aLong) {
		log.info("Events sync started.");
		sync();
	}

	private void sync() {
		final long started = System.currentTimeMillis();
		final JsonObject query = new JsonObject().put("synced", new JsonObject().put("$exists", false));
		final JsonObject sort = new JsonObject().put("date", 1);
		mongo.find(EVENTS, query, sort, null, 0, BULK_SIZE, BULK_SIZE,
				new DeliveryOptions().setSendTimeout(timeout),res -> {
			final JsonArray results = res.body().getJsonArray("results");
			final int resultsSize;
			if ("ok".equals(res.body().getString("status")) &&
					results != null && (resultsSize = results.size()) > 0) {
				final JsonArray eventsIds = new JsonArray();
				Future<BulkRequestOld> bulkRequestFuture = es.bulk(EVENTS, ar -> {
					if (ar.succeeded()) {
						JsonArray items = ar.result().getJsonArray("items");
						if (items.size() != resultsSize) {
							log.error("Error different sync length. Expected : " + resultsSize +
									" - Found : " + items.size());
							return;
						}
						int countWarningItems = 0;
						for (Object o: items) {
							final int itemStatus = ((JsonObject) o).getJsonObject("index").getInteger("status");
							if (itemStatus != 201) {
								if (itemStatus == 200) {
									countWarningItems++;
									//log.warn("Update event in ES : " + ((JsonObject) o).encode());
								} else {
									log.error("Error persisting event in ES : " + ((JsonObject) o).encode());
									return;
								}
							}
						}
						if (countWarningItems > 0) {
							log.warn("Update " + countWarningItems + " events in ES.");
						}
						final JsonObject modifier = new JsonObject()
								.put("$set", new JsonObject().put("synced", MongoDb.now()));
						final JsonObject criteria2 = new JsonObject()
								.put("_id", new JsonObject().put("$in", eventsIds));
						mongo.update(EVENTS, criteria2, modifier, false, true, null,
								new DeliveryOptions().setSendTimeout(timeout), ru2 -> {
							if ("ok".equals(ru2.body().getString("status"))) {
								if (resultsSize == BULK_SIZE) {
									sync();
								}
								log.info(resultsSize + " events synced to ElasticSearch in " + (System.currentTimeMillis() - started) + "ms.");
							} else {
								log.error("Error when set synced date.");
							}
						});
					} else {
						log.error("Error sending events to elasticsearch", ar.cause());
					}
				});
				bulkRequestFuture.onSuccess(bulkRequest -> {
					for (Object o : results) {
						JsonObject j = (JsonObject) o;
						eventsIds.add(j.getString("_id"));
						bulkRequest.index(j, null);
					}
					bulkRequest.end();
				});
			} else if (!"ok".equals(res.body().getString("status"))){
				log.error("Error getting events in mongodb : " + res.body().getString("message"));
			} else {
				log.info("Empty res");
			}
		});
	}
}
