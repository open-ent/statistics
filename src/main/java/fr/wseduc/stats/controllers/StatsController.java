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

package fr.wseduc.stats.controllers;

import static fr.wseduc.webutils.Utils.getOrElse;
import static org.entcore.common.http.response.DefaultResponseHandler.*;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;

import fr.wseduc.stats.filters.StatsResourceProvider;
import fr.wseduc.stats.services.StatsService;
import fr.wseduc.stats.services.StatsServiceMongoImpl;
import fr.wseduc.stats.services.StructureService;
import fr.wseduc.rs.*;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.I18n;
import io.vertx.core.MultiMap;
import org.entcore.common.aggregation.processing.AggregationProcessing;
import org.entcore.common.events.EventHelper;
import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.common.http.filter.SuperAdminFilter;
import org.entcore.common.mongodb.MongoDbControllerHelper;
import org.entcore.common.user.UserUtils;
import org.joda.time.DateTime;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Vert.x backend controller for the application using Mongodb.
 */
public class StatsController extends MongoDbControllerHelper {

	public static final String UTF8_BOM = "\uFEFF";

	//Computation service
	private StatsService statsService;
	private final StructureService structureService = new StructureService();
	private EventHelper eventHelper;

	//Permissions
	private static final String
		view = "stats.view",
		list = "",
		export = "";

	/**
	 * Creates a new controller.
	 * @param collection Name of the collection stored in the mongoDB database.
	 */
	public StatsController(String collection) {
		super(collection);
	}

	/**
	 * Displays the home view.
	 * @param request Client request
	 */
	@Get("")
	@SecuredAction(value = view)
	public void view(HttpServerRequest request) {
		// CCTP 51C — React PAR DÉFAUT pour ce module : l'IHM AngularJS n'a jamais été
		// gulp-buildée dans ce déploiement (view/stats.html absent → /stats en 500). Le
		// fallback Java est donc "react" (la conf `frontend-ui` étant strippée par le
		// springboard) ; repli AngularJS possible via `?ui=angular`.
		final String uiParam = request.params().get("ui");
		final String frontendUi = "angular".equals(config.getString("frontend-ui", "react")) ? "angular" : "react";
		final String ui = ("react".equals(uiParam) || "angular".equals(uiParam)) ? uiParam : frontendUi;
		if ("react".equals(ui)) {
			renderView(request, new JsonObject(), "stats-react.html", null);
		} else {
			renderView(request);
		}
		eventHelper.onAccess(request);
	}

	/**
	 * Returns the user functions allowed to see the statistics at project scope and export them, if put in the configuration Json file.
	 * @param request
	 */
	@Get("/allowed")
	@SecuredAction(value="", type = ActionType.AUTHENTICATED)
	public void listAllowedFunctions(HttpServerRequest request) {
		renderJson(request, config.getJsonArray("overviewAllowedFunctions", new JsonArray()));
	}

	/**
	 * Returns the list of statistics.<br>
	 * Request may contain filters as query parameters.
	 * @param request Client request
	 */
	@Get("/list")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(StatsResourceProvider.class)
	public void listStats(final HttpServerRequest request) {
		statsService.listStats(request.params(), arrayResponseHandler(request));
	}

