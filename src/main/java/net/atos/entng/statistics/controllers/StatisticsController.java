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

package net.atos.entng.statistics.controllers;

import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.rs.ApiDoc;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.I18n;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.request.RequestUtils;
import net.atos.entng.statistics.DateUtils;
import net.atos.entng.statistics.Statistics;
import net.atos.entng.statistics.services.*;
import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.common.http.filter.SuperAdminFilter;
import org.entcore.common.mongodb.MongoDbResult;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.vertx.java.core.http.RouteMatcher;

import java.text.SimpleDateFormat;
import java.util.*;

import static fr.wseduc.webutils.I18n.acceptLanguage;
import static fr.wseduc.webutils.Utils.isNotEmpty;
import static net.atos.entng.statistics.aggregation.indicators.IndicatorConstants.STATS_FIELD_ACTIVATED_ACCOUNTS;
import static net.atos.entng.statistics.aggregation.indicators.IndicatorConstants.STATS_FIELD_UNIQUE_VISITORS;
import static org.entcore.common.aggregation.MongoConstants.*;

public class StatisticsController extends BaseController {

    private StatisticsService statsService;
    private final StructureService structureService;
    private final Vertx vertx;
    private I18n i18n;

    public static final String PARAM_SCHOOL_ID = "schoolId";
    public static final String PARAM_INDICATOR = "indicator";
    public static final String PARAM_START_DATE = "startDate";
    public static final String PARAM_END_DATE = "endDate";
    public static final String PARAM_MODULE = "module";
    public static final String PARAM_FORMAT = "format";
    public static final String PARAM_DEVICE = "device";

    private final Set<String> indicators;
    private final JsonObject metadata;
    private final JsonArray accessModules;
    private final JsonArray mobileClientIds;

    @Override
    public void init(Vertx vertx, JsonObject config, RouteMatcher rm,
            Map<String, fr.wseduc.webutils.security.SecuredAction> securedActions) {
        super.init(vertx, config, rm, securedActions);
        metadata.put("connectors", config.getJsonArray("connectors", new JsonArray()));
    }

    public StatisticsController(Vertx vertx, JsonArray pAccessModules, List<String> customIndicators, JsonArray mobileClientIds) {
        this.vertx = vertx;
        structureService = new StructureServiceNeo4jImpl();
        i18n = I18n.getInstance("statistics");

        indicators = new HashSet<>();
        indicators.add(STATS_FIELD_UNIQUE_VISITORS);
        indicators.add(TRACE_TYPE_ACTIVATION);
        indicators.add(TRACE_TYPE_CONNEXION);
        indicators.add(TRACE_TYPE_SVC_ACCESS);
        indicators.add(STATS_FIELD_ACTIVATED_ACCOUNTS);
        indicators.add(TRACE_TYPE_CONNECTOR);
        indicators.addAll(customIndicators);

        metadata = new JsonObject();
        metadata.put("indicators", new JsonArray(new ArrayList<>(indicators)));
        metadata.put("modules", pAccessModules);
        accessModules = pAccessModules;
        this.mobileClientIds = mobileClientIds;
    }

    @Get("")
    @ApiDoc("Get HTML view")
    @SecuredAction("statistics.view")
    public void view(HttpServerRequest request) {
        JsonObject params = new JsonObject()
                .put("deviceFilter", Statistics.deviceFilter);
        renderView(request, params);
    }

    @Get("/indicators")
    @ApiDoc("Get existing indicators and modules")
    @SecuredAction("statistics.get.indicators")
    public void getIndicators(final HttpServerRequest request) {
        renderJson(request, metadata);
    }

