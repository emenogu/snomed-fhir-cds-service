package org.snomed.cdsservice.service.medication.dose;

import jakarta.annotation.PostConstruct;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Dosage;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Timing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.cdsservice.model.CDSCard;
import org.snomed.cdsservice.model.CDSCoding;
import org.snomed.cdsservice.model.CDSIndicator;
import org.snomed.cdsservice.model.CDSReference;
import org.snomed.cdsservice.model.CDSSource;
import org.snomed.cdsservice.service.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.lang.String.format;

/**
 * NMHS CDSS-HDA-003: dose-limit check keyed by the OpenMRS drug UUID and route, independent of SNOMED strength.
 * Limits come from a pharmacy-maintained TSV (Standard Config). When a drug has a usable limit row the request is
 * "covered" and the caller skips the WHO-DDD check for it, so each drug produces at most one dose card.
 */
@Service
public class NmhsDoseLimitService {

    public static final String NMHS_DRUG_SYSTEM = "https://nmhs.local/fhir/CodeSystem/openmrs-drug";
    public static final String OPENMRS_SYSTEM = "https://fhir.openmrs.org";
    public static final String EXPECTED_HEADER = "drug_uuid\tdrug_name\troute\tlimit_unit\tmax_single_dose\tmax_daily_dose\t"
            + "strength_value\tstrength_unit\tper_value\tper_unit\tpopulation\tstatus\tsource\tnotes";
    private static final String HIGH_DOSAGE_ALERT_TYPE = "High Dosage";
    private static final MathContext MC = MathContext.DECIMAL64;

    private static final Map<String, String[]> UNITS = new HashMap<>();
    static {
        // unit -> {family, factor to the family base (mg | ml | iu)}
        for (String u : new String[]{"mg", "milligram", "milligrams"}) UNITS.put(u, new String[]{"mass", "1"});
        for (String u : new String[]{"g", "gram", "grams", "gm"}) UNITS.put(u, new String[]{"mass", "1000"});
        for (String u : new String[]{"mcg", "ug", "µg", "microgram", "micrograms"}) UNITS.put(u, new String[]{"mass", "0.001"});
        for (String u : new String[]{"ng", "nanogram", "nanograms"}) UNITS.put(u, new String[]{"mass", "0.000001"});
        UNITS.put("kg", new String[]{"mass", "1000000"});
        for (String u : new String[]{"ml", "milliliter", "millilitre", "milliliters", "millilitres"}) UNITS.put(u, new String[]{"volume", "1"});
        for (String u : new String[]{"l", "liter", "litre", "liters", "litres"}) UNITS.put(u, new String[]{"volume", "1000"});
        for (String u : new String[]{"ul", "microliter", "microlitre"}) UNITS.put(u, new String[]{"volume", "0.001"});
        for (String u : new String[]{"teaspoon", "teaspoons", "tsp"}) UNITS.put(u, new String[]{"volume", "5"});
        for (String u : new String[]{"tablespoon", "tablespoons", "tbsp"}) UNITS.put(u, new String[]{"volume", "15"});
        for (String u : new String[]{"iu", "international unit", "international units", "[iu]"}) UNITS.put(u, new String[]{"iu", "1"});
        for (String u : new String[]{"miu", "million units", "million iu"}) UNITS.put(u, new String[]{"iu", "1000000"});
    }

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Value("${rules.nmhs-dose-limits.tsv:}")
    private String tsvPath;
    @Value("${nmhs.dose-limits.include-drafts:false}")
    private boolean includeDrafts;
    @Value("${nmhs.dose-limits.info-threshold:0}")
    private double infoThreshold;

    private final Map<String, List<Limit>> limitsByDrug = new HashMap<>();

    public record Limit(String drugUuid, String drugName, String route, String limitUnit, BigDecimal maxSingle,
                        BigDecimal maxDaily, BigDecimal strengthValue, String strengthUnit, BigDecimal perValue,
                        String perUnit, String status, String source) {
        boolean draft() { return !"approved".equalsIgnoreCase(status); }
    }

    public record Result(List<CDSCard> cards, Set<MedicationRequest> covered) {}

