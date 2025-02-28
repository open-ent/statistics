package net.atos.entng.statistics.aggregation.indicators;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.aggregation.filters.IndicatorFilter;

import org.entcore.common.aggregation.indicators.Indicator;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

public interface CustomIndicator {
    Logger log = LoggerFactory.getLogger(CustomIndicator.class);

    static CustomIndicator create(String name) {
        try {
            return (CustomIndicator) Class.forName(String.format("net.atos.entng.statistics.indicators.%s", name)).newInstance();
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            log.error("Unable to create custom indicator " + name, e);
            return null;
        }
    }

    JsonObject aggregation();

    JsonArray filter(List<String> schoolIds, JsonObject params, JsonArray mobileClientIds, boolean export);

    Indicator indicator(Collection<IndicatorFilter> filters, Date pWriteDate);
}
