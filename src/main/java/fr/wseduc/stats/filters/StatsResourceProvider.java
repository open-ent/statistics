package fr.wseduc.stats.filters;

import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.http.filter.ResourcesProvider;
import org.entcore.common.user.UserInfos;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;


import fr.wseduc.webutils.http.Binding;

import static org.entcore.common.user.DefaultFunctions.SUPER_ADMIN;

public class StatsResourceProvider implements ResourcesProvider {


    @Override
    public void authorize(final HttpServerRequest request, Binding binding, final UserInfos user, final Handler<Boolean> handler) {

        //Super-admin "hack"
        if(user.getFunctions().containsKey(SUPER_ADMIN)) {
            handler.handle(true);
            return;
        }

        if ("Relative".equals(user.getType()) || "Student".equals(user.getType())) {
            handler.handle(false);
            return;
        }

        final String entity = request.params().get("entity");
        if (entity == null || entity.trim().isEmpty()) {
            handler.handle(false);
            return;
        }

        final String entityLevel = request.params().get("entityLevel");
        if (entityLevel == null || entityLevel.trim().isEmpty()) {
            handler.handle(false);
            return;
        } else if ("structure".equals(entityLevel)) {
            final String query =
                    "MATCH (u:User {id: $userId})-[:IN]->(pg)-[:DEPENDS]->(s:Structure {id: $entityId}) " +
                            "WHERE (pg:ProfileGroup OR pg:FunctionGroup) " +
                            "RETURN count(*) > 0 as exists ";
            JsonObject params = new JsonObject()
                    .put("entityId", entity)
                    .put("userId", user.getUserId());
            request.pause();
            Neo4j.getInstance().execute(query, params, new Handler<Message<JsonObject>>() {
                @Override
                public void handle(Message<JsonObject> r) {
                    request.resume();
                    JsonArray res = r.body().getJsonArray("result");
                    if ("ok".equals(r.body().getString("status")) && res.size() == 1 &&
                            ((JsonObject) res.getJsonObject(0)).getBoolean("exists", false)) {
                        handler.handle(true);
                    } else {
                        handler.handle(false);
                    }
                }
            });
        } else if ("class".equals(entityLevel)) {
            // check if user is ADML of the class structure OR if user is attached to class
            final String queryAdml =
                    "MATCH (u:User {id: $userId}) " +
                            "WHERE (u)-[:IN]->(:FunctionGroup)-[:DEPENDS]->(:Structure)<-[:BELONGS]-(:Class {id: $classId}) " +
                            "OR (u)-[:IN]->(:ProfileGroup)-[:DEPENDS]->(:Class {id: $classId}) " +
                            "RETURN count(*) > 0 as exists ";
            JsonObject params = new JsonObject()
                    .put("classId", entity)
                    .put("userId", user.getUserId());
            request.pause();
            Neo4j.getInstance().execute(queryAdml, params, new Handler<Message<JsonObject>>() {
                @Override
                public void handle(Message<JsonObject> r) {
                    request.resume();
                    JsonArray res = r.body().getJsonArray("result");
                    if ("ok".equals(r.body().getString("status")) && res.size() == 1 &&
                            ((JsonObject) res.getJsonObject(0)).getBoolean("exists", false)) {
                        handler.handle(true);
                    } else {
                        handler.handle(false);
                    }
                }
            });
        } else {
            handler.handle(false);
        }
    }
}