    @PostConstruct
    public void init() throws ServiceException {
        load(tsvPath, includeDrafts, infoThreshold);
    }

    public void load(String path, boolean drafts, double threshold) throws ServiceException {
        this.includeDrafts = drafts;
        this.infoThreshold = threshold;
        limitsByDrug.clear();
        if (path == null || path.isBlank()) {
            logger.info("NMHS dose limits: no TSV configured (rules.nmhs-dose-limits.tsv); rule disabled.");
            return;
        }
        int rows = 0, active = 0, skipped = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String header = reader.readLine();
            if (header != null && header.startsWith("﻿")) header = header.substring(1);
            if (!EXPECTED_HEADER.equals(header)) {
                throw new ServiceException(format("NMHS dose-limit file does not have the expected header. Expected: '%s', Actual: '%s'", EXPECTED_HEADER, header));
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                rows++;
                String[] c = line.split("\t", -1);
                try {
                    if (c.length < 12) throw new IllegalArgumentException("expected 14 columns, got " + c.length);
                    BigDecimal maxSingle = num(c[4]), maxDaily = num(c[5]);
                    if (c[0].isBlank() || c[3].isBlank() || (maxSingle == null && maxDaily == null)) { skipped++; continue; }
                    if (!c[10].isBlank() && !"adult".equalsIgnoreCase(c[10].trim())) { skipped++; continue; }
                    Limit limit = new Limit(c[0].trim(), c[1].trim(), c[2].isBlank() ? "ANY" : c[2].trim(), c[3].trim(),
                            maxSingle, maxDaily, num(c[6]), c[7].trim(), num(c[8]), c[9].trim(), c[11].trim(),
                            c.length > 12 ? c[12].trim() : "");
                    if (limit.draft() && !drafts) { skipped++; continue; }
                    limitsByDrug.computeIfAbsent(limit.drugUuid(), k -> new ArrayList<>()).add(limit);
                    active++;
                } catch (RuntimeException e) {
                    skipped++;
                    logger.error("NMHS dose limits: skipping row {} ({}): {}", rows, e.getMessage(), line);
                }
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("Failed to read NMHS dose-limit file " + path, e);
        }
        logger.info("NMHS dose limits loaded: {} rows, {} active for {} drugs, {} skipped (includeDrafts={}).",
                rows, active, limitsByDrug.size(), skipped, drafts);
    }

    public Result check(List<MedicationRequest> medicationRequests) {
        List<CDSCard> cards = new ArrayList<>();
        Set<MedicationRequest> covered = Collections.newSetFromMap(new IdentityHashMap<>());
        if (limitsByDrug.isEmpty()) return new Result(cards, covered);
        for (MedicationRequest request : medicationRequests) {
            try {
                CDSCard card = checkOne(request, covered);
                if (card != null) cards.add(card);
            } catch (RuntimeException e) {
                logger.warn("NMHS dose limits: could not evaluate a medication request: {}", e.toString());
            }
        }
        return new Result(cards, covered);
    }