    @Post("/data")
    @SecuredAction("statistics.get.data")
    public void getData(final HttpServerRequest request) {
        RequestUtils.bodyToJson(request, pathPrefix + "schoolquery", new Handler<JsonObject>() {
            @Override
            public void handle(JsonObject data) {
                final JsonObject params = data; // parameters from the model (schoolIdArray / indicator / startDate / endDate / module)

                UserUtils.getUserInfos(eb, request, new Handler<UserInfos>() {
                    @Override
                    public void handle(final UserInfos user) {
                        if (user == null) {
                            log.debug("User not found in session.");
                            unauthorized(request);
                            return;
                        }
                        JsonArray arr = params.getJsonArray("schoolIdArray");
                        List<String> schoolIds = new ArrayList<String>();
                        for (int i = 0; i < arr.size(); i++) {
                            schoolIds.add(arr.getString(i));
                        }

                        //final List<String> schoolIds = Arrays.asList(params.getJsonArray("schoolIdArray"));
                        if (schoolIds == null || schoolIds.size() == 0) {
                            String errorMsg = i18n.translate("statistics.bad.request.invalid.schools", getHost(request), acceptLanguage(request));
                            badRequest(request, errorMsg);
                            return;
                        }

                        //final String indicator = request.params().get(PARAM_INDICATOR);
                        final String indicator = params.getString("indicator");
                        if (indicator == null || indicator.trim().isEmpty() || !indicators.contains(indicator)) {
                            String errorMsg = i18n.translate("statistics.bad.request.invalid.indicator", getHost(request), acceptLanguage(request));
                            badRequest(request, errorMsg);
                            return;
                        }

                        String module = "";
                        if (TRACE_TYPE_SVC_ACCESS.equals(indicator)) {
                            //module = request.params().get(PARAM_MODULE);
                            module = params.getString("module");
                            if (module != null && !module.trim().isEmpty() && !accessModules.contains(module)) {
                                String errorMsg = i18n.translate("statistics.bad.request.invalid.module", getHost(request), acceptLanguage(request));
                                badRequest(request, errorMsg);
                                return;
                            }
                            // Else (when module is not specified) return data for all modules
                        } else if (TRACE_TYPE_CONNECTOR.equals(indicator)) {
                            module = params.getString("module");
                            if (isNotEmpty(module) && !metadata.getJsonArray("connectors").contains(module)) {
                                badRequest(request, i18n.translate(
                                        "statistics.bad.request.invalid.connector",
                                        getHost(request), acceptLanguage(request)));
                                return;
                            }
                        }

                        //String startDate = request.params().get(PARAM_START_DATE);
                        //String endDate = request.params().get(PARAM_END_DATE);
                        String startDate = String.valueOf(params.getInteger("startDate"));
                        String endDate = String.valueOf(params.getInteger("endDate"));
                        long start, end;
                        try {
                            start = DateUtils.parseStringDate(startDate);
                            end = DateUtils.parseStringDate(endDate);
                            if (end < start || end < 0L || start < 0L) {
                                String errorMsg = i18n.translate("statistics.bad.request.invalid.dates", getHost(request), acceptLanguage(request));
                                badRequest(request, errorMsg);
                                return;
                            }
                        } catch (Exception e) {
                            log.error("Error when casting startDate or endDate to long", e);
                            String errorMsg = i18n.translate("statistics.bad.request.invalid.date.format", getHost(request), acceptLanguage(request));
                            badRequest(request, errorMsg);
                            return;
                        }

                        String device = params.getString("device");

                        final JsonObject params = new JsonObject();
                        params.put(PARAM_INDICATOR, indicator)
                                .put(PARAM_START_DATE, start)
                                .put(PARAM_END_DATE, end)
                                .put(PARAM_MODULE, module)
                                .put(PARAM_DEVICE, device);

                        if (schoolIds.size() == 1) {
                            // if the structure choosed is not a school, we need to explore all the attached schools from the graph base
                            structureService.getAttachedStructureslist(schoolIds.get(0), new Handler<Either<String, JsonArray>>() {
                                @Override
                                public void handle(Either<String, JsonArray> either) {
                                    if (either.isLeft()) {
                                        log.error(either.left().getValue());
                                        renderError(request);
                                    } else {
                                        final List<String> attachedSchoolsList;
                                        final JsonArray result = either.right().getValue();

                                        if (result != null) {
                                            attachedSchoolsList = new ArrayList<String>(result.size());
                                            for (int i = 0; i < result.size(); i++) {
                                                Object obj = result.getValue(i);
                                                if (obj instanceof JsonObject) {
                                                    final JsonObject jo = (JsonObject) obj;
                                                    attachedSchoolsList.add(jo.getString("s2.id", ""));
                                                }
                                            }
                                        } else {
                                            attachedSchoolsList = new ArrayList<String>(0);
                                        }
                                        formatting(attachedSchoolsList, params, indicator, request);
                                    }
                                }
                            });
                        } else {
                            formatting(schoolIds, params, indicator, request);
                        }
                    }
                });
            }
        });
    }

