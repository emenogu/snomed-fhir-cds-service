package org.snomed.cdsservice.util;

import java.util.HashMap;
import java.util.Map;

public enum UnitConversion {
    MG_MG("mg", "mg", 1.0),
    MG_G("mg", "g", 0.001),
    MG_MCG("mg", "mcg", 1000.0),
    G_MG("g", "mg", 1000.0),
    G_G("g", "g", 1.0),
    G_MCG("g", "mcg", 1000000.0),
    MCG_MG("mcg", "mg", 0.001),
    MCG_G("mcg", "g", 0.000001),
    MCG_MCG("mcg", "mcg", 1.0),
    L_L("L", "L", 1.0),
    L_ML("L", "mL", 1000.0),
    L_UL("L", "uL", 1000000.0),
    ML_L("mL", "L", 0.001),
    ML_ML("mL", "mL", 1.0),
    ML_UL("mL", "uL", 1000.0),
    UL_L("uL", "L", 0.000001),
    UL_ML("uL", "mL", 0.001),
    UL_UL("uL", "uL", 1.0),
    TABLET_TABLET("Tablet", "Tablet", 1.0),
    TABLET_CAPSULE("Tablet", "Capsule", 1.0),
    CAPSULE_CAPSULE("Capsule", "Capsule", 1.0),
    PUFF_ACTUATION("Actuation", "Actuation", 1.0),
    SPOONFUL_SPOONFUL("Spoonful", "Spoonful", 1.0),
    DROP_DROP("Drop", "Drop", 1.0);


    private static final Map<String, Double> BY_UNIT = new HashMap<>();
    private static final Map<String, Double> BY_NORMALIZED_UNIT = new HashMap<>();
    // NMHS CDSS-HDA-002: SNOMED/Bahmni unit names that mean the same unit as an enum value.
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("milligram", "mg"), Map.entry("milligrams", "mg"),
            Map.entry("gram", "g"), Map.entry("grams", "g"),
            Map.entry("microgram", "mcg"), Map.entry("micrograms", "mcg"), Map.entry("ug", "mcg"), Map.entry("µg", "mcg"),
            Map.entry("milliliter", "ml"), Map.entry("millilitre", "ml"), Map.entry("milliliters", "ml"), Map.entry("millilitres", "ml"),
            Map.entry("liter", "l"), Map.entry("litre", "l"),
            Map.entry("microliter", "ul"), Map.entry("microlitre", "ul"),
            Map.entry("tablets", "tablet"), Map.entry("capsules", "capsule"));

    static {
        for (UnitConversion e : values()) {
            String exactKey = e.inputUnit + "-" + e.targetUnit;
            BY_UNIT.put(exactKey, e.getFactor());
            BY_NORMALIZED_UNIT.put(normalizeUnit(e.inputUnit) + "-" + normalizeUnit(e.targetUnit), e.getFactor());
        }
    }

    public final String inputUnit;
    public final String targetUnit;
    public final Double factor;

    private UnitConversion(String inputUnit, String targetUnit, Double factor) {
        this.inputUnit = inputUnit;
        this.targetUnit = targetUnit;
        this.factor = factor;
    }

    public static Double factorOfConversion(String sourceUnit, String targetUnit) {
        if (sourceUnit == null || targetUnit == null) {
            return null;
        }

        Double factor = BY_UNIT.get(sourceUnit + "-" + targetUnit);
        if (factor != null) {
            return factor;
        }

        return BY_NORMALIZED_UNIT.get(normalizeUnit(sourceUnit) + "-" + normalizeUnit(targetUnit));
    }

    // Backport of IHTSDO/snomed-fhir-cds-service 72177a3f normalisation, plus NMHS aliases.
    private static String normalizeUnit(String unit) {
        String normalized = unit.trim().toLowerCase();
        normalized = normalized.replaceAll("\\(.*?\\)", " ");
        normalized = normalized.replaceAll("[^a-z0-9µ]+", " ");
        normalized = normalized.trim().replaceAll("\\s+", " ");
        return ALIASES.getOrDefault(normalized, normalized);
    }

    //getter
    public String getInputUnit() {
        return inputUnit;
    }

    public String getTargetUnit() {
        return targetUnit;
    }

    public Double getFactor() {
        return factor;
    }
}

