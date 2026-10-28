package com.gielinorwrapped;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Day records on disk: one JSON file per account per day under {@code accounts/<hash>/days}. All methods
 * do file I/O and must not be called on the client thread.
 */
@Slf4j
class StatsStore
{
	private final Gson gson;
	private final Filepath root;

	StatsStore(Gson gson, Filepath root)
	{
		this.gson = gson;
		this.root = root;
	}

	Filepath root()
	{
		return root;
	}

	Filepath accountDir(long accountHash)
	{
		return root.join("accounts", Long.toUnsignedString(accountHash));
	}

	private Filepath daysDir(long accountHash)
	{
		return accountDir(accountHash).join("days");
	}

	DayRecord readDay(long accountHash, LocalDate date)
	{
		Filepath file = daysDir(accountHash).join(date + ".json");
		if (!file.exists())
		{
			return null;
		}
		try (Reader reader = file.openReader())
		{
			DayRecord day = gson.fromJson(reader, DayRecord.class);
			if (day == null)
			{
				return null;
			}
			day.date = date.toString();
			return day.normalize();
		}
		catch (IOException | JsonParseException e)
		{
			log.warn("Unable to read {}", file, e);
			return null;
		}
	}

	/** Writes the day through a temporary file so a crash mid-write never leaves a broken record. */
	void writeDay(long accountHash, String date, String json) throws IOException
	{
		Filepath dir = daysDir(accountHash);
		dir.createDirectories();
		Filepath tmp = dir.join(date + ".json.tmp");
		tmp.write(json);
		tmp.moveTo(dir.join(date + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Dates with a stored record, oldest first. */
	List<LocalDate> listDays(long accountHash)
	{
		Filepath dir = daysDir(accountHash);
		if (!dir.exists())
		{
			return new ArrayList<>();
		}
		try (Stream<Filepath> files = dir.walk(1))
		{
			List<LocalDate> dates = new ArrayList<>();
			for (String name : files.map(Filepath::getFileName).collect(Collectors.toList()))
			{
				if (name != null && name.endsWith(".json"))
				{
					try
					{
						dates.add(LocalDate.parse(name.substring(0, name.length() - 5)));
					}
					catch (DateTimeParseException e)
					{
						// Not a day file.
					}
				}
			}
			dates.sort(null);
			return dates;
		}
		catch (IOException e)
		{
			log.warn("Unable to list {}", dir, e);
			return new ArrayList<>();
		}
	}

	/** Stored days from {@code from} to {@code to} inclusive, oldest first. */
	List<DayRecord> readRange(long accountHash, LocalDate from, LocalDate to)
	{
		List<DayRecord> days = new ArrayList<>();
		for (LocalDate d : listDays(accountHash))
		{
			if (!d.isBefore(from) && !d.isAfter(to))
			{
				DayRecord day = readDay(accountHash, d);
				if (day != null)
				{
					days.add(day);
				}
			}
		}
		return days;
	}

	Gson gson()
	{
		return gson;
	}
}