    @Get("/substructures")
    public void getSubStructures(final HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, new Handler<UserInfos>() {
            @Override
            public void handle(final UserInfos user) {
                if (user != null) {
                    structureService.getListStructuresForUser(user.getUserId(), new Handler<Either<String, JsonArray>>() {
                        @Override
                        public void handle(Either<String, JsonArray> either) {
                            if (either.isLeft()) {
                                log.error(either.left().getValue());
                                renderError(request);
                            } else {
                                renderJson(request, either.right().getValue());
                            }
                        }

                        ;
                    });
                }
            }
        });
    }


    /**
     * @param schoolIds : list of schools
     * @param params    JsonObject
     * @param indicator
     * @param request
     */
    private void formatting(List<String> schoolIds, JsonObject params, final String indicator, final HttpServerRequest request) {
        String format = request.params().get(PARAM_FORMAT);
        if (format == null || format.isEmpty()) {
            // Default case : return JSON data
            StatisticsController.this.getJsonData(schoolIds, params, request);
        } else {
            switch (format) {
                case "csv": // CSV export
                    statsService.getStatsForExport(schoolIds, params, mobileClientIds, new Handler<Either<String, JsonArray>>() {
                        @Override
                        public void handle(Either<String, JsonArray> event) {
                            if (event.isLeft()) {
                                log.error(event.left().getValue());
                                renderError(request);
                            } else {
                                JsonObject params = new JsonObject()
                                        .put("is" + indicator, true)
                                        .put("list", event.right().getValue())
                                        .put("indicator", indicator);

                                processTemplate(request, "text/export.txt", params, new Handler<String>() {
                                    @Override
                                    public void handle(final String export) {
                                        if (export != null) {
                                            request.response().putHeader("Content-Type", "application/csv");
                                            request.response().putHeader("Content-Disposition",
                                                    "attachment; filename=export.csv");
                                            request.response().end(export);
                                        } else {
                                            renderError(request);
                                        }
                                    }
                                });

                            }
                        }
                    });
                    break;

                case "json":
                    StatisticsController.this.getJsonData(schoolIds, params, request);
                    break;

                default:
                    String errorMsg = i18n.translate("statistics.bad.request.invalid.export.format", getHost(request), acceptLanguage(request));
                    badRequest(request, errorMsg);
                    break;
            }

        }
    }

    private boolean isValidSchools(Collection<String> schoolIds, Collection<String> structuresIds) {
        return structuresIds.containsAll(schoolIds);
    }

    private void getJsonData(final List<String> schoolIds, final JsonObject params, final HttpServerRequest request) {
        statsService.getStats(schoolIds, params, mobileClientIds, new Handler<Either<String, JsonArray>>() {
            @Override
            public void handle(Either<String, JsonArray> event) {
                if (event.isLeft()) {
                    log.error(event.left().getValue());
                    renderError(request);
                } else {
                    renderJson(request, event.right().getValue());
                }
            }
        });
    }

    @Post("/structures")
    @ApiDoc("Get structures' names, UAIs and cities")
    @SecuredAction("statistics.get.structures")
    public void getStructures(final HttpServerRequest request) {
        RequestUtils.bodyToJson(request, pathPrefix + "schoolquery", new Handler<JsonObject>() {
            @Override
            public void handle(JsonObject data) {
                final JsonObject params = data; // parameters from the model (schoolIdArray / indicator / startDate / endDate / module)
                UserUtils.getUserInfos(eb, request, new Handler<UserInfos>() {
                    @Override
                    public void handle(final UserInfos user) {
                        if (user != null) {

                            JsonArray arr = params.getJsonArray("schoolIdArray");
                            List<String> schoolIds = new ArrayList<String>();
                            for(int i = 0; i < arr.size(); i++){
                                JsonObject obj = arr.getJsonObject(i);
                                schoolIds.add(obj.getString("id"));
                            }

                            if (schoolIds == null || schoolIds.size() == 0) {
                                String errorMsg = i18n.translate("statistics.bad.request.invalid.schools", getHost(request), acceptLanguage(request));
                                badRequest(request, errorMsg);
                                return;
                            }

                            JsonArray structureIds = new JsonArray();
                            for (String school : schoolIds) {
                                structureIds.add(school);
                            }

                            structureService.list(structureIds, new Handler<Either<String, JsonArray>>() {
                                @Override
                                public void handle(Either<String, JsonArray> event) {
                                    if (event.isLeft()) {
                                        log.error(event.left().getValue());
                                        renderError(request);
                                    } else {
                                        renderJson(request, event.right().getValue());
                                    }
                                }
                            });
                        } // end if
                    }
                });
            }
        });
    }

    @Get("/generation")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(SuperAdminFilter.class)
    /**
     * Generation of statistics to mongoDB database. Calls the same treatment as the one called by cron
     * Before the generation, deletes the records who will be regenerated.
     */
    public void generation(final HttpServerRequest request) {
        RequestUtils.bodyToJson(request, pathPrefix + "schoolquery", new Handler<JsonObject>() {
            @Override
            public void handle(JsonObject data) {
                final JsonObject params = data; // parameters from the model (schoolIdArray / indicator / startDate / endDate / module)

                UserUtils.getUserInfos(eb, request, new Handler<UserInfos>() {
                    @Override
                    public void handle(final UserInfos user) {
                        final Date startDate = new Date(params.getLong("startDate") * 1000L);
                        final Date endDate = new Date(params.getLong("endDate") * 1000L);
                        //final Date startDate = new Date(Long.parseLong(request.params().get(PARAM_START_DATE)) * 1000L);
                        //final Date endDate = new Date(Long.parseLong(request.params().get(PARAM_END_DATE)) * 1000L);
                        deleteRegeneratedStatistics(startDate, endDate, new Handler<Either<String, JsonArray>>() {
                            @Override
                            public void handle(Either<String, JsonArray> event) {
                                if (event.isLeft()) {
                                    log.error(event.left().getValue());
                                    renderError(request);
                                } else {
                                    if (user != null) {
                                        Statistics st = new Statistics();
                                        st.aggregateEvents(StatisticsController.this.vertx, startDate, endDate);
                                        JsonArray jarray = new JsonArray();
                                        jarray.add(String.valueOf(params.getInteger("startDate")));
                                        renderJson(request, jarray);
                                    }
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    // delete all the statisticcs from mongoDB who will be generated again.
    private void deleteRegeneratedStatistics(Date startDate, Date endDate, Handler<Either<String, JsonArray>> handler){
        MongoDb mongo = MongoDb.getInstance();
        JsonObject criteria = new JsonObject();
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd 00:00.00.000");
        String strStartDate = df.format(startDate);
        String strEndDate = df.format(endDate);

        // first condition
        final JsonArray cond = new JsonArray().add(new JsonObject().put("date", new JsonObject()
                .put("$gte", strStartDate)));

        // second condition
        cond.add(new JsonObject().put("date", new JsonObject()
                .put("$lt", strEndDate)));

        // query = condition1 AND condition2
        final JsonObject query = new JsonObject().put("$and", cond);

        // launch deletion
        mongo.delete("stats", query, MongoDbResult.validResultsHandler(handler));


    }

    public void setStatsService(StatisticsService statsService) {
        this.statsService = statsService;
    }
}