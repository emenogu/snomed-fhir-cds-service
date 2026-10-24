package org.snomed.cdsservice.service.medication.dose;

import ca.uhn.fhir.parser.IParser;
import jakarta.annotation.PostConstruct;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Dosage;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
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
 * NMHS CDSS-HDA-003/004: dose-limit check keyed by the OpenMRS drug UUID, route and patient age band.
 * Bases: FIXED (absolute), PER_KG (per kg body weight with optional absolute caps) and FLUID_HS (IV fluid volume
 * against Holliday-Segar maintenance; bolus per kg). Optional maximum administrations per day. Limits come from a
 * pharmacy-maintained TSV (Standard Config). Card text names the reference (e.g. "BNF for Children").
 * Covered requests skip the WHO-DDD check, so each drug produces at most one dose card.
 */
@Service
public class NmhsDoseLimitService {

    public static final String NMHS_DRUG_SYSTEM = "https://nmhs.local/fhir/CodeSystem/openmrs-drug";
    public static final String OPENMRS_SYSTEM = "https://fhir.openmrs.org";
    public static final String EXPECTED_HEADER = String.join("\t", "drug_uuid", "drug_name", "route", "population",
            "min_age_days", "max_age_days", "basis", "limit_unit", "max_single_dose", "max_daily_dose", "max_single_cap",
            "max_daily_cap", "max_admin_per_day", "strength_value", "strength_unit", "per_value", "per_unit",
            "source_label", "source", "status", "notes");
    static final int ADULT_AGE_DAYS = 18 * 365;
    private static final String HIGH_DOSAGE_ALERT_TYPE = "High Dosage";
    private static final MathContext MC = MathContext.DECIMAL64;

    public enum Basis { FIXED, PER_KG, FLUID_HS }

