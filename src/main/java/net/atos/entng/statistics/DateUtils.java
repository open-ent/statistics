package net.atos.entng.statistics;

import java.util.Calendar;
import java.util.Date;

import fr.wseduc.mongodb.MongoDb;

public class DateUtils {

	public static Date getFirstDayOfMonth(Date date) {
		Calendar cal = Calendar.getInstance();
		cal.setTime(date);
		return getFirstDay(cal);
	}

	public static Date getFirstDayOfLastMonth(Date date) {
		Calendar cal = Calendar.getInstance();
		cal.setTime(date);
		cal.add(Calendar.MONTH, -1);
		return getFirstDay(cal);
	}

	private static Date getFirstDay(Calendar cal) {
		cal.set(Calendar.DAY_OF_MONTH, 1);
		return cal.getTime();
	}

	public static String formatTimestamp(long unixTimestamp) {
		Date date = new Date();
		date.setTime(unixTimestamp);
		return MongoDb.formatDate(date);
	}
}