    private CDSCard checkOne(MedicationRequest request, Set<MedicationRequest> covered) {
        if (!request.hasMedicationCodeableConcept()) return null;
        List<Coding> codings = request.getMedicationCodeableConcept().getCoding();
        // Only draft orders from the NMHS frontend carry NMHS_DRUG_SYSTEM; existing active orders (fhir-cdss-int adds
        // OPENMRS_SYSTEM + drug uuid) keep the previous behaviour so running orders do not raise repeat cards.
        String drugUuid = codings.stream()
                .filter(c -> NMHS_DRUG_SYSTEM.equals(c.getSystem()))
                .map(Coding::getCode).filter(limitsByDrug::containsKey).findFirst().orElse(null);
        if (drugUuid == null || request.getDosageInstruction().isEmpty()) return null;
        Dosage dosage = request.getDosageInstruction().get(0);
        if (dosage.getDoseAndRate().isEmpty() || !dosage.getDoseAndRate().get(0).hasDoseQuantity()) return null;
        Quantity quantity = dosage.getDoseAndRate().get(0).getDoseQuantity();
        if (quantity.getValue() == null) return null;
        String route = dosage.hasRoute() ? dosage.getRoute().getText() : null;
        Limit limit = selectLimit(limitsByDrug.get(drugUuid), route);
        if (limit == null) return null;

        BigDecimal single = toLimitUnit(quantity.getValue(), quantity.getUnit(), limit);
        if (single == null) {
            logger.info("NMHS dose limits: {} dose unit '{}' cannot be compared with limit unit '{}'; not checked.",
                    limit.drugName(), quantity.getUnit(), limit.limitUnit());
            return null;
        }
        covered.add(request);
        BigDecimal perDay = administrationsPerDay(dosage);
        BigDecimal daily = perDay == null ? single : single.multiply(perDay, MC);

        boolean singleOver = limit.maxSingle() != null && exceeds(single, limit.maxSingle());
        boolean dailyOver = limit.maxDaily() != null && exceeds(daily, limit.maxDaily());
        // info near the maximum is optional (threshold in (0,1)); disabled by default because standard once-daily
        // regimens often sit exactly at the maximum and would raise an info card on every prescription.
        boolean near = infoThreshold > 0 && infoThreshold < 1
                && !dailyOver && limit.maxDaily() != null && limit.maxDaily().signum() > 0
                && daily.divide(limit.maxDaily(), MC).doubleValue() >= infoThreshold;
        if (!singleOver && !dailyOver && !near) return null;

        String u = limit.limitUnit();
        String draft = limit.draft() ? " (draft limit)" : "";
        String name = limit.drugName().length() > 45 ? limit.drugName().substring(0, 45) : limit.drugName();
        String summary;
        if (dailyOver) {
            summary = format("%s: %s %s/day exceeds the NMHS maximum daily dose of %s %s%s.", name, fmt(daily), u, fmt(limit.maxDaily()), u, draft);
        } else if (singleOver) {
            summary = format("%s: single dose %s %s exceeds the NMHS maximum single dose of %s %s%s.", name, fmt(single), u, fmt(limit.maxSingle()), u, draft);
        } else {
            BigDecimal pct = daily.multiply(BigDecimal.valueOf(100)).divide(limit.maxDaily(), 0, RoundingMode.HALF_UP);
            summary = format("%s: %s %s/day is %s%% of the NMHS maximum daily dose (%s %s)%s.", name, fmt(daily), u, pct.toPlainString(), fmt(limit.maxDaily()), u, draft);
        }
        StringBuilder detail = new StringBuilder("\n");
        detail.append(format("* Prescribed: %s %s per dose, %s %s per day%n", fmt(single), u, fmt(daily), u));
        if (limit.maxSingle() != null) detail.append(format("* NMHS maximum single dose: %s %s%s%n", fmt(limit.maxSingle()), u, singleOver ? " (exceeded)" : ""));
        if (limit.maxDaily() != null) detail.append(format("* NMHS maximum daily dose: %s %s%s%n", fmt(limit.maxDaily()), u, dailyOver ? " (exceeded)" : ""));
        detail.append(format("* Route: %s; limit status: %s%n", limit.route(), limit.status()));
        if (!limit.source().isBlank()) detail.append("* Source: ").append(limit.source()).append('\n');
        CDSIndicator indicator = (singleOver || dailyOver) ? CDSIndicator.warning : CDSIndicator.info;
        List<CDSCoding> refCodings = codings.stream().map(c -> new CDSCoding(c.getSystem(), c.getCode(), c.getDisplay())).collect(Collectors.toList());
        return new CDSCard(UUID.randomUUID().toString(), summary, detail.toString(), indicator,
                new CDSSource("NMHS dose limits"), Collections.singletonList(new CDSReference(refCodings)), null, HIGH_DOSAGE_ALERT_TYPE);
    }

    static Limit selectLimit(List<Limit> limits, String route) {
        if (limits == null) return null;
        if (route != null) {
            for (Limit l : limits) if (l.route().equalsIgnoreCase(route.trim())) return l;
        }
        for (Limit l : limits) if ("ANY".equalsIgnoreCase(l.route())) return l;
        return null;
    }

