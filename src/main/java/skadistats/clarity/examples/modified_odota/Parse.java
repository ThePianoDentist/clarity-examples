// Originally from https://github.com/odota/parser/blob/master/src/main/java/opendota/Parse.java

package skadistats.clarity.examples.modified_odota;

import com.google.gson.Gson;
import com.google.protobuf.GeneratedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skadistats.clarity.decoder.Util;
import skadistats.clarity.model.CombatLogEntry;
import skadistats.clarity.model.Entity;
import skadistats.clarity.model.FieldPath;
import skadistats.clarity.model.StringTable;
import skadistats.clarity.processor.entities.Entities;
import skadistats.clarity.processor.entities.OnEntityLeft;
import skadistats.clarity.processor.entities.UsesEntities;
import skadistats.clarity.processor.gameevents.OnCombatLogEntry;
import skadistats.clarity.processor.reader.OnMessage;
import skadistats.clarity.processor.reader.OnTickStart;
import skadistats.clarity.processor.runner.Context;
import skadistats.clarity.processor.runner.SimpleRunner;
import skadistats.clarity.processor.stringtables.StringTables;
import skadistats.clarity.processor.stringtables.UsesStringTable;
import skadistats.clarity.source.MappedFileSource;
import skadistats.clarity.wire.common.proto.DotaUserMessages.CDOTAUserMsg_SpectatorPlayerUnitOrders;
import skadistats.clarity.wire.common.proto.DotaUserMessages.DOTA_COMBATLOG_TYPES;
import skadistats.clarity.wire.s2.proto.S2DotaGcCommon.CMsgDOTAMatch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Parse {

    private static final Logger log = LoggerFactory.getLogger(skadistats.clarity.examples.smoketimings.Main.class.getPackage().getClass());
    private static final Pattern ID_DOT_DEM = Pattern.compile("(\\d+).DEM", Pattern.CASE_INSENSITIVE);

    private class Entry { //
        public long matchID;
        public Integer tick;
        public Integer time;
        public String type;
        public Integer team;
        public String unit;
        public String key;
        public Integer value;
        public Integer slot;
        public Integer player_slot;
        //chat event fields
        public Integer player1;
        public Integer player2;
        //combat log fields
        public String attackername;
        public String targetname;
        public String sourcename;
        public String targetsourcename;
        public Boolean attackerhero;
        public Boolean targethero;
        public Boolean attackerillusion;
        public Boolean targetillusion;
        public String inflictor;
        public Integer gold_reason;
        public Integer xp_reason;
        public String valuename;
        //public Float stun_duration;
        //public Float slow_duration;
        //entity fields
        public Integer gold;
        public Integer lh;
        public Integer xp;
        public Integer x;
        public Integer y;
        public Integer z;
        public Integer health;
        public Integer max_health;
        public Float stuns;
        public Integer hero_id;
        public List<Item> hero_inventory;
        public Integer life_state;
        public Integer level;
        public Integer kills;
        public Integer deaths;
        public Integer assists;
        public Integer denies;
        public Boolean entityleft;
        public Integer ehandle;
        public Integer obs_placed;
        public Integer sen_placed;
        public Integer creeps_stacked;
        public Integer camps_stacked;
        public Integer rune_pickups;
        public Float stun_duration;
        public Float slow_duration;

        public Entry() {
        }

        public Entry(Integer time, Integer tick) {
            this.time = time;
            this.tick = tick;
        }
    }

    private class Item {
        String id;
        //Charges can be used to determine how many items are stacked together on stackable items
        Integer num_charges;
        //item_ward_dispenser uses num_changes for observer wards
        //and num_secondary_changes for sentry wards count
        //and is considered not stackable
        Integer num_secondary_charges;
    }

    private class UnknownItemFoundException extends RuntimeException {
        public UnknownItemFoundException(String message) {
            super(message);
        }
    }

    float INTERVAL = 1;
    float nextInterval = 0;
    Integer time = 0;
    int numPlayers = 10;
    int[] validIndices = new int[numPlayers];
    boolean init = false;
    int gameStartTime = 0;
    private Gson g = new Gson();
    HashMap<String, Integer> name_to_slot = new HashMap<String, Integer>();
    HashMap<Integer, Integer> slot_to_playerslot = new HashMap<Integer, Integer>();
    HashMap<Long, Integer> steamid_to_playerslot = new HashMap<Long, Integer>();
    private long matchID;
    private String searchType;

    private long compileMatchID(String filename){
        Matcher matcher = ID_DOT_DEM.matcher(filename);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        throw new NumberFormatException("No match ID at end of filename: " + filename);
    }

//    public Parse(InputStream input, OutputStream output) throws IOException
//    {
//
//        is = input;
//        os = output;
//        long tStart = System.currentTimeMillis();
//        new SimpleRunner(new InputStreamSource(is)).runWith(this);
//        long tMatch = System.currentTimeMillis() - tStart;
//        System.err.format("total time taken: %s\n", (tMatch) / 1000.0);
//    }

    public void run(String filename) throws IOException {
        this.matchID = compileMatchID(filename);
        long tStart = System.currentTimeMillis();
        new SimpleRunner(new MappedFileSource(filename)).runWith(this);
        long tMatch = System.currentTimeMillis() - tStart;
        log.info("[{}] total time taken: {}s", this.matchID, (tMatch) / 1000.0);
    }


    private String output(Entry e) {
        e.matchID = this.matchID;
        return (g.toJson(e));
    }

    //@OnMessage(GeneratedMessage.class)
    public void onMessage(Context ctx, GeneratedMessage message) {
        System.err.println(message.getClass().getName());
        System.out.println(message.toString());
    }


    @OnMessage(CMsgDOTAMatch.class)
    public void onDotaMatch(Context ctx, CMsgDOTAMatch message)
    {
        //TODO could use this for match overview data for uploads
        //System.err.println(message);
    }

    @OnMessage(CDOTAUserMsg_SpectatorPlayerUnitOrders.class)
    public void onSpectatorPlayerUnitOrders(Context ctx, CDOTAUserMsg_SpectatorPlayerUnitOrders message) {
        Entry entry = new Entry(time, ctx.getTick());
        entry.type = "actions";
        //the entindex points to a CDOTAPlayer.  This is probably the player that gave the order.
        Entity e = ctx.getProcessor(Entities.class).getByIndex(message.getEntindex());
        entry.slot = getEntityProperty(e, "m_iPlayerID", null);
        //Integer handle = (Integer)getEntityProperty(e, "m_hAssignedHero", null);
        //Entity h = ctx.getProcessor(Entities.class).getByHandle(handle);
        //System.err.println(h.getDtClass().getDtName());
        //break actions into types?
        entry.key = String.valueOf(message.getOrderType());

        // write item_id if order_type: "DOTA_UNIT_ORDER_PURCHASE_ITEM"
        if (entry.key.equals("16") && message.hasAbilityIndex()) {
            entry.value = message.getAbilityIndex();
        }

        //System.err.println(message);
        log.info(output(entry));
    }

    @OnCombatLogEntry
    public void onCombatLogEntry(Context ctx, CombatLogEntry cle) {
        try
        {
            time = Math.round(cle.getTimestamp());
            //create a new entry
            Entry combatLogEntry = new Entry(time, ctx.getTick());
            combatLogEntry.type = cle.getType().name();
            //translate the fields using string tables if necessary (get*Name methods)
            combatLogEntry.attackername = cle.getAttackerName();
            combatLogEntry.targetname = cle.getTargetName();
            combatLogEntry.sourcename = cle.getDamageSourceName();
            combatLogEntry.targetsourcename = cle.getTargetSourceName();
            combatLogEntry.inflictor = cle.getInflictorName();
            combatLogEntry.attackerhero = cle.isAttackerHero();
            combatLogEntry.targethero = cle.isTargetHero();
            combatLogEntry.attackerillusion = cle.isAttackerIllusion();
            combatLogEntry.targetillusion = cle.isTargetIllusion();
            combatLogEntry.value = cle.getValue();
            float stunDuration = cle.getStunDuration();
            if (stunDuration > 0) {
                combatLogEntry.stun_duration = stunDuration;
            }
            float slowDuration = cle.getSlowDuration();
            if (slowDuration > 0) {
                combatLogEntry.slow_duration = slowDuration;
            }
            //value may be out of bounds in string table, we can only get valuename if a purchase (type 11)
            if (cle.getType() == DOTA_COMBATLOG_TYPES.DOTA_COMBATLOG_PURCHASE) {
                combatLogEntry.valuename = cle.getValueName();
            }
            else if (cle.getType() == DOTA_COMBATLOG_TYPES.DOTA_COMBATLOG_GOLD) {
                combatLogEntry.gold_reason = cle.getGoldReason();
            }
            else if (cle.getType() == DOTA_COMBATLOG_TYPES.DOTA_COMBATLOG_XP) {
                combatLogEntry.xp_reason = cle.getXpReason();
            }

            log.info(output(combatLogEntry));

            if (cle.getType().ordinal() > 19) {
                System.err.println(cle);
            }
        }
        catch(Exception e)
        {
            System.err.println(e);
            System.err.println(cle);
        }
    }

    @OnEntityLeft
    public void onEntityLeft(Context ctx, Entity e) {
        processEntity(ctx, e, true);
    }

    @UsesStringTable("EntityNames")
    @UsesEntities
    @OnTickStart
    public void onTickStart(Context ctx, boolean synthetic) {

        //TODO check engine to decide whether to use s1 or s2 entities
        //ctx.getEngineType()

        //s1 DT_DOTAGameRulesProxy
        Entity grp = ctx.getProcessor(Entities.class).getByDtName("CDOTAGamerulesProxy");
        Entity pr = ctx.getProcessor(Entities.class).getByDtName("CDOTA_PlayerResource");
        Entity dData = ctx.getProcessor(Entities.class).getByDtName("CDOTA_DataDire");
        Entity rData = ctx.getProcessor(Entities.class).getByDtName("CDOTA_DataRadiant");

        if (grp != null)
        {
            //System.err.println(grp);
            //dota_gamerules_data.m_iGameMode = 22
            //dota_gamerules_data.m_unMatchID64 = 1193091757
            time = Math.round((float) getEntityProperty(grp, "m_pGameRules.m_fGameTime", null));

            //initialize nextInterval value
            if (nextInterval == 0)
            {
                nextInterval = time;
            }
            //alternate to combat log for getting game zero time (looks like this is set at the same time as the game start, so it's not any better for streaming)
            /*
            int currGameStartTime = Math.round( (float) grp.getProperty("m_pGameRules.m_flGameStartTime"));
            if (currGameStartTime != gameStartTime){
                gameStartTime = currGameStartTime;
                System.err.println(gameStartTime);
                System.err.println(time);
            }
            */
        }
        if (pr != null)
        {
            //Radiant coach shows up in vecPlayerTeamData as position 5
            //all the remaining dire entities are offset by 1 and so we miss reading the last one and don't get data for the first dire player
            //coaches appear to be on team 1, radiant is 2 and dire is 3?
            //construct an array of valid indices to get vecPlayerTeamData from
            if (!init)
            {
                int added = 0;
                int i = 0;
                //according to @Decoud Valve seems to have fixed this issue and players should be in first 10 slots again
                //sanity check of i to prevent infinite loop when <10 players?
                while (added < numPlayers && i < 100) {
                    try
                    {
                        //check each m_vecPlayerData to ensure the player's team is radiant or dire
                        int playerTeam = getEntityProperty(pr, "m_vecPlayerData.%i.m_iPlayerTeam", i);
                        int teamSlot = getEntityProperty(pr, "m_vecPlayerTeamData.%i.m_iTeamSlot", i);
                        Long steamid = getEntityProperty(pr, "m_vecPlayerData.%i.m_iPlayerSteamID", i);
                        //System.err.format("%s %s %s: %s\n", i, playerTeam, teamSlot, steamid);
                        if (playerTeam == 2 || playerTeam == 3) {
                            //output the player_slot based on team and teamslot
                            Entry entry = new Entry(time, ctx.getTick());
                            entry.type = "player_slot";
                            entry.key = String.valueOf(added);
                            entry.value = (playerTeam == 2 ? 0 : 128) + teamSlot;
                            log.info(output(entry));
                            //add it to validIndices, add 1 to added
                            validIndices[added] = i;
                            added += 1;
                            slot_to_playerslot.put(added, entry.value);
                            steamid_to_playerslot.put(steamid, entry.value);
                        }
                    }
                    catch(Exception e)
                    {
                        //swallow the exception when an unexpected number of players (!=10)
                        //System.err.println(e);
                    }

                    i += 1;
                }
                init = true;
            }

            if (time >= nextInterval)
            {
                //System.err.println(pr);
                for (int i = 0; i < numPlayers; i++)
                {
                    Integer hero = getEntityProperty(pr, "m_vecPlayerTeamData.%i.m_nSelectedHeroID", validIndices[i]);
                    int handle = getEntityProperty(pr, "m_vecPlayerTeamData.%i.m_hSelectedHero", validIndices[i]);
                    int playerTeam = getEntityProperty(pr, "m_vecPlayerData.%i.m_iPlayerTeam", validIndices[i]);
                    int teamSlot = getEntityProperty(pr, "m_vecPlayerTeamData.%i.m_iTeamSlot", validIndices[i]);

                    //2 is radiant, 3 is dire, 1 is other?
                    Entity dataTeam = playerTeam == 2 ? rData : dData;

                    Entry entry = new Entry(time, ctx.getTick());
                    entry.type = "interval";
                    entry.slot = i;

                    if (teamSlot >= 0)
                    {
                        entry.gold = getEntityProperty(dataTeam, "m_vecDataTeam.%i.m_iTotalEarnedGold", teamSlot);
                        entry.lh = getEntityProperty(dataTeam, "m_vecDataTeam.%i.m_iLastHitCount", teamSlot);
                        entry.xp = getEntityProperty(dataTeam, "m_vecDataTeam.%i.m_iTotalEarnedXP", teamSlot);
                        entry.stuns = getEntityProperty(dataTeam, "m_vecDataTeam.%i.m_fStuns", teamSlot);
                    }

                    entry.level = getEntityProperty(pr, "m_vecPlayerTeamData.%i.m_iLevel", validIndices[i]);
                    entry.kills = getEntityProperty(pr, "m_vecPlayerTeamData.%i.m_iKills", validIndices[i]);
                    entry.deaths = getEntityProperty(pr, "m_vecPlayerTeamData.%i.m_iDeaths", validIndices[i]);
                    entry.assists = getEntityProperty(pr, "m_vecPlayerTeamData.%i.m_iAssists", validIndices[i]);
                    entry.denies = getEntityProperty(dataTeam, "m_vecDataTeam.%i.m_iDenyCount", teamSlot);
                    entry.obs_placed = getEntityProperty(dataTeam, "m_vecDataTeam.%i.m_iObserverWardsPlaced", teamSlot);
                    entry.sen_placed = getEntityProperty(dataTeam, "m_vecDataTeam.%i.m_iSentryWardsPlaced", teamSlot);
                    entry.creeps_stacked = getEntityProperty(dataTeam, "m_vecDataTeam.%i.m_iCreepsStacked", teamSlot);
                    entry.camps_stacked = getEntityProperty(dataTeam, "m_vecDataTeam.%i.m_iCampsStacked", teamSlot);
                    entry.rune_pickups = getEntityProperty(dataTeam, "m_vecDataTeam.%i.m_iRunePickups", teamSlot);

                    //TODO: gem, rapier time?
                    //need to dump inventory items for each player and possibly keep track of item entity handles

                    //get the player's hero entity
                    Entity e = ctx.getProcessor(Entities.class).getByHandle(handle);
                    //get the hero's coordinates
                    if (e != null)
                    {
                        //System.err.println(e);
                        entry.x = getEntityProperty(e, "CBodyComponent.m_cellX", null);
                        entry.y = getEntityProperty(e, "CBodyComponent.m_cellY", null);
                        //System.err.format("%s, %s\n", entry.x, entry.y);
                        //get the hero's entity name, ex: CDOTA_Hero_Zuus
                        entry.unit = e.getDtClass().getDtName();
                        entry.hero_id = hero;
                        entry.life_state = getEntityProperty(e, "m_lifeState", null);
                        // Added healths as feel like they'll come in useful for me (checking jukes/close escapes etc)
                        entry.health = getEntityProperty(e, "m_iHealth", null);
                        entry.max_health = getEntityProperty(e, "m_iMaxHealth", null);
                        //check if hero has been assigned to entity
                        if (hero > 0)
                        {
                            //get the hero's entity name, ex: CDOTA_Hero_Zuus
                            String unit = e.getDtClass().getDtName();
                            //grab the end of the name, lowercase it
                            String ending = unit.substring("CDOTA_Unit_Hero_".length());
                            //valve is bad at consistency and the combat log name could involve replacing camelCase with _ or not!
                            //double map it so we can look up both cases
                            String combatLogName = "npc_dota_hero_" + ending.toLowerCase();
                            //don't include final underscore here since the first letter is always capitalized and will be converted to underscore
                            String combatLogName2 = "npc_dota_hero" + ending.replaceAll("([A-Z])", "_$1").toLowerCase();
                            //System.err.format("%s, %s, %s\n", unit, combatLogName, combatLogName2);
                            //populate for combat log mapping
                            name_to_slot.put(combatLogName, entry.slot);
                            name_to_slot.put(combatLogName2, entry.slot);

                            entry.hero_inventory = getHeroInventory(ctx, e);
                        }
                    }
                    log.info(output(entry));
                }
                nextInterval += INTERVAL;
            }
        }
    }

    private List<Item> getHeroInventory(Context ctx, Entity eHero) {
        List<Item> inventoryList = new ArrayList<>(6);

        for (int i = 0; i < 6; i++) {
            try {
                Item item = getHeroItem(ctx, eHero, i);
                if(item != null) {
                    inventoryList.add(item);
                }
            } catch (UnknownItemFoundException e) {
                return null;
            }
        }

        return inventoryList;
    }

    /**
     * Uses "EntityNames" string table and Entities processor
     * @param ctx Context
     * @param eHero Hero entity
     * @param idx 0-5 - inventory, 6-8 - backpack, 9-16 - stash
     * @return {@code null} - empty slot. Throws @{@link UnknownItemFoundException} if item information can't be extracted
     */
    private Item getHeroItem(Context ctx, Entity eHero, int idx) throws UnknownItemFoundException {
        StringTable stEntityNames = ctx.getProcessor(StringTables.class).forName("EntityNames");
        Entities entities = ctx.getProcessor(Entities.class);

        Integer hItem = eHero.getProperty("m_hItems." + Util.arrayIdxToString(idx));
        if (hItem == 0xFFFFFF) {
            return null;
        }
        Entity eItem = entities.getByHandle(hItem);
        if(eItem == null) {
            throw new UnknownItemFoundException(String.format("Can't find item by its handle (%d)", hItem));
        }
        String itemName = stEntityNames.getNameByIndex(eItem.getProperty("m_pEntity.m_nameStringableIndex"));
        if(itemName == null) {
            throw new UnknownItemFoundException("Can't get item name from EntityName string table");
        }

        Item item = new Item();
        item.id = itemName;
        int numCharges = eItem.getProperty("m_iCurrentCharges");
        if(numCharges != 0) {
            item.num_charges = numCharges;
        }
        int numSecondaryCharges = eItem.getProperty("m_iSecondaryCharges");
        if(numSecondaryCharges != 0) {
            item.num_secondary_charges = numSecondaryCharges;
        }

        return item;
    }

    public <T> T getEntityProperty(Entity e, String property, Integer idx) {
        try {
            if (e == null) {
                return null;
            }
            if (idx != null) {
                property = property.replace("%i", Util.arrayIdxToString(idx));
            }
            FieldPath fp = e.getDtClass().getFieldPathForName(property);
            return e.getPropertyForFieldPath(fp);
        }
        catch (Exception ex) {
            return null;
        }
    }

    public void processEntity(Context ctx, Entity e, boolean entityLeft)
    {
        //CDOTA_NPC_Observer_Ward
        //CDOTA_NPC_Observer_Ward_TrueSight
        //s1 "DT_DOTA_NPC_Observer_Ward"
        //s1 "DT_DOTA_NPC_Observer_Ward_TrueSight"
        boolean isObserver = e.getDtClass().getDtName().equals("CDOTA_NPC_Observer_Ward");
        boolean isSentry = e.getDtClass().getDtName().equals("CDOTA_NPC_Observer_Ward_TrueSight");
        if (isObserver || isSentry) {
            //System.err.println(e);
            Entry entry = new Entry(time, ctx.getTick());
            Integer x = getEntityProperty(e, "CBodyComponent.m_cellX", null);
            Integer y = getEntityProperty(e, "CBodyComponent.m_cellY", null);
            Integer z = getEntityProperty(e, "CBodyComponent.m_cellZ", null);
            Integer[] pos = {x, y};
            entry.x = x;
            entry.y = y;
            entry.z = z;
            if (entityLeft)
            {
                entry.type = isObserver ? "obs_left" : "sen_left";
            }
            else
            {
                entry.type = isObserver ? "obs" : "sen";
            }
            entry.key = Arrays.toString(pos);
            entry.entityleft = entityLeft;
            entry.ehandle = e.getHandle();
            //System.err.println(entry.key);
            Integer owner = getEntityProperty(e, "m_hOwnerEntity", null);
            Entity ownerEntity = ctx.getProcessor(Entities.class).getByHandle(owner);
            entry.slot = ownerEntity != null ? (Integer) getEntityProperty(ownerEntity, "m_iPlayerID", null) : null;
            //2/3 radiant/dire
            //entry.team = e.getProperty("m_iTeamNum");
            log.info(output(entry));
        }
    }
}