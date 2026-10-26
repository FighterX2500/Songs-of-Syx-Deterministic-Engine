package sosdet.game.cmd;

import java.util.List;

import game.GAME;
import game.battle.DivisionBanners.DivisionBanner;
import game.battle.div.Div;
import game.battle.div.DivInfo;
import game.battle.util.DIV_SPEC.DIV_SPECE;
import game.faction.FACTIONS;
import game.faction.FBanner;
import game.faction.Faction;
import game.faction.FactionProfileFlusher;
import game.faction.player.Player;
import game.nobility.NOBLES;
import game.nobility.Noble;
import game.nobility.NobleOffice;
import init.sprite.BitmapSprite;
import init.type.HCLASS_RACE;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.room.main.RoomInstance;
import settlement.stats.STATS;
import settlement.tilemap.SettMarks.SettMark;
import snake2d.util.color.ColorImp;
import snake2d.util.sets.LIST;
import snake2d.util.sprite.text.Str;
import snake2d.util.sprite.text.StringInputSprite;
import sosdet.Engine;
import sosdet.Hooks;
import sosdet.ai.CmdReader;
import sosdet.ai.CmdWriter;
import world.WORLD;
import world.map.regions.Region;

/**
 * Names, banner colours, map marks and nobles (ids 5000-5999). A typed name travels whole: each edit the UI writes
 * becomes one command with the input's full text, so the last one applied wins and a lost keystroke cannot exist.
 */
public final class Names {

    static final int DIVISION_NAME = 5000;
    static final int ROOM_NAME = 5001;
    static final int FACTION_NAME = 5002;
    static final int RULER_NAME = 5003;
    static final int MARK_ADD = 5004;
    static final int MARK_REMOVE = 5005;
    static final int MARK_NAME = 5006;
    static final int MARK_ORDER = 5007;
    static final int COLOUR = 5008;
    static final int BANNER_TYPE = 5009;
    static final int SUBJECT_NAME = 5010;
    static final int NOBLE = 5011;
    static final int NOBLE_OFFICE = 5012;
    static final int NOBLE_RANK = 5013;
    static final int NOBLE_REPLACE = 5014;
    static final int BANNER = 5015;
    static final int PLAYER_BANNER = 5016;
    static final int DIVISION_BANNER = 5017;

    /**
     * A colour's owner: a division banner (part 0 symbol, 1 background), the player's banner (parts 0-3), a mark. Both
     * banners are now sent whole ({@link #PLAYER_BANNER}, {@link #DIVISION_BANNER}); their colour commands,
     * {@link #BANNER_TYPE} and {@link #BANNER} (the player's banner without its pixels) still apply from logs recorded
     * before that.
     */
    private static final int DIV_BANNER = 0;
    private static final int FACTION_BANNER = 1;
    private static final int MARK = 2;

    static final List<Command> ALL = List.of(
            new Command(DIVISION_NAME, "divisionName", r -> {
                Div d = Args.div(r);
                d.info.name().clear().add(r.s());
                return true;
            }),
            new Command(ROOM_NAME, "roomName", r -> {
                RoomInstance room = Args.room(r);
                String name = r.s();
                if (room == null) {
                    return false;
                }
                room.name().clear().add(name);
                return true;
            }),
            new Command(FACTION_NAME, "factionName", r -> {
                String name = r.s();
                Faction f = FACTIONS.player();
                f.name.clear().add(name);
                if (f.capitolRegion() != null) {
                    f.capitolRegion().info.name().clear().add(name); // as the profile's input does
                }
                return true;
            }),
            new Command(RULER_NAME, "rulerName", r -> {
                FACTIONS.player().rulerName.clear().add(r.s());
                return true;
            }),
            new Command(MARK_ADD, "markAdd", r -> {
                int tx = r.i();
                int ty = r.i();
                SettMark m = SETT.TILE_MAP().marks.make();
                if (m == null) {
                    return false;
                }
                m.set(tx, ty);
                return true;
            }),
            new Command(MARK_REMOVE, "markRemove", r -> {
                SettMark m = mark(r);
                if (m == null) {
                    return false;
                }
                m.remove();
                return true;
            }),
            new Command(MARK_NAME, "markName", r -> {
                SettMark m = mark(r);
                String name = r.s();
                if (m == null) {
                    return false;
                }
                m.name.clear().add(name);
                return true;
            }),
            new Command(MARK_ORDER, "markOrder", r -> {
                SettMark m = mark(r);
                int position = r.i();
                if (m == null) {
                    return false;
                }
                m.setPosition(position);
                return true;
            }),
            new Command(COLOUR, "colour", r -> {
                int kind = r.i();
                ColorImp c = colour(kind, r.i(), r.i());
                int channel = r.i();
                int value = r.i();
                if (c == null) {
                    return false;
                }
                if (channel == 0) {
                    c.setRed(value);
                } else if (channel == 1) {
                    c.setGreen(value);
                } else {
                    c.setBlue(value);
                }
                if (kind == FACTION_BANNER) { // the banner editor's own follow-up: the world map redraws the realm
                    minimapRedraw();
                }
                return true;
            }),
            new Command(BANNER_TYPE, "bannerType", r -> {
                FACTIONS.player().banner().bannerTypeSet(r.i());
                return true;
            }),
            new Command(BANNER, "banner", r -> bannerApply(BannerDraft.FACTION, values(r, BannerDraft.FACTION_VALUES))),
            new Command(PLAYER_BANNER, "playerBanner",
                    r -> bannerApply(BannerDraft.FACTION, values(r, BannerDraft.size(BannerDraft.FACTION)))),
            new Command(DIVISION_BANNER, "divisionBanner", r -> {
                int i = r.i();
                int[] v = values(r, BannerDraft.size(Math.max(i, 0)));
                return i >= 0 && i < GAME.ARMIES().banners.size() && bannerApply(i, v);
            }),
            new Command(SUBJECT_NAME, "subjectName", r -> {
                Humanoid h = Args.humanoid(r);
                String name = r.s();
                if (h == null) {
                    return false;
                }
                STATS.APPEARANCE().setCustomName(h.indu(), name);
                return true;
            }),
            new Command(NOBLE, "noble", r -> {
                Humanoid h = Args.humanoid(r);
                if (h == null || h.noble() != null
                        || GAME.NOBLE().active().size() >= GAME.NOBLE().MAX.get(FACTIONS.player())) {
                    return false;
                }
                h.nobleSet();
                return true;
            }),
            new Command(NOBLE_OFFICE, "nobleOffice", r -> {
                Noble n = noble(r);
                NobleOffice o = office(r);
                if (n == null) {
                    return false;
                }
                GAME.NOBLE().setOffice(n, o);
                return true;
            }),
            new Command(NOBLE_RANK, "nobleRank", r -> {
                Noble n = noble(r);
                if (n == null || n.rank() >= GAME.NOBLE().maxRanks() - 1) {
                    return false;
                }
                GAME.NOBLE().ranksAllocate(n);
                return true;
            }),
            new Command(NOBLE_REPLACE, "nobleReplace", r -> {
                Humanoid h = Args.humanoid(r);
                NobleOffice o = office(r);
                int ranks = r.i();
                NOBLES nobles = GAME.NOBLE();
                if (h == null || h.noble() != null || nobles.active().size() >= nobles.MAX.get(HCLASS_RACE.clP())) {
                    return false;
                }
                // the death message's "replace" button, after it chose the citizen
                h.nobleSet();
                nobles.setOffice(h.noble(), o);
                for (int i = 0; i < ranks; i++) {
                    if (nobles.ranksAllocated() < nobles.MAX_RANKS.get(HCLASS_RACE.clP())) {
                        nobles.ranksAllocate(h.noble());
                    }
                }
                return true;
            }));

    private Names() {
    }

    // ------------------------------------------------------------ names

