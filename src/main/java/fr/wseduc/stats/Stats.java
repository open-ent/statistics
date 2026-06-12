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

package fr.wseduc.stats;

import java.text.ParseException;
import java.util.Base64;
import java.util.Map;

import fr.wseduc.webutils.collections.SharedDataHelper;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.pgclient.SslMode;
import io.vertx.sqlclient.PoolOptions;
import org.entcore.common.aggregation.MongoConstants.COLLECTIONS;
import org.entcore.common.events.EventHelper;
import org.entcore.common.events.EventStore;
import org.entcore.common.events.EventStoreFactory;
import org.entcore.common.http.BaseServer;
import org.entcore.common.mongodb.MongoDbConf;

import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import fr.wseduc.mongodb.MongoDb;

import fr.wseduc.cron.CronTrigger;
import fr.wseduc.stats.controllers.JobsController;
import fr.wseduc.stats.controllers.StatsController;
import fr.wseduc.stats.cron.CronAggregationTask;
import fr.wseduc.stats.filters.WorkflowFilter;
import fr.wseduc.stats.services.DefaultJobsServiceImpl;
import fr.wseduc.stats.services.MockStatsService;
import fr.wseduc.stats.services.PGStatsService;
import fr.wseduc.stats.services.StatsService;
import fr.wseduc.stats.services.StatsServiceMongoImpl;

import com.opendigitaleducation.repository.SyncRepository;

public class Stats extends BaseServer {

	private static final Logger logger = LoggerFactory.getLogger(Stats.class);

	@Override
	public void start(final Promise<Void> startPromise) throws Exception {
		Promise<Void> promise = Promise.promise();
		super.start(promise);
		promise.future()
				.compose(init -> SharedDataHelper.getInstance().getMulti("server", "event-store", "neo4jConfig"))
				.compose(statsConfigMap -> initStats(statsConfigMap))
				.onComplete(startPromise);
	}

