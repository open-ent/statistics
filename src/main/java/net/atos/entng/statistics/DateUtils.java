package net.atos.entng.statistics;

import java.util.Calendar;
import java.util.Date;

public class DateUtils {

	public static Date getFirstDayOfCurrentMonth() {
		Calendar cal = Calendar.getInstance();
		return getFirstDay(cal);
	}

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
		cal.set(Calendar.HOUR_OF_DAY, 0);
		cal.set(Calendar.MINUTE, 0);
		cal.set(Calendar.SECOND, 0);
		cal.set(Calendar.MILLISECOND, 0);

		return cal.getTime();
	}

	public static Date getTheDayBefore(Date date) {
		Calendar cal = Calendar.getInstance();
		cal.setTime(date);
		cal.add(Calendar.DAY_OF_YEAR, -1);

		return cal.getTime();
	};
}