    private static final Map<String, String[]> UNITS = new HashMap<>();
    static {
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
    @Value("${nmhs.dose-limits.weight-max-age-days.child:30}")
    private int childWeightMaxAgeDays = 30;
    @Value("${nmhs.dose-limits.weight-max-age-days.adult:180}")
    private int adultWeightMaxAgeDays = 180;

    private final Map<String, List<Limit>> limitsByDrug = new HashMap<>();

    public record Limit(String drugUuid, String drugName, String route, String population, Integer minAgeDays,
                        Integer maxAgeDays, Basis basis, String limitUnit, BigDecimal maxSingle, BigDecimal maxDaily,
                        BigDecimal maxSingleCap, BigDecimal maxDailyCap, BigDecimal maxAdminPerDay,
                        BigDecimal strengthValue, String strengthUnit, BigDecimal perValue, String perUnit,
                        String sourceLabel, String source, String status) {
        boolean draft() { return !"approved".equalsIgnoreCase(status); }
        boolean covers(Integer ageDays) {
            int age = ageDays == null ? ADULT_AGE_DAYS : ageDays;   // unknown age: adult rows
            return (minAgeDays == null || age >= minAgeDays) && (maxAgeDays == null || age < maxAgeDays);
        }
        long bandWidth() {
            return (long) (maxAgeDays == null ? 100_000 : maxAgeDays) - (minAgeDays == null ? 0 : minAgeDays);
        }
    }

    /** Age in days (null if unknown) and a recent body weight in kg (null if none or too old). */
    public record PatientContext(Integer ageDays, BigDecimal weightKg) {
        public static final PatientContext UNKNOWN = new PatientContext(null, null);
        boolean child() { return ageDays != null && ageDays < ADULT_AGE_DAYS; }
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
                    if (c.length < 20) throw new IllegalArgumentException("expected 21 columns, got " + c.length);
                    Basis basis = c[6].isBlank() ? Basis.FIXED : Basis.valueOf(c[6].trim().toUpperCase(Locale.ROOT));
                    Limit limit = new Limit(c[0].trim(), c[1].trim(), c[2].isBlank() ? "ANY" : c[2].trim(), c[3].trim(),
                            integer(c[4]), integer(c[5]), basis, c[7].trim(), num(c[8]), num(c[9]), num(c[10]), num(c[11]),
                            num(c[12]), num(c[13]), c[14].trim(), num(c[15]), c[16].trim(),
                            c[17].isBlank() ? "NMHS" : c[17].trim(), c[18].trim(), c[19].trim());
                    boolean anyLimit = limit.maxSingle() != null || limit.maxDaily() != null || limit.maxSingleCap() != null
                            || limit.maxDailyCap() != null || limit.maxAdminPerDay() != null;
                    if (limit.drugUuid().isEmpty() || limit.limitUnit().isEmpty() || !anyLimit) { skipped++; continue; }
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

    /** Builds the patient context from the CDS Hooks prefetch (patient and optional bodyWeight Observation). */
    public PatientContext contextFrom(String patientJson, String bodyWeightJson, IParser parser, LocalDate today) {
        Integer ageDays = null;
        try {
            if (patientJson != null && !"null".equals(patientJson)) {
                Patient patient = parser.parseResource(Patient.class, patientJson);
                if (patient.hasBirthDate()) {
                    LocalDate birth = patient.getBirthDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                    ageDays = (int) ChronoUnit.DAYS.between(birth, today);
                }
            }
        } catch (RuntimeException e) {
            logger.warn("NMHS dose limits: could not read patient birth date: {}", e.toString());
        }
        BigDecimal weight = null;
        try {
            if (bodyWeightJson != null && !"null".equals(bodyWeightJson) && !bodyWeightJson.isBlank()) {
                Observation obs = parser.parseResource(Observation.class, bodyWeightJson);
                if (obs.hasValueQuantity() && obs.getValueQuantity().hasValue()) {
                    Quantity q = obs.getValueQuantity();
                    BigDecimal kg = convert(q.getValue(), norm(q.getCode() != null ? q.getCode() : q.getUnit()), "kg");
                    Date when = obs.hasEffectiveDateTimeType() ? obs.getEffectiveDateTimeType().getValue() : null;
                    if (kg != null && kg.signum() > 0 && when != null) {
                        long ageOfWeight = ChronoUnit.DAYS.between(when.toInstant().atZone(ZoneId.systemDefault()).toLocalDate(), today);
                        int maxAge = (ageDays != null && ageDays < ADULT_AGE_DAYS) ? childWeightMaxAgeDays : adultWeightMaxAgeDays;
                        if (ageOfWeight <= maxAge) weight = kg;
                    }
                }
            }
        } catch (RuntimeException e) {
            logger.warn("NMHS dose limits: could not read body weight: {}", e.toString());
        }
        return new PatientContext(ageDays, weight);
    }

    public Result check(List<MedicationRequest> medicationRequests) {
        return check(medicationRequests, PatientContext.UNKNOWN);
    }

    public Result check(List<MedicationRequest> medicationRequests, PatientContext patient) {
        List<CDSCard> cards = new ArrayList<>();
        Set<MedicationRequest> covered = Collections.newSetFromMap(new IdentityHashMap<>());
        if (limitsByDrug.isEmpty()) return new Result(cards, covered);
        PatientContext ctx = patient == null ? PatientContext.UNKNOWN : patient;
        for (MedicationRequest request : medicationRequests) {
            try {
                CDSCard card = checkOne(request, ctx, covered);
                if (card != null) cards.add(card);
            } catch (RuntimeException e) {
                logger.warn("NMHS dose limits: could not evaluate a medication request: {}", e.toString());
            }
        }
        return new Result(cards, covered);
    }

    private CDSCard checkOne(MedicationRequest request, PatientContext ctx, Set<MedicationRequest> covered) {
        if (!request.hasMedicationCodeableConcept()) return null;
        List<Coding> codings = request.getMedicationCodeableConcept().getCoding();
        // Only draft orders from the NMHS frontend carry NMHS_DRUG_SYSTEM; existing active orders keep the old behaviour.
        String drugUuid = codings.stream().filter(c -> NMHS_DRUG_SYSTEM.equals(c.getSystem()))
                .map(Coding::getCode).filter(limitsByDrug::containsKey).findFirst().orElse(null);
        if (drugUuid == null || request.getDosageInstruction().isEmpty()) return null;
        Dosage dosage = request.getDosageInstruction().get(0);
        if (dosage.getDoseAndRate().isEmpty() || !dosage.getDoseAndRate().get(0).hasDoseQuantity()) return null;
        Quantity quantity = dosage.getDoseAndRate().get(0).getDoseQuantity();
        if (quantity.getValue() == null) return null;
        String route = dosage.hasRoute() ? dosage.getRoute().getText() : null;
        Limit limit = selectLimit(limitsByDrug.get(drugUuid), route, ctx.ageDays());
        if (limit == null) return null;

        BigDecimal perDay = administrationsPerDay(dosage);
        Evaluation e = evaluate(limit, quantity, perDay, ctx);
        if (e == null) {
            logger.info("NMHS dose limits: {} dose unit '{}' cannot be compared with limit unit '{}'; not checked.",
                    limit.drugName(), quantity.getUnit(), limit.limitUnit());
            return null;
        }
        covered.add(request);
        if (e.summary() == null) return null;
        List<CDSCoding> refCodings = codings.stream().map(c -> new CDSCoding(c.getSystem(), c.getCode(), c.getDisplay())).collect(Collectors.toList());
        return new CDSCard(UUID.randomUUID().toString(), e.summary(), e.detail(), e.indicator(),
                new CDSSource(limit.sourceLabel()), Collections.singletonList(new CDSReference(refCodings)), null, HIGH_DOSAGE_ALERT_TYPE);
    }

    record Evaluation(String summary, String detail, CDSIndicator indicator) {}

    /** Null when the dose cannot be compared (unit); an Evaluation with null summary when the dose is within limits. */
    Evaluation evaluate(Limit limit, Quantity quantity, BigDecimal perDay, PatientContext ctx) {
        // frequency-only rows (e.g. eye drops "max 3 times/day") do not need the dose unit to be comparable
        boolean frequencyOnly = limit.maxSingle() == null && limit.maxDaily() == null && limit.maxSingleCap() == null
                && limit.maxDailyCap() == null && limit.maxAdminPerDay() != null;
        String u = frequencyOnly && quantity.getUnit() != null ? quantity.getUnit() : limit.limitUnit();
        BigDecimal single = frequencyOnly ? quantity.getValue() : toLimitUnit(quantity.getValue(), quantity.getUnit(), limit);
        if (single == null) return null;
        BigDecimal daily = perDay == null ? single : single.multiply(perDay, MC);
        String name = limit.drugName().length() > 40 ? limit.drugName().substring(0, 40) : limit.drugName();
        String src = limit.sourceLabel();
        String draft = limit.draft() ? " (draft limit)" : "";
        BigDecimal w = ctx.weightKg();
        StringBuilder detail = new StringBuilder("\n");
        detail.append(format("* Prescribed: %s %s per dose, %s %s per day%n", fmt(single), u, fmt(daily), u));
        if (w != null) detail.append(format("* Weight used: %s kg%n", fmt(w)));
        detail.append(format("* Limit: %s, %s, route %s, population %s%n", src, limit.basis(), limit.route(), limit.population()));
        if (!limit.source().isBlank()) detail.append("* Reference: ").append(limit.source()).append('\n');

        String summary = null;
        CDSIndicator indicator = CDSIndicator.warning;
        // 1. frequency
        if (limit.maxAdminPerDay() != null && perDay != null && exceeds(perDay, limit.maxAdminPerDay())) {
            summary = format("%s: %s times/day exceeds the %s maximum of %s times/day%s.", name, fmt(perDay), src, fmt(limit.maxAdminPerDay()), draft);
        }
        // 2. dose
        if (summary == null) {
            switch (limit.basis()) {
                case FIXED -> summary = fixed(name, src, draft, u, single, daily, limit.maxSingle(), limit.maxDaily());
                case PER_KG -> {
                    if (w != null) summary = perKg(name, src, draft, u, single, daily, w, limit);
                    else summary = fixed(name, src, draft, u, single, daily, limit.maxSingleCap(), limit.maxDailyCap());
                }
                case FLUID_HS -> {
                    if (w != null) summary = fluid(name, src, draft, u, single, daily, w, limit);
                    else summary = fixed(name, src, draft, u, single, daily, limit.maxSingleCap(), limit.maxDailyCap());
                }
            }
        }
        // 3. optional near-maximum info (fixed daily limits only)
        if (summary == null && limit.basis() == Basis.FIXED && infoThreshold > 0 && infoThreshold < 1 && limit.maxDaily() != null
                && limit.maxDaily().signum() > 0 && daily.divide(limit.maxDaily(), MC).doubleValue() >= infoThreshold) {
            BigDecimal pct = daily.multiply(BigDecimal.valueOf(100)).divide(limit.maxDaily(), 0, RoundingMode.HALF_UP);
            summary = format("%s: %s %s/day is %s%% of the %s maximum daily dose (%s %s)%s.", name, fmt(daily), u, pct.toPlainString(), src, fmt(limit.maxDaily()), u, draft);
            indicator = CDSIndicator.info;
        }
        // 4. child without a recent weight: prompt (the weight-based limit could not be applied)
        if (summary == null && w == null && ctx.child() && limit.basis() != Basis.FIXED) {
            String basisText = limit.basis() == Basis.FLUID_HS ? "maintenance fluids" :
                    (limit.maxDaily() != null ? fmt(limit.maxDaily()) + " " + u + "/kg/day" : fmt(limit.maxSingle()) + " " + u + "/kg per dose");
            summary = format("%s: record the child's weight to check this dose (%s limit %s)%s.", name, src, basisText, draft);
            indicator = CDSIndicator.info;
        }
        return new Evaluation(summary, detail.toString(), indicator);
    }

    private static String fixed(String name, String src, String draft, String u, BigDecimal single, BigDecimal daily, BigDecimal maxSingle, BigDecimal maxDaily) {
        if (maxDaily != null && exceeds(daily, maxDaily))
            return format("%s: %s %s/day exceeds the %s maximum daily dose of %s %s%s.", name, fmt(daily), u, src, fmt(maxDaily), u, draft);
        if (maxSingle != null && exceeds(single, maxSingle))
            return format("%s: single dose %s %s exceeds the %s maximum single dose of %s %s%s.", name, fmt(single), u, src, fmt(maxSingle), u, draft);
        return null;
    }

    private static String perKg(String name, String src, String draft, String u, BigDecimal single, BigDecimal daily, BigDecimal w, Limit l) {
        if (l.maxDaily() != null) {
            BigDecimal byWeight = l.maxDaily().multiply(w, MC);
            boolean capBinds = l.maxDailyCap() != null && l.maxDailyCap().compareTo(byWeight) < 0;
            if (capBinds && exceeds(daily, l.maxDailyCap()))
                return format("%s: %s %s/day exceeds the %s maximum daily dose of %s %s%s.", name, fmt(daily), u, src, fmt(l.maxDailyCap()), u, draft);
            if (!capBinds && exceeds(daily, byWeight))
                return format("%s: %s %s/day exceeds the %s maximum of %s %s/kg/day (%s %s at %s kg)%s.", name, fmt(daily), u, src, fmt(l.maxDaily()), u, fmt(byWeight), u, fmt(w), draft);
        } else if (l.maxDailyCap() != null && exceeds(daily, l.maxDailyCap())) {
            return format("%s: %s %s/day exceeds the %s maximum daily dose of %s %s%s.", name, fmt(daily), u, src, fmt(l.maxDailyCap()), u, draft);
        }
        if (l.maxSingle() != null) {
            BigDecimal byWeight = l.maxSingle().multiply(w, MC);
            boolean capBinds = l.maxSingleCap() != null && l.maxSingleCap().compareTo(byWeight) < 0;
            if (capBinds && exceeds(single, l.maxSingleCap()))
                return format("%s: single dose %s %s exceeds the %s maximum single dose of %s %s%s.", name, fmt(single), u, src, fmt(l.maxSingleCap()), u, draft);
            if (!capBinds && exceeds(single, byWeight))
                return format("%s: single dose %s %s exceeds the %s maximum of %s %s/kg (%s %s at %s kg)%s.", name, fmt(single), u, src, fmt(l.maxSingle()), u, fmt(byWeight), u, fmt(w), draft);
        } else if (l.maxSingleCap() != null && exceeds(single, l.maxSingleCap())) {
            return format("%s: single dose %s %s exceeds the %s maximum single dose of %s %s%s.", name, fmt(single), u, src, fmt(l.maxSingleCap()), u, draft);
        }
        return null;
    }

    /** Holliday-Segar maintenance: 100 mL/kg for the first 10 kg, 50 mL/kg for the next 10 kg, 20 mL/kg above 20 kg. */
    static BigDecimal hollidaySegar(BigDecimal w) {
        BigDecimal first = w.min(BigDecimal.TEN);
        BigDecimal second = w.subtract(BigDecimal.TEN).max(BigDecimal.ZERO).min(BigDecimal.TEN);
        BigDecimal rest = w.subtract(BigDecimal.valueOf(20)).max(BigDecimal.ZERO);
        return first.multiply(BigDecimal.valueOf(100)).add(second.multiply(BigDecimal.valueOf(50))).add(rest.multiply(BigDecimal.valueOf(20)));
    }

    private static String fluid(String name, String src, String draft, String u, BigDecimal single, BigDecimal daily, BigDecimal w, Limit l) {
        if (l.maxDaily() != null) {
            BigDecimal limit = hollidaySegar(w).multiply(l.maxDaily(), MC);
            if (l.maxDailyCap() != null) limit = limit.min(l.maxDailyCap());
            if (exceeds(daily, limit)) {
                String pct = l.maxDaily().multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();
                return format("%s: %s %s/day exceeds %s%% of maintenance fluids (%s %s/day at %s kg; %s)%s.", name, fmt(daily), u, pct, fmt(limit), u, fmt(w), src, draft);
            }
        }
        if (l.maxSingle() != null) {
            BigDecimal limit = l.maxSingle().multiply(w, MC);
            if (l.maxSingleCap() != null) limit = limit.min(l.maxSingleCap());
            if (exceeds(single, limit))
                return format("%s: bolus %s %s exceeds the %s maximum of %s %s/kg (%s %s at %s kg)%s.", name, fmt(single), u, src, fmt(l.maxSingle()), u, fmt(limit), u, fmt(w), draft);
        }
        return null;
    }

    /** Route-specific rows first, then ANY; among those covering the patient's age, the narrowest age band wins. */
    static Limit selectLimit(List<Limit> limits, String route, Integer ageDays) {
        if (limits == null) return null;
        Comparator<Limit> narrowest = Comparator.comparingLong(Limit::bandWidth);
        if (route != null) {
            Limit routed = limits.stream().filter(l -> l.route().equalsIgnoreCase(route.trim()) && l.covers(ageDays)).min(narrowest).orElse(null);
            if (routed != null) return routed;
        }
        return limits.stream().filter(l -> "ANY".equalsIgnoreCase(l.route()) && l.covers(ageDays)).min(narrowest).orElse(null);
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
        if (u.endsWith("s") && u.length() > 3) u = u.substring(0, u.length() - 1);
        return switch (u) {
            case "actuation", "puff" -> "puff";
            case "drop", "gtt" -> "drop";
            case "spoonful" -> "teaspoon";
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

    private static Integer integer(String s) {
        if (s == null || s.isBlank()) return null;
        return Integer.valueOf(s.trim());
    }

    /** Display value: one decimal place from 10 upwards (so 52.5 ml/kg or 22.5 mg is never shown rounded), else 3 significant digits. */
    static String fmt(BigDecimal v) {
        BigDecimal r = v.abs().compareTo(BigDecimal.TEN) >= 0 ? v.setScale(1, RoundingMode.HALF_UP) : v.round(new MathContext(3));
        return r.stripTrailingZeros().toPlainString();
    }

    public int activeDrugCount() {
        return limitsByDrug.size();
    }

    void setWeightMaxAgeDays(int child, int adult) {
        this.childWeightMaxAgeDays = child;
        this.adultWeightMaxAgeDays = adult;
    }
}