	private Future<Void> initStats(final Map<String, Object> statsConfigMap) {

		// CRON
		// Default at 00:00AM every day
		final String aggregationCron = config.getString("aggregation-cron");
		// Day delta, default : processes yesterday events
		int dayDelta = config.getInteger("dayDelta", -1);

		if (aggregationCron != null && !aggregationCron.trim().isEmpty()) {
			try {
				new CronTrigger(vertx, aggregationCron).schedule(new CronAggregationTask(dayDelta));
			} catch (ParseException e) {
				logger.fatal(e.getMessage(), e);
				vertx.close();
				return Future.failedFuture(e);
			}
		}

		final String platformId;
		final String eventStoreConf = (String) statsConfigMap.get("event-store");
		final JsonObject eventStoreConfig;
		if (eventStoreConf != null) {
			eventStoreConfig = new JsonObject(eventStoreConf);
			platformId = eventStoreConfig.getString("platform");
		} else {
			platformId = null;
			eventStoreConfig = null;
		}

		final StatsService statsService;
		final JsonObject readPGConfig = config.getJsonObject("read-pg-config");
		final JsonObject pgConfig = config.getJsonObject("pg-config");
		final boolean oldStats = config.getBoolean("mongo-stats-service", false);
		if (pgConfig != null && !pgConfig.isEmpty() && !oldStats) {
			final PgConnectOptions connectOptions = new PgConnectOptions().setPort(pgConfig.getInteger("port", 5432))
					.setHost(pgConfig.getString("host")).setDatabase(pgConfig.getString("database"))
					.setUser(pgConfig.getString("user")).setPassword(pgConfig.getString("password"));
			final SslMode sslMode = SslMode.valueOf(pgConfig.getString("ssl-mode", "DISABLE"));
			if (!SslMode.DISABLE.equals(sslMode)) {
				connectOptions.setSslMode(sslMode).setTrustAll(SslMode.ALLOW.equals(sslMode) || SslMode.PREFER.equals(sslMode) || SslMode.REQUIRE.equals(sslMode));
			}
			PoolOptions poolOptions = new PoolOptions().setMaxSize(pgConfig.getInteger("pool-size", 5));
			PgPool pgPool = PgPool.pool(vertx, connectOptions, poolOptions);

			// SyncRepository with neo4j config
			final String neo4jConfig = (String) statsConfigMap.get("neo4jConfig");
			final JsonObject neo4jConfigJson = new JsonObject(neo4jConfig);
			final String neo4jUserName = neo4jConfigJson.getString("username");
			final String neo4jPassword = neo4jConfigJson.getString("password");
			final String neo4jUri = neo4jConfigJson.getJsonArray("server-uris",
					new JsonArray().add(neo4jConfigJson.getString("server-uri"))).getString(0);
			final JsonObject syncRepositoryConfig = new JsonObject()
					.put("neo4j-uri", neo4jUri)
					.put("platform-id", platformId)
					.put("neo4j-auth", Base64.getEncoder().encodeToString((neo4jUserName + ":" + neo4jPassword).getBytes()));
			final SyncRepository syncRepository = new SyncRepository(vertx, new JsonArray().add(syncRepositoryConfig));

			final DefaultJobsServiceImpl jobsService = new DefaultJobsServiceImpl(vertx, platformId, config.getJsonObject("api-allowed-values"));
			jobsService.setPgPool(pgPool);
			jobsService.setSyncRepository(syncRepository);
			final JobsController jobsController = new JobsController();
			jobsController.setJobsService(jobsService);
			addController(jobsController);
		}
		if (Boolean.TRUE.equals(config.getBoolean("mock", false))) {
			statsService = new MockStatsService(vertx, config.getString("mocks-path"));
		} else if (readPGConfig != null && !readPGConfig.isEmpty() && !oldStats) {
			final PgConnectOptions connectOptions = new PgConnectOptions().setPort(readPGConfig.getInteger("port", 5432))
					.setHost(readPGConfig.getString("host")).setDatabase(readPGConfig.getString("database"))
					.setUser(readPGConfig.getString("user")).setPassword(readPGConfig.getString("password"));
			final SslMode sslMode = SslMode.valueOf(readPGConfig.getString("ssl-mode", "DISABLE"));
			if (!SslMode.DISABLE.equals(sslMode)) {
				connectOptions.setSslMode(sslMode).setTrustAll(SslMode.ALLOW.equals(sslMode) || SslMode.PREFER.equals(sslMode) || SslMode.REQUIRE.equals(sslMode));
			}
			PoolOptions poolOptions = new PoolOptions().setMaxSize(readPGConfig.getInteger("pool-size", 5));
			PgPool pgPool = PgPool.pool(vertx, connectOptions, poolOptions);
			statsService = new PGStatsService(platformId, config.getJsonObject("api-allowed-values"));
			((PGStatsService) statsService).setReadPgPool(pgPool);
		} else if (eventStoreConfig != null && eventStoreConfig.getJsonObject("postgresql-slave") != null && !oldStats) {
			final JsonObject eventStorePGConfig = eventStoreConfig.getJsonObject("postgresql-slave");
			final PgConnectOptions connectOptions = new PgConnectOptions()
				.setPort(eventStorePGConfig.getInteger("port", 5432))
				.setHost(eventStorePGConfig.getString("host"))
				.setDatabase(eventStorePGConfig.getString("database"))
				.setUser(eventStorePGConfig.getString("user"))
				.setPassword(eventStorePGConfig.getString("password"));
			final SslMode sslMode = SslMode.valueOf(eventStorePGConfig.getString("ssl-mode", "DISABLE"));
			if (!SslMode.DISABLE.equals(sslMode)) {
				connectOptions.setSslMode(sslMode).setTrustAll(SslMode.ALLOW.equals(sslMode) || SslMode.PREFER.equals(sslMode) || SslMode.REQUIRE.equals(sslMode));
			}
			PoolOptions poolOptions = new PoolOptions().setMaxSize(eventStorePGConfig.getInteger("pool-size", 5));
			PgPool pgPool = PgPool.pool(vertx, connectOptions, poolOptions);
			statsService = new PGStatsService(platformId, config.getJsonObject("api-allowed-values"));
			((PGStatsService) statsService).setReadPgPool(pgPool);
        } else {
			statsService = new StatsServiceMongoImpl(COLLECTIONS.stats.name());
		}

		final StatsController statsController = new StatsController(COLLECTIONS.stats.name());
		statsController.setStatsService(statsService);
		final EventStore eventStore = EventStoreFactory.getFactory().getEventStore(Stats.class.getSimpleName());
		final EventHelper eventHelper =  new EventHelper(eventStore);
		statsController.setEventHelper(eventHelper);

		// REST BASICS
		addController(statsController);
		MongoDbConf.getInstance().setCollection(COLLECTIONS.stats.name());
		addFilter(new WorkflowFilter(this.vertx.eventBus(), "stats.view", "fr.wseduc.stats.controllers.StatsController|view"));

		// Index the stats collection on the criteria used by the nightly aggregation upserts
		// (IndicatorMongoImpl.writeStats). Without it each of the ~thousands of upserts does a
		// full collection scan, saturating MongoDB and triggering connection/reply timeouts.
		ensureStatsIndexes(COLLECTIONS.stats.name());

		return Future.succeededFuture();
	}

	/**
	 * Idempotently create the compound index used by the aggregation write path.
	 * Re-running with the same specification is a no-op on MongoDB.
	 */
	private void ensureStatsIndexes(final String collection) {
		final JsonObject index = new JsonObject()
				.put("key", new JsonObject()
						.put("date", 1)
						.put("groupedBy", 1)
						.put("structures_id", 1)
						.put("classes_id", 1)
						.put("profil_id", 1)
						.put("module_id", 1))
				.put("name", "stats_aggregation_idx")
				.put("background", true);
		final JsonObject command = new JsonObject()
				.put("createIndexes", collection)
				.put("indexes", new JsonArray().add(index));
		MongoDb.getInstance().command(command.encode(), (Message<JsonObject> message) -> {
			if (!"ok".equals(message.body().getString("status"))) {
				logger.error("[Stats] Failed to ensure stats_aggregation_idx : " + message.body().encode());
			} else {
				logger.info("[Stats] stats_aggregation_idx ensured on collection " + collection);
			}
		});
	}

}