	/**
	 * Exports global aggregations.
	 * @param request Client request
	 */
	@Get("/export")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(StatsResourceProvider.class)
	public void export(final HttpServerRequest request) {
		final Handler<Either<String, JsonArray>> handler = new Handler<Either<String, JsonArray>>() {
			@Override
			public void handle(Either<String, JsonArray> r) {
				if (r.isRight()) {
					MultiMap params = request.params();
					String csvTemplateName = "text/export-" + params.get("indicator");
					csvTemplateName += "CONNECTOR".equals(params.get("type")) ? "-connector": "";
					csvTemplateName += "true".equals(params.get("device")) ? "-device": "";
					if ("structure".equals(params.get("entityLevel"))) {
						csvTemplateName += ("true".equals(params.get("accumulate")) ? "" : "-wta");
					}
					csvTemplateName += ".template.csv";

					processTemplate(request, csvTemplateName,
							new JsonObject().put("list", r.right().getValue()), new Handler<String>() {
						@Override
						public void handle(final String export) {
							if (export != null) {
								request.response().putHeader("Content-Type", "application/csv");
								request.response().putHeader("Content-Disposition",
										"attachment; filename=export-stats-" + params.get("indicator") + ".csv");
								request.response().end(UTF8_BOM + export);
							} else {
								renderError(request);
							}
						}
					});
				} else {
					renderJson(request, new JsonObject().put("error", r.left().getValue()), 400);
				}
			}
		};

		if (statsService instanceof StatsServiceMongoImpl) {
			statsService.listStats(null, handler);
		} else {
			if ("true".equals(request.params().get("substructures")) && "structure".equals(request.params().get("entitylevel"))) {
				structureService.getSubStructures(request.params().get("entity"), either -> {
					if (either.isRight()) {
						request.params().set("entity", (List<String>) either.right().getValue().getJsonArray("ids").getList());
						statsService.listStatsExport(request.params(), I18n.acceptLanguage(request), handler);
					} else {
						renderJson(request, new JsonObject().put("error", either.left().getValue()), 400);
					}
				});
			} else if ("true".equals(request.params().get("structureClasses")) && "structure".equals(request.params().get("entityLevel"))) {
				structureService.getClassesForStructure(request.params().get("entity"), either -> {
					if (either.isRight()) {
						List<String> classIds = (List<String>) either.right().getValue().getJsonArray("ids").getList();
						if (classIds != null && !classIds.isEmpty()) {
							request.params().set("entityLevel", "class");
							request.params().set("entity", classIds);
						}
						statsService.listStatsExport(request.params(), I18n.acceptLanguage(request), handler);
					} else {
						renderJson(request, new JsonObject().put("error", either.left().getValue()), 400);
					}
				});
			} else if ("true".equals(request.params().get("userClasses")) && "structure".equals(request.params().get("entityLevel"))) {
				UserUtils.getUserInfos(eb, request, user -> {
					if (user != null) {
						structureService.getUserClassesForStructure(request.params().get("entity"), user.getUserId(), either -> {
							List<String> classIds = (List<String>) either.right().getValue().getJsonArray("ids").getList();
							if (either.isRight()) {
								if (classIds != null && !classIds.isEmpty()) {
									request.params().set("entityLevel", "class");
									request.params().set("entity", classIds);
								}
								statsService.listStatsExport(request.params(), I18n.acceptLanguage(request), handler);
							} else {
								renderJson(request, new JsonObject().put("error", either.left().getValue()), 400);
							}
						});
					} else {
						unauthorized(request);
					}
				});
			} else {
				statsService.listStatsExport(request.params(), I18n.acceptLanguage(request), handler);
			}
		}
	}

