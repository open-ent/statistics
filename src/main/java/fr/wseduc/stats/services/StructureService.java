package fr.wseduc.stats.services;

import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.neo4j.Neo4jResult;

import fr.wseduc.webutils.Either;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class StructureService {
    private final Neo4j neo4j = Neo4j.getInstance();

    public void getStructuresForUser(String userId, boolean hierarchical,
            Handler<Either<String, JsonArray>> handler) {
//        final String structPart = hierarchical ? "(s:Structure)<-[:HAS_ATTACHMENT*0..]-(s2:Structure)"
//                : "(s2:Structure)";
//        final String query = "match (u:User)-[IN]->(pg:ProfileGroup)-[DEPENDS]-> " + structPart
//                + " where u.id = {userId} "
//                + " return distinct s2.id as id, s2.name as name ";
//        final JsonObject params = new JsonObject().put("userId", userId);
//        neo4j.execute(query, params, Neo4jResult.validResultHandler(handler));

        final String structPart = hierarchical ? "(s:Structure)<-[:HAS_ATTACHMENT*0..]-(s2:Structure)"
                : "(s2:Structure)";
        final String query = "MATCH (u:User {id: {userId}})-[:IN]->(pg)-[:DEPENDS]->(s:Structure)" +
                " WHERE (pg:ProfileGroup OR pg:FunctionGroup) " +
                " OPTIONAL MATCH (s)-[r:HAS_ATTACHMENT]->(ps:Structure)" +
                " WITH s, COLLECT({id: ps.id, name: ps.name}) as parents" +
                " return distinct s.id as id, s.name as name, parents as parents " +
                " ORDER BY name ";
        final JsonObject params = new JsonObject().put("userId", userId);
        neo4j.execute(query, params, Neo4jResult.validResultHandler(handler));
    }

    public void getClassesForUser(String userId, Handler<Either<String, JsonArray>> handler) {
        final String query = "match (u:User {id: {userId}})-[:IN]->(pg:ProfileGroup)-[:DEPENDS]->(c:Class)-[:BELONGS]->(s:Structure) "
                + " where (pg:ProfileGroup OR pg:FunctionGroup) "
                + " return distinct c.id as id, c.name as name " +
                " ORDER BY name ";
        final JsonObject params = new JsonObject().put("userId", userId);
        neo4j.execute(query, params, Neo4jResult.validResultHandler(handler));
    }

    public void getStructuresHierarchyAndClasses(String userId, Handler<Either<String, JsonArray>> handler) {
        // Deux sources d'établissements, unies : (1) l'appartenance directe (structure
        // d'affectation, via ProfileGroup/FunctionGroup) et (2) le périmètre accordé par
        // une fonction à portée élargie (ADMIN_LOCAL, ADMIN_COLLECTIVITE) — porté par
        // rf.scope sur HAS_FUNCTION, pas par une appartenance à un groupe de CES
        // établissements-là. Sans (2), un profil Collectivité ne voit ici que sa structure
        // de rattachement (ex. une DSDEN) et jamais les établissements de son territoire.
        final String query =
                "MATCH (:User {id: {userId}})-[:IN]->(pg)-[:DEPENDS]->(s:Structure)" +
                " WHERE (pg:ProfileGroup OR pg:FunctionGroup) " +
                " OPTIONAL MATCH (s)-[:HAS_ATTACHMENT]->(ps:Structure)<-[:DEPENDS]-(g)<-[:IN]-(:User {id: {userId}})" +
                " WHERE (g:ProfileGroup OR g:FunctionGroup) " +
                " OPTIONAL MATCH (:User {id: {userId}})-[:IN]->(:ProfileGroup)-[:DEPENDS]->(c:Class)-[:BELONGS]->(s)" +
                " WITH s, COLLECT(distinct {id: ps.id, name: ps.name}) as parents, COLLECT(distinct {id: c.id, name: c.name}) as classes" +
                " WITH DISTINCT s, CASE WHEN any(p in parents where p <> {id: null, name: null}) THEN parents END as parents," +
                " CASE WHEN any(c in classes where c <> {id: null, name: null}) THEN classes END as classes " +
                " RETURN DISTINCT s.id as id, s.name as name, parents, classes, length(coalesce(parents,[])) > 0 as notroot " +
                " UNION " +
                "MATCH (:User {id: {userId}})-[rf:HAS_FUNCTION]->(f:Function)" +
                " WHERE (f.externalId = \"ADMIN_LOCAL\" OR f.externalId = \"ADMIN_COLLECTIVITE\") AND rf.scope IS NOT NULL " +
                " UNWIND rf.scope as scopeId " +
                " MATCH (s:Structure {id: scopeId}) " +
                " OPTIONAL MATCH (:User {id: {userId}})-[:IN]->(:ProfileGroup)-[:DEPENDS]->(c:Class)-[:BELONGS]->(s)" +
                " WITH DISTINCT s, COLLECT(distinct {id: c.id, name: c.name}) as classes" +
                " RETURN DISTINCT s.id as id, s.name as name, null as parents," +
                " CASE WHEN any(c in classes where c <> {id: null, name: null}) THEN classes END as classes, false as notroot " +
                " ORDER BY notroot, name ";

        final JsonObject params = new JsonObject().put("userId", userId);
        neo4j.execute(query, params, Neo4jResult.validResultHandler(handler));
    }

    public void getSubStructures(String structureId, Handler<Either<String, JsonObject>> handler) {
        final String query =
                "MATCH (:Structure {id: {structureId}})<-[:HAS_ATTACHMENT*0..]-(s:Structure) " +
                "RETURN COLLECT(DISTINCT s.id) as ids ";
        final JsonObject params = new JsonObject().put("structureId", structureId);
        neo4j.execute(query, params, Neo4jResult.validUniqueResultHandler(handler));
    }

    public void getClassesForStructure(String structureId, Handler<Either<String, JsonObject>> handler) {
        final String query =
                "MATCH (c:Class)-[:BELONGS]->(s:Structure {id: {structureId}}) " +
                        "RETURN COLLECT(DISTINCT c.id) as ids ";
        final JsonObject params = new JsonObject().put("structureId", structureId);
        neo4j.execute(query, params, Neo4jResult.validUniqueResultHandler(handler));
    }

    public void getUserClassesForStructure(String structureId, String userId, Handler<Either<String, JsonObject>> handler) {
        final String query =
                "MATCH (u:User {id: {userId}})-[:IN]->(:ProfileGroup)-[:DEPENDS]->(c:Class)-[:BELONGS]->(s:Structure {id: {structureId}}) " +
                        "RETURN COLLECT(DISTINCT c.id) as ids ";
        final JsonObject params = new JsonObject().put("structureId", structureId).put("userId", userId);
        neo4j.execute(query, params, Neo4jResult.validUniqueResultHandler(handler));
    }
    
    public void getStructureMetrics(String structureId, Handler<Either<String, JsonObject>> results){
        
        String query = "MATCH (s:Structure) " +
                        "WHERE s.id = {structureId} " +
			"MATCH (u:User)-[:IN]->(pg:ProfileGroup)-[:DEPENDS]->(s)," +
			"(pg)-[:HAS_PROFILE]->(p:Profile) " +
			"WITH p, collect(distinct u) as allUsers " +
			"WITH p, FILTER(u IN allUsers WHERE u.activationCode IS NULL) as active, " +
			"FILTER(u IN allUsers WHERE NOT(u.activationCode IS NULL)) as inactive " +
			"WITH p, length (active) as active, length(inactive) as inactive " +
			"RETURN collect({profile: p.name, active: active, inactive: inactive}) as metrics";

	    JsonObject params = new JsonObject().put("structureId", structureId);

	    neo4j.execute(query.toString(), params,  Neo4jResult.validUniqueResultHandler(results));

    }
}