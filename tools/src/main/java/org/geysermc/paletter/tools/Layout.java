package org.geysermc.paletter.tools;

/** Where the repository keeps its non-code inputs, relative to its root. See data/README.md. */
public final class Layout {
    /** The component model and the generators' inputs: components.json, mappings.json, names.json, ... */
    public static final String MODEL = "data/model";
    public static final String FALLBACKS = "data/fallbacks";
    /** One directory per BDS version: captures, Script API dump, experiment record, report. */
    public static final String BDS = "data/bds";
    /** The add-on packs the tooling runs on BDS. */
    public static final String ADDONS = "data/addons";
    public static final String DUMP_ADDON = ADDONS + "/dump";
    public static final String CAPTURE_ADDON = ADDONS + "/capture";
    public static final String CREATIVE_ADDON = ADDONS + "/creative";

    private Layout() {
    }
}