    /** Converts the prescribed quantity into the limit unit, directly or through the drug strength. Null if not comparable. */
    static BigDecimal toLimitUnit(BigDecimal value, String unit, Limit limit) {
        String from = norm(unit), target = norm(limit.limitUnit());
        if (from.isEmpty() || "na".equals(from)) return null;
        BigDecimal direct = convert(value, from, target);
        if (direct != null) return direct;
        if (limit.strengthValue() == null || limit.perUnit().isBlank()) return null;
        String per = norm(limit.perUnit());
        BigDecimal perValue = limit.perValue() == null ? BigDecimal.ONE : limit.perValue();
        BigDecimal unitsOfPer = from.equals(per) ? value : convert(value, from, per);
        if (unitsOfPer == null) return null;
        BigDecimal amount = unitsOfPer.divide(perValue, MC).multiply(limit.strengthValue(), MC);
        return convert(amount, norm(limit.strengthUnit()), target);
    }

    static BigDecimal convert(BigDecimal value, String from, String to) {
        if (from.equals(to)) return value;
        String[] f = UNITS.get(from), t = UNITS.get(to);
        if (f == null || t == null || !f[0].equals(t[0])) return null;
        return value.multiply(new BigDecimal(f[1]), MC).divide(new BigDecimal(t[1]), MC);
    }

    static String norm(String unit) {
        if (unit == null) return "";
        String u = unit.trim().toLowerCase(Locale.ROOT).replace("(s)", "");
        if (UNITS.containsKey(u)) return u;
        if (u.endsWith("s") && u.length() > 3 && !UNITS.containsKey(u)) u = u.substring(0, u.length() - 1);
        return switch (u) {
            case "actuation", "puff" -> "puff";
            case "drop", "gtt" -> "drop";
            default -> u;
        };
    }

    /** Administrations per day from timing.repeat; null when no repeat is given (treated as a single administration). */
    static BigDecimal administrationsPerDay(Dosage dosage) {
        if (!dosage.hasTiming() || !dosage.getTiming().hasRepeat()) return null;
        Timing.TimingRepeatComponent repeat = dosage.getTiming().getRepeat();
        if (!repeat.hasFrequency() || !repeat.hasPeriod() || !repeat.hasPeriodUnit()) return null;
        BigDecimal period = repeat.getPeriod();
        if (period.signum() <= 0) return null;
        BigDecimal f = BigDecimal.valueOf(repeat.getFrequency());
        // per day = frequency x (periods per day) / period; exact factors avoid rounding at the limit boundary
        return switch (repeat.getPeriodUnit()) {
            case S -> f.multiply(BigDecimal.valueOf(86400)).divide(period, MC);
            case MIN -> f.multiply(BigDecimal.valueOf(1440)).divide(period, MC);
            case H -> f.multiply(BigDecimal.valueOf(24)).divide(period, MC);
            case D -> f.divide(period, MC);
            case WK -> f.divide(period.multiply(BigDecimal.valueOf(7)), MC);
            case MO -> f.divide(period.multiply(BigDecimal.valueOf(30)), MC);
            case A -> f.divide(period.multiply(BigDecimal.valueOf(365)), MC);
            default -> null;
        };
    }

    /** value > max, with a relative tolerance of 1e-9 so unit conversions cannot tip an exact maximum over. */
    static boolean exceeds(BigDecimal value, BigDecimal max) {
        return value.compareTo(max.multiply(new BigDecimal("1.000000001"), MC)) > 0;
    }

    private static BigDecimal num(String s) {
        if (s == null || s.isBlank()) return null;
        return new BigDecimal(s.trim());
    }

    private static String fmt(BigDecimal v) {
        BigDecimal r = v.abs().compareTo(BigDecimal.TEN) >= 0 ? v.setScale(0, RoundingMode.HALF_UP) : v.round(new MathContext(3));
        return r.stripTrailingZeros().toPlainString();
    }

    public int activeDrugCount() {
        return limitsByDrug.size();
    }
}
