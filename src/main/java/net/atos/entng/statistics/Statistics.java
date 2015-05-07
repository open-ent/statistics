package net.atos.entng.statistics;

import java.text.ParseException;
import java.util.Calendar;
import java.util.Date;

import net.atos.entng.statistics.aggregation.AggregateTask;

import org.entcore.common.http.BaseServer;
import org.vertx.java.core.Handler;

import fr.wseduc.cron.CronTrigger;

public class Statistics extends BaseServer {

	@Override
	public void start() {
		super.start();

		/* By default, fire aggregate-cron at 1:15am every day.
		 * Be careful when setting fire times between midnight and 1:00 AM
		 * - "daylight savings" can cause a skip or a repeat depending on whether the time moves back or jumps forward.
		 */
		final String aggregateEventsCron = container.config().getString("aggregate-cron", "0 15 1 ? * * *");

		AggregateTask aggTask = new AggregateTask();
		try {
			new CronTrigger(vertx, aggregateEventsCron).schedule(aggTask);
		} catch (ParseException e) {
			log.fatal(e.getMessage(), e);
			return;
		}


		// Used for development
		if("dev".equals(container.config().getString("mode", null))
				&& container.config().getBoolean("aggregateOnStart", false)) {

            Date startDate = new Date();
            startDate.setTime(1422748800000L); // Sun, 01 Feb 2015 00:00:00 GMT

            Date endDate = new Date();
            endDate.setTime(1430438400000L); // Fri, 01 May 2015 00:00:00 GMT

			this.aggregateEvents(startDate, endDate);
		}
	}


	// Aggregate documents of collection "events" for each day, from startDate to endDate
	private void aggregateEvents(Date startDate, Date endDate) {
		Date from, to;

		Calendar fromCal = Calendar.getInstance();
		fromCal.setTime(startDate);

		fromCal.set(Calendar.HOUR_OF_DAY, 0);
		fromCal.set(Calendar.MINUTE, 0);
		fromCal.set(Calendar.SECOND, 0);
		fromCal.set(Calendar.MILLISECOND, 0);
		from = fromCal.getTime();

		Calendar toCal = (Calendar) fromCal.clone();
		toCal.add(Calendar.DAY_OF_MONTH, 1);
		to = toCal.getTime();

		do {
			// Launch aggregation
			final AggregateTask aggTask = new AggregateTask(from, to, DateUtils.getFirstDayOfMonth(from));

			vertx.setTimer(100L, new Handler<Long>() {
				@Override
				public void handle(Long event) {
					aggTask.handle(event);
				}
			});

			// Increment dates
			fromCal.add(Calendar.DAY_OF_MONTH, 1);
			from = fromCal.getTime();

			toCal.add(Calendar.DAY_OF_MONTH, 1);
			to = toCal.getTime();
		} while (to.before(endDate));
	}

}
