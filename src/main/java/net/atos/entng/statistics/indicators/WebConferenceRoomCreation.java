package net.atos.entng.statistics.indicators;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import net.atos.entng.statistics.aggregation.indicators.CustomIndicator;

import org.entcore.common.aggregation.groups.IndicatorGroup;
import org.entcore.common.aggregation.indicators.Indicator;
import org.entcore.common.aggregation.indicators.mongo.IndicatorMongoImpl;

import org.entcore.common.aggregation.filters.IndicatorFilter;

import java.util.*;

import static net.atos.entng.statistics.aggregation.indicators.IndicatorConstants.STATS_FIELD_MOBILE;
import static net.atos.entng.statistics.aggregation.indicators.IndicatorConstants.STATS_FIELD_WEB;
import static net.atos.entng.statistics.controllers.StatisticsController.PARAM_DEVICE;
import static org.entcore.common.aggregation.MongoConstants.*;

public class WebConferenceRoomCreation implements CustomIndicator {
    private static final String MODULE_NAME = "WebConference";
    private static final String TRACE_TYPE_SVC_ROOM_CREATION = "ROOM_CREATION";

    @Override
    public JsonObject aggregation() {
        return new JsonObject().put("group_by", new JsonObject()
                .put("terms", new JsonObject().put("field", TRACE_FIELD_PROFILE)));
    }

    @Override
    public JsonArray filter(List<String> schoolIds, JsonObject params, JsonArray mobileClientIds, boolean export) {
        JsonArray filter = new JsonArray();
        final String device = params.getString(PARAM_DEVICE);

        if (STATS_FIELD_MOBILE.equals(device)) {
            filter.add(new JsonObject().put("terms", new JsonObject().put("module", mobileClientIds)));
        }
        if (STATS_FIELD_WEB.equals(device)) {
            filter.add(new JsonObject().put("term", new JsonObject().put("module", MODULE_NAME)));
        }
        filter.add(new JsonObject().put("term", new JsonObject().put("event-type", TRACE_TYPE_SVC_ROOM_CREATION)));

        return filter;
    }

    @Override
    public Indicator indicator(Collection<IndicatorFilter> filters, Date pWriteDate) {
        Collection<IndicatorGroup> indicatorGroups = new ArrayList<>();
        indicatorGroups.add(new IndicatorGroup(TRACE_FIELD_MODULE)
                .addAndReturnChild(new IndicatorGroup(TRACE_FIELD_STRUCTURES).setArray(true))
                .addAndReturnChild(TRACE_FIELD_PROFILE));

        Indicator indicator = new IndicatorMongoImpl(TRACE_TYPE_SVC_ROOM_CREATION, filters, indicatorGroups);
        indicator.setWriteDate(pWriteDate);
        return indicator;
    }
}
