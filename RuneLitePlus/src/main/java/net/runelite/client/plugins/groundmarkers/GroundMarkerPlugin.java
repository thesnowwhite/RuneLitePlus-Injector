/*
 * Copyright (c) 2018, TheLonelyDev <https://github.com/TheLonelyDev>
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.groundmarkers;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import api.Client;
import static api.Constants.CHUNK_SIZE;
import api.GameState;
import api.MenuAction;
import api.MenuEntry;
import api.Tile;
import api.coords.LocalPoint;
import api.coords.WorldPoint;
import api.events.FocusChanged;
import api.events.GameStateChanged;
import api.events.MenuEntryAdded;
import api.events.MenuOptionClicked;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Ground Markers",
	description = "Enable marking of tiles using the Shift key",
	tags = {"overlay", "tiles"}
)
public class GroundMarkerPlugin extends Plugin
{
	private static final String CONFIG_GROUP = "groundMarker";
	private static final String MARK = "Mark tile";
	private static final Pattern GROUP_MATCHER = Pattern.compile(".*ark tile \\(Group (\\d)\\)");
	private static final String UNMARK = "Unmark tile";
	private static final String WALK_HERE = "Walk here";
	private static final String REGION_PREFIX = "region_";

	private static final Gson GSON = new Gson();

	@Getter(AccessLevel.PACKAGE)
	@Setter(AccessLevel.PACKAGE)
	private boolean hotKeyPressed;

	@Getter(AccessLevel.PACKAGE)
	private final List<GroundMarkerWorldPoint> points = new ArrayList<>();

	@Inject
	private Client client;

	@Inject
	private GroundMarkerInputListener inputListener;

	@Inject
	private ConfigManager configManager;

	@Inject
	private GroundMarkerConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private GroundMarkerOverlay overlay;

	@Inject
	private GroundMarkerMinimapOverlay minimapOverlay;

	@Inject
	private KeyManager keyManager;

	private void savePoints(int regionId, Collection<GroundMarkerPoint> points)
	{
		if (points == null || points.isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, REGION_PREFIX + regionId);
			return;
		}

		String json = GSON.toJson(points);
		configManager.setConfiguration(CONFIG_GROUP, REGION_PREFIX + regionId, json);
	}

	private Collection<GroundMarkerPoint> getPoints(int regionId)
	{
		String json = configManager.getConfiguration(CONFIG_GROUP, REGION_PREFIX + regionId);
		if (Strings.isNullOrEmpty(json))
		{
			return Collections.emptyList();
		}
		return GSON.fromJson(json, new GroundMarkerListTypeToken().getType());
	}

	private static class GroundMarkerListTypeToken extends TypeToken<List<GroundMarkerPoint>>
	{
	}

	@Provides
	GroundMarkerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GroundMarkerConfig.class);
	}

	private void loadPoints()
	{
		points.clear();

		int[] regions = client.getMapRegions();

		if (regions == null)
		{
			return;
		}

		for (int regionId : regions)
		{
			// load points for region
			log.debug("Loading points for region {}", regionId);
			Collection<GroundMarkerPoint> regionPoints = getPoints(regionId);
			Collection<GroundMarkerWorldPoint> worldPoints = translateToWorld(regionPoints);
			points.addAll(worldPoints);
		}
	}

	/**
	 * Translate a collection of ground marker points to world points, accounting for instances
	 *
	 * @param points
	 * @return
	 */
	private Collection<GroundMarkerWorldPoint> translateToWorld(Collection<GroundMarkerPoint> points)
	{
		if (points.isEmpty())
		{
			return Collections.EMPTY_LIST;
		}

		List<GroundMarkerWorldPoint> worldPoints = new ArrayList<>();
		for (GroundMarkerPoint point : points)
		{
			int regionId = point.getRegionId();
			int regionX = point.getRegionX();
			int regionY = point.getRegionY();
			int z = point.getZ();

			WorldPoint worldPoint = WorldPoint.fromRegion(regionId, regionX, regionY, z);

			if (!client.isInInstancedRegion())
			{
				worldPoints.add(new GroundMarkerWorldPoint(point, worldPoint));
				continue;
			}

			// find instance chunks using the template point. there might be more than one.
			int[][][] instanceTemplateChunks = client.getInstanceTemplateChunks();
			for (int x = 0; x < instanceTemplateChunks[z].length; ++x)
			{
				for (int y = 0; y < instanceTemplateChunks[z][x].length; ++y)
				{
					int chunkData = instanceTemplateChunks[z][x][y];
					int rotation = chunkData >> 1 & 0x3;
					int templateChunkY = (chunkData >> 3 & 0x7FF) * CHUNK_SIZE;
					int templateChunkX = (chunkData >> 14 & 0x3FF) * CHUNK_SIZE;
					if (worldPoint.getX() >= templateChunkX && worldPoint.getX() < templateChunkX + CHUNK_SIZE
						&& worldPoint.getY() >= templateChunkY && worldPoint.getY() < templateChunkY + CHUNK_SIZE)
					{
						WorldPoint p = new WorldPoint(client.getBaseX() + x * CHUNK_SIZE + (worldPoint.getX() & (CHUNK_SIZE - 1)),
							client.getBaseY() + y * CHUNK_SIZE + (worldPoint.getY() & (CHUNK_SIZE - 1)),
							worldPoint.getPlane());
						p = rotate(p, rotation);
						worldPoints.add(new GroundMarkerWorldPoint(point, p));
					}
				}
			}
		}
		return worldPoints;
	}

	/**
	 * Rotate the chunk containing the given point to rotation 0
	 *
	 * @param point point
	 * @param rotation rotation
	 * @return world point
	 */
	private static WorldPoint rotateInverse(WorldPoint point, int rotation)
	{
		return rotate(point, 4 - rotation);
	}

	/**
	 * Rotate the coordinates in the chunk according to chunk rotation
	 *
	 * @param point point
	 * @param rotation rotation
	 * @return world point
	 */
	private static WorldPoint rotate(WorldPoint point, int rotation)
	{
		int chunkX = point.getX() & -CHUNK_SIZE;
		int chunkY = point.getY() & -CHUNK_SIZE;
		int x = point.getX() & (CHUNK_SIZE - 1);
		int y = point.getY() & (CHUNK_SIZE - 1);
		switch (rotation)
		{
			case 1:
				return new WorldPoint(chunkX + y, chunkY + (CHUNK_SIZE - 1 - x), point.getPlane());
			case 2:
				return new WorldPoint(chunkX + (CHUNK_SIZE - 1 - x), chunkY + (CHUNK_SIZE - 1 - y), point.getPlane());
			case 3:
				return new WorldPoint(chunkX + (CHUNK_SIZE - 1 - y), chunkY + x, point.getPlane());
		}
		return point;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		// map region has just been updated
		loadPoints();
	}

	@Subscribe
	public void onFocusChanged(FocusChanged focusChanged)
	{
		if (!focusChanged.isFocused())
		{
			hotKeyPressed = false;
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (hotKeyPressed && event.getOption().equals(WALK_HERE))
		{
			MenuEntry[] menuEntries = client.getMenuEntries();
			int lastIndex = menuEntries.length;
			menuEntries = Arrays.copyOf(menuEntries, lastIndex + 4);

			final Tile tile = client.getSelectedSceneTile();
			if (tile == null)
			{
				return;
			}
			final WorldPoint loc = WorldPoint.fromLocalInstance(client, tile.getLocalLocation());
			final int regionId = loc.getRegionID();

			for (int i = 4; i > 0; i--)
			{
				MenuEntry menuEntry = menuEntries[lastIndex] = new MenuEntry();

				final GroundMarkerPoint point = new GroundMarkerPoint(regionId, loc.getRegionX(), loc.getRegionY(), client.getPlane(), i);
				final Optional<GroundMarkerPoint> stream = getPoints(regionId).stream().filter(x -> x.equals(point)).findAny();
				final String option = (stream.isPresent() && stream.get().getGroup() == i) ? UNMARK : MARK;
				menuEntry.setOption(ColorUtil.prependColorTag(Text.removeTags(option + (i == 1 ? "" : " (Group " + i + ")")), getColor(i)));
				menuEntry.setTarget(event.getTarget());
				menuEntry.setType(MenuAction.CANCEL.getId());

				lastIndex++;
			}

			client.setMenuEntries(menuEntries);
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!event.getMenuOption().contains(MARK) && !event.getMenuOption().contains(UNMARK))
		{
			return;
		}

		int group = 1;
		Matcher m = GROUP_MATCHER.matcher(event.getMenuOption());
		if (m.matches())
		{
			group = Integer.parseInt(m.group(1));
		}

		Tile target = client.getSelectedSceneTile();

		if (target == null)
		{
			return;
		}
		markTile(target.getLocalLocation(), group);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		overlayManager.add(minimapOverlay);
		keyManager.registerKeyListener(inputListener);
		loadPoints();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(minimapOverlay);
		keyManager.unregisterKeyListener(inputListener);
		points.clear();
	}

	protected void markTile(LocalPoint localPoint, int group)
	{
		if (localPoint == null)
		{
			return;
		}

		WorldPoint worldPoint = WorldPoint.fromLocalInstance(client, localPoint);

		int regionId = worldPoint.getRegionID();
		GroundMarkerPoint point = new GroundMarkerPoint(regionId, worldPoint.getRegionX(), worldPoint.getRegionY(), client.getPlane(), group);
		log.debug("Updating point: {} - {}", point, worldPoint);

		List<GroundMarkerPoint> points = new ArrayList<>(getPoints(regionId));
		if (points.contains(point))
		{
			GroundMarkerPoint old = points.get(points.indexOf(point));
			points.remove(point);

			if (old.getGroup() != group)
			{
				points.add(point);
			}
		}
		else
		{
			points.add(point);
		}

		savePoints(regionId, points);

		loadPoints();
	}

	private Color getColor(int group)
	{
		Color color = config.markerColor();
		switch (group)
		{
			case 2:
				color = config.markerColor2();
				break;
			case 3:
				color = config.markerColor3();
				break;
			case 4:
				color = config.markerColor4();
		}

		return color;
	}
}