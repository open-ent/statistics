package net.atos.entng.statistics.aggregation.indicators;

import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.security.Sha256;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import net.atos.entng.statistics.services.ElasticSearchEventsSync;
import org.entcore.common.elasticsearch.BulkRequestOld;
import org.entcore.common.elasticsearch.ElasticSearch;
import org.entcore.common.neo4j.Neo4j;
import org.joda.time.DateTime;

import java.security.NoSuchAlgorithmException;

import static org.entcore.common.neo4j.Neo4jResult.validResultHandler;

public class ESActivatedAccountsIndicatorImpl implements Handler<Long> {

	private static final Logger log = LoggerFactory.getLogger(ESActivatedAccountsIndicatorImpl.class);
	private final ElasticSearch es = ElasticSearch.getInstance();

	@Override
	public void handle(Long aLong) {
		final Long start = System.currentTimeMillis();
		getAccounts(event -> {
			if(event.isLeft()) {
				log.error(event.left().getValue());
				return;
			}

			JsonArray results = event.right().getValue();
			if(results.size() == 0){
				return;
			}

			Future<BulkRequestOld> bulkRequestFuture = es.bulk(ElasticSearchEventsSync.EVENTS, ar -> {
				if (ar.succeeded()) {
					log.info("[Aggregation]{ActivatedAccounts} Took [" + (System.currentTimeMillis() - start) + "] ms");
				} else {
					log.error("Error when aggregate ActivatedAccounts : " + ar.cause().getMessage());
				}
			});
			bulkRequestFuture.onSuccess(bulkRequest -> {
				long date = new DateTime().dayOfMonth().withMinimumValue().withTimeAtStartOfDay().getMillis();
				for (Object o : results) {
					JsonObject j = (JsonObject) o;
					j.put("date", date);
					j.put("event-type", IndicatorConstants.STATS_FIELD_ACCOUNTS);
					final String id;
					try {
						id = Sha256.hash(date + j.getString("profil") +
								j.getJsonArray("structures").getString(0));
					} catch (NoSuchAlgorithmException e) {
						log.error("Error hashing id.", e);
						continue;
					}
					bulkRequest.index(j, new JsonObject().put("_id", id));
				}
				bulkRequest.end();
			});
		});
	}

	private void getAccounts(Handler<Either<String, JsonArray>> handler) {
		final String query =
				"MATCH (s:Structure)<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u:User) " +
				"RETURN [s.id] AS structures, HEAD(u.profiles) AS profil, count(distinct u) AS accounts, " +
				"count(distinct u.password) AS activatedAccounts ";
		Neo4j.getInstance().execute(query, new JsonObject(), validResultHandler(handler));
	}

}