    /**
     * Hook in {@code UIDivEditor}'s name change, applied by the editor's "accept": a city division's new name. A world
     * army's division stays with the world (stage D).
     */
    public static boolean divisionName(DIV_SPECE to, CharSequence name) {
        if (!(to instanceof DivInfo) || !Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.div(new CmdWriter(DIVISION_NAME), ((DivInfo) to).div()).s(name.toString()));
        return true;
    }

    /** Hook in {@code UIRoom}'s name input: the room's name, after each edit, whole. */
    public static boolean roomName(RoomInstance room, CharSequence name) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.room(new CmdWriter(ROOM_NAME), room).s(name.toString()));
        return true;
    }

    /**
     * {@code UIProfile}'s name inputs edit the faction's live name in place. With a session the player types into the
     * input's own text instead, which follows the live name while the input is not being typed into.
     */
    public static Str nameInput(StringInputSprite input, Str live, Str draft) {
        if (!Commands.capturing()) {
            return live;
        }
        if (!input.listening()) {
            draft.clear().add(live);
        }
        return draft;
    }

    /** Hook in {@code UIProfile}'s faction name input: the faction's name and its capital's, after each edit. */
    public static boolean factionName(CharSequence name) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(FACTION_NAME).s(name.toString()));
        return true;
    }

    /** Hook in {@code UIProfile}'s ruler name input, after each edit. */
    public static boolean rulerName(CharSequence name) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(RULER_NAME).s(name.toString()));
        return true;
    }

    /**
     * Hook in {@code UIProfile}'s Load (SOS-78): the saved profile's faction name, ruler name and banner become the same
     * commands as typing them and accepting the banner, so every PC applies them at one step. The profile's colour masks
     * stay this PC's, as before. True when the hook did the load.
     */
    public static boolean profileLoad() {
        if (!Commands.capturing() || !FactionProfileFlusher.canLoad(FACTIONS.player())) {
            return false;
        }
        bannerCancel(); // an open draft goes back first; the load's banner is a draft of its own
        Player p = FACTIONS.player();
        String name = p.name.toString();
        String ruler = p.rulerName.toString();
        Hooks.bannerDraft().keep(BannerDraft.FACTION);
        FactionProfileFlusher.load(p); // the banner as a draft, as the editor would leave it; the names to send
        String loadedName = p.name.toString();
        String loadedRuler = p.rulerName.toString();
        p.name.clear().add(name); // the names change on every PC at the commands' step, not here first
        p.rulerName.clear().add(ruler);
        factionName(loadedName);
        rulerName(loadedRuler);
        bannerAccept();
        minimapRedraw();
        return true;
    }

    /** Hook in {@code UISubjectActions}' rename, given when the rename dialog is accepted. */
    public static boolean subjectName(Humanoid h, String name) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.entity(new CmdWriter(SUBJECT_NAME), h).s(name));
        return true;
    }

    // ------------------------------------------------------------ map marks

    /** Hook in {@code UIMiniHotSpots}' placer: a new mark (its colour is drawn when it applies, inside the step). */
    public static boolean markAdd(int tx, int ty) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(MARK_ADD).i(tx).i(ty));
        return true;
    }

    /** Hook in {@code UIMiniHotSpots}' mark panel: the trash button. */
    public static boolean markRemove(SettMark m) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(mark(new CmdWriter(MARK_REMOVE), m));
        return true;
    }

    /** Hook in {@code UIMiniHotSpots}' mark panel: the name input, after each edit, whole. */
    public static boolean markName(SettMark m, CharSequence name) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(mark(new CmdWriter(MARK_NAME), m).s(name.toString()));
        return true;
    }

    /** Hook in {@code UIMiniHotSpots}' mark panel: the mark's place in the list. */
    public static boolean markOrder(SettMark m, int position) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(mark(new CmdWriter(MARK_ORDER), m).i(position));
        return true;
    }

    // ------------------------------------------------------------ colours and banners

    /**
     * Hook in {@code GColorPicker}'s three sliders ({@code channel} 0 red, 1 green, 2 blue). Map marks become commands.
     * The player's banner and the division banners are drafts ({@link BannerDraft}): the slider sets them on this PC
     * only, and Accept sends them. Any other picker's colour (the profile's colour masks, debug tools) stays local, as
     * before. A slider held down sets its value every frame; the log drops an exact repeat of this PC's last command
     * until it applies, so a drag sends one command per new value.
     */
    public static boolean colour(ColorImp c, int channel, int value) {
        if (!Commands.capturing()) {
            return false;
        }
        int banner = BannerDraft.owner(c);
        if (banner != BannerDraft.NONE) {
            draft(banner);
            return false;
        }
        CmdWriter w = new CmdWriter(COLOUR);
        if (!owner(w, c)) {
            return false;
        }
        Commands.capture(w.i(channel).i(value));
        return true;
    }

    /** Hook in {@code FBanner.bannerTypeSet}: the profile's arrows change the player's banner shape in the draft. */
    public static boolean bannerType(FBanner banner, int type) {
        if (Commands.capturing() && banner == FACTIONS.player().banner()) {
            draft(BannerDraft.FACTION);
        }
        return false;
    }

    /** Hook in {@code BitmapSpriteEditor}, before it sets a pixel: a banner's pixels change in the draft. */
    public static void bannerPixel(BitmapSprite sprite) {
        if (!Commands.capturing()) {
            return;
        }
        int banner = BannerDraft.owner(sprite);
        if (banner != BannerDraft.NONE) {
            draft(banner);
        }
    }

    /** Hook in the banner editors: their Accept and Cancel show only in a session. */
    public static boolean bannerDraftShown() {
        return Engine.active();
    }

    /** Hook in the banner editors: true while this PC's player has a banner draft to accept or cancel. */
    public static boolean bannerDrafting() {
        return Commands.capturing() && Hooks.bannerDraft().open();
    }

    /** Hook in the banner editors' Accept: the drafted banner, whole with its pixels, is one command. */
    public static void bannerAccept() {
        if (!bannerDrafting()) {
            return;
        }
        BannerDraft d = Hooks.bannerDraft();
        int banner = d.target();
        CmdWriter w = banner == BannerDraft.FACTION ? new CmdWriter(PLAYER_BANNER) : new CmdWriter(DIVISION_BANNER).i(banner);
        for (int v : d.take()) {
            w.i(v);
        }
        Commands.capture(w);
    }

    /** Hook in the banner editors' Cancel, and where the full-screen windows close: the banner goes back as it was. */
    public static void bannerCancel() {
        if (!Commands.capturing()) {
            return;
        }
        BannerDraft d = Hooks.bannerDraft();
        boolean player = d.target() == BannerDraft.FACTION;
        if (d.cancel() && player) {
            minimapRedraw();
        }
    }

    /**
     * Hook in {@code IPopup}, where the popup's content changes or it closes: a division banner still in its draft goes
     * back as it was, as with Cancel. The player's banner is edited in a window, not a popup, so its draft stays.
     */
    public static void bannerPopupLeft() {
        if (Commands.capturing() && Hooks.bannerDraft().target() >= 0) {
            Hooks.bannerDraft().cancel();
        }
    }

    /** A change to {@code banner} is about to be made: a draft of another banner is sent first, then this one opens. */
    private static void draft(int banner) {
        BannerDraft d = Hooks.bannerDraft();
        if (d.open() && d.target() != banner) {
            bannerAccept();
        }
        d.keep(banner);
    }

    /** A banner command: its values replace the banner, and this PC's draft of it ends (the command wins). */
    private static boolean bannerApply(int banner, int[] v) {
        // the player's banner applies on every PC (SOS-78), for the human who sent it: only that PC's draft is its own
        if (banner != BannerDraft.FACTION || FACTIONS.player().index() == Hooks.localHuman()) {
            Hooks.bannerDraft().drop(banner, v); // on the PC that accepted, the draft has already ended
        }
        BannerDraft.write(banner, v);
        if (banner == BannerDraft.FACTION) {
            minimapRedraw();
        }
        return true;
    }

    private static int[] values(CmdReader r, int n) {
        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            v[i] = r.i();
        }
        return v;
    }

    /** The banner editor's own follow-up to a colour change: the world map redraws the player's realm. */
    private static void minimapRedraw() {
        for (Region region : FACTIONS.player().realm().all()) {
            WORLD.MINIMAP().updateRegion(region);
        }
    }

    // ------------------------------------------------------------ nobles

    /** At {@code UISubjectActions}' "elevate" (Humanoid is not menu-safe): the subject becomes a noble. */
    public static boolean nobleSet(Humanoid h) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.entity(new CmdWriter(NOBLE), h));
        return true;
    }

    /** Hook in {@code NOBLES.setOffice}: the noble screen's office buttons. */
    public static boolean nobleOffice(Noble n, NobleOffice office) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(NOBLE_OFFICE).i(n.index()).i(office == null ? -1 : office.index));
        return true;
    }

    /** Hook in {@code NOBLES.ranksAllocate}: the noble screen's promotion button. */
    public static boolean nobleRank(Noble n) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(new CmdWriter(NOBLE_RANK).i(n.index()));
        return true;
    }

    /**
     * At the death message's "replace" button, once it chose the citizen: the citizen becomes a noble with the dead
     * noble's office and ranks, in one command (the office and ranks need the noble the first part makes).
     */
    public static boolean nobleReplace(Humanoid h, NobleOffice office, int ranks) {
        if (!Commands.capturing()) {
            return false;
        }
        Commands.capture(Args.entity(new CmdWriter(NOBLE_REPLACE), h).i(office == null ? -1 : office.index).i(ranks));
        return true;
    }

    // ------------------------------------------------------------ arguments

    /** A map mark, by its slot among all marks. */
    private static CmdWriter mark(CmdWriter w, SettMark m) {
        LIST<SettMark> all = SETT.TILE_MAP().marks.all;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) == m) {
                return w.i(i);
            }
        }
        throw new IllegalArgumentException("SOSDET: a map mark that is not one of the settlement's");
    }

    /** The mark in that slot, or null when the slot is out of range or the mark has been removed since. */
    private static SettMark mark(CmdReader r) {
        int i = r.i();
        LIST<SettMark> all = SETT.TILE_MAP().marks.all;
        if (i < 0 || i >= all.size() || !all.get(i).active) {
            return null;
        }
        return all.get(i);
    }

    /** A noble slot, or null when it is out of range or no one holds it any more. */
    private static Noble noble(CmdReader r) {
        int i = r.i();
        if (i < 0 || i >= GAME.NOBLE().ALL().size()) {
            return null;
        }
        Noble n = GAME.NOBLE().ALL().get(i);
        return n.subject() == null ? null : n;
    }

    private static NobleOffice office(CmdReader r) {
        int i = r.i();
        return i < 0 ? null : GAME.NOBLE().OFFICES.get(i);
    }

    /** Writes a shared colour's owner (kind, index, part); false for a colour only this PC has (or a banner's draft). */
    private static boolean owner(CmdWriter w, ColorImp c) {
        LIST<SettMark> marks = SETT.TILE_MAP().marks.all;
        for (int i = 0; i < marks.size(); i++) {
            if (c == marks.get(i).color) {
                w.i(MARK).i(i).i(0);
                return true;
            }
        }
        return false;
    }

    /** The colour a command names, or null when its owner is gone (a removed mark) or out of range. */
    private static ColorImp colour(int kind, int index, int part) {
        if (kind == DIV_BANNER) {
            if (index < 0 || index >= GAME.ARMIES().banners.size()) {
                return null;
            }
            DivisionBanner b = GAME.ARMIES().banners.get(index);
            return part == 0 ? b.col : b.bg;
        }
        if (kind == FACTION_BANNER) {
            FBanner f = FACTIONS.player().banner();
            return part == 0 ? f.colorBG() : part == 1 ? f.colorFG() : part == 2 ? f.colorBorder() : f.colorPole();
        }
        LIST<SettMark> marks = SETT.TILE_MAP().marks.all;
        if (index < 0 || index >= marks.size() || !marks.get(index).active) {
            return null;
        }
        return marks.get(index).color;
    }
}