	@Post("/recalculate/:from/:to")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(SuperAdminFilter.class)
	public void recalculate(final HttpServerRequest request) {
		final String fromDateStr 	= request.params().get("from");
		final String toDateStr	 	= request.params().get("to");
		long fromDate;
		long toDate;

		try {
			fromDate = Long.parseLong(fromDateStr);
		} catch (NumberFormatException e) {
			try {
				fromDate = DateTime.parse(fromDateStr).getMillis();
			} catch (RuntimeException e2) {
				badRequest(request, "invalid.from.date");
				return;
			}
		}

		try {
			toDate = Long.parseLong(toDateStr);
		} catch (NumberFormatException e) {
			try {
				toDate = DateTime.parse(toDateStr).getMillis();
			} catch (RuntimeException e2) {
				badRequest(request, "invalid.to.date");
				return;
			}
		}

		if(fromDate >= toDate) {
			badRequest(request, "from.date.must.be.lesser.than.to.date");
			return;
		}

		ok(request);

		log.info("[Aggregation][Recalculate] Starting ... (from " + new Date(fromDate) + " / to " + new Date(toDate) + ")");

		final Calendar cal = Calendar.getInstance();
		final Calendar endCal = Calendar.getInstance();
		cal.setTimeInMillis(fromDate);
		endCal.setTimeInMillis(toDate);
		final ServiceLoader<AggregationProcessing> implementations = ServiceLoader.load(AggregationProcessing.class);

		final AtomicInteger countdown = new AtomicInteger(0);
		final AtomicInteger implNb = new AtomicInteger(0);
		for(@SuppressWarnings("unused") AggregationProcessing _ : implementations){
			implNb.incrementAndGet();
		}
		countdown.set(implNb.get());

		final Handler<Void> processing = new Handler<Void>() {

			final Handler<Void> that = this;
			final Handler<Void> continuation = new Handler<Void>() {
				public void handle(Void v) {
					if(countdown.decrementAndGet() <= 0) {
						cal.add(Calendar.DAY_OF_MONTH, 1);
						if(cal.getTimeInMillis() >= endCal.getTimeInMillis()) {
							log.info("[Aggregation][Recalculate] Over");
							return;
						}
						countdown.set(implNb.get());
						that.handle(null);
					}
				}
			};

			public void handle(Void v) {
				log.info("[Aggregation][Processing] Date marker set at : {"+cal.getTime()+"}");
				for(AggregationProcessing processor : implementations) {
					final Date start = new Date();
					log.info("[Aggregation][Processing] Launching implementation : "+processor.getClass().getName());
					processor.getIndicators().clear();
					processor.process(cal.getTime(), new Handler<JsonObject>() {
						public void handle(JsonObject event) {
							final Date end = new Date();
							log.info("[Aggregation][Processing] Over, took ["+(end.getTime() - start.getTime())+"] ms");
							continuation.handle(null);
						}
					});
				}
			}
		};

		processing.handle(null);

	}

	public void setStatsService(StatsService statsService) {
		this.statsService = statsService;
	}


	@Get("/substructures")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void getSubStructures(final HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> {
			if (user != null) {
				final boolean hierarchical = "true".equals(request.getParam("hierarchical"));
				structureService.getStructuresForUser(user.getUserId(), hierarchical, either -> {
					if (either.isLeft()) {
						log.error(either.left().getValue());
						renderError(request);
					} else {
						renderJson(request, either.right().getValue());
					}
				});
			} else{
				unauthorized(request);
			}
        });
    }

	@Get("/structures")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
	public void getStructuresHierarchyAndClasses(final HttpServerRequest request) {
		UserUtils.getUserInfos(eb, request, user -> {
			if (user != null) {
				structureService.getStructuresHierarchyAndClasses(user.getUserId(), either -> {
					if (either.isLeft()) {
						log.error(either.left().getValue());
						renderError(request);
					} else {
						renderJson(request, either.right().getValue());
					}
				});
			} else{
				unauthorized(request);
			}
		});
	}

	@Get("/classes")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
	public void getClasses(final HttpServerRequest request) {
		UserUtils.getUserInfos(eb, request, user -> {
			if (user != null) {
				structureService.getClassesForUser(user.getUserId(), either -> {
					if (either.isLeft()) {
						log.error(either.left().getValue());
						renderError(request);
					} else {
						renderJson(request, either.right().getValue());
					}
				});
			} else{
				unauthorized(request);
			}
		});
	}

	public void setEventHelper(EventHelper eventHelper) {
		this.eventHelper = eventHelper;
	}

	@Get("/structure/metrics")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(StatsResourceProvider.class)
	public void getStructureMetrics(final HttpServerRequest request) {

		final String structureId = request.params().get("structureId");
		if(structureId == null || structureId.trim().isEmpty()) {
			badRequest(request, "structure.id.missing");
			return;
		}

		structureService.getStructureMetrics(structureId, either -> {
			if (either.isLeft()) {
				log.error(either.left().getValue());
				renderError(request);
			} else {
				renderJson(request, either.right().getValue());
			}
		});
	}

}
