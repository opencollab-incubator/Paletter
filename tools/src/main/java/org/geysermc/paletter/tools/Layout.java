package org.geysermc.paletter.tools;

/** Where the repository keeps its non-code inputs, relative to its root. See docs/data-pipeline.md. */
public final class Layout {
    /** The component model and the generators' inputs: components.json, mappings.json, ... */
    public static final String MODEL = "data/model";
    /** One directory per BDS version: captures, Script API dump, experiment record, report. */
    public static final String BDS = "data/bds";
    /** The add-on packs the tooling runs on BDS. */
    public static final String ADDONS = "data/addons";
    public static final String DUMP_ADDON = ADDONS + "/dump";
    public static final String CREATIVE_ADDON = ADDONS + "/creative";
    public static final String PROBE_ADDON = ADDONS + "/probe";
    public static final String BEHAVIOUR_DUMP_ADDON = ADDONS + "/behaviour-dump";
    public static final String FOOD_PROBE_ADDON = ADDONS + "/food-probe";

    private Layout() {
    }
}
