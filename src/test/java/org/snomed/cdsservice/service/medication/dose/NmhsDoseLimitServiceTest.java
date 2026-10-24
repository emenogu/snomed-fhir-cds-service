package org.snomed.cdsservice.service.medication.dose;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Dosage;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Timing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snomed.cdsservice.model.CDSCard;
import org.snomed.cdsservice.model.CDSIndicator;
import org.snomed.cdsservice.service.ServiceException;
import org.snomed.cdsservice.service.medication.dose.NmhsDoseLimitService.PatientContext;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NmhsDoseLimitServiceTest {

    static final String METHYLDOPA = "11111111-1111-1111-1111-111111111111";
    static final String METRO_SUSP = "22222222-2222-2222-2222-222222222222";
    static final String METHOTREXATE = "33333333-3333-3333-3333-333333333333";
    static final String DRAFT_DRUG = "44444444-4444-4444-4444-444444444444";
    static final String ROUTED = "55555555-5555-5555-5555-555555555555";
    static final String PARA_SYRUP = "66666666-6666-6666-6666-666666666666";
    static final String GENTA = "77777777-7777-7777-7777-777777777777";
    static final String SALINE = "88888888-8888-8888-8888-888888888888";
    static final String ATROPINE_DROPS = "99999999-9999-9999-9999-999999999999";
    static final int CHILD = 5 * 365, NEONATE = 10, ADULT = 30 * 365;

    @TempDir
    Path dir;
    NmhsDoseLimitService service;
    Path tsv;

    static String row(String uuid, String name, String route, String pop, String minAge, String maxAge, String basis, String unit,
                      String single, String daily, String singleCap, String dailyCap, String adminPerDay,
                      String sv, String su, String pv, String pu, String label, String status) {
        return String.join("\t", uuid, name, route, pop, minAge, maxAge, basis, unit, single, daily, singleCap, dailyCap,
                adminPerDay, sv, su, pv, pu, label, "test reference", status, "");
    }

    @BeforeEach
    void setUp() throws Exception {
        tsv = dir.resolve("limits.tsv");
        String adult = String.valueOf(NmhsDoseLimitService.ADULT_AGE_DAYS);
        Files.writeString(tsv, String.join("\n",
                NmhsDoseLimitService.EXPECTED_HEADER,
                row(METHYLDOPA, "Methyldopa 250 mg", "ANY", "adult", adult, "", "fixed", "mg", "1000", "3000", "", "", "", "250", "mg", "1", "Tablet", "BNF", "approved"),
                row(METRO_SUSP, "Metronidazole 200 mg/5 ml", "ANY", "adult", adult, "", "fixed", "mg", "", "4000", "", "", "", "200", "mg", "5", "ml", "US product label", "approved"),
                row(METRO_SUSP, "Metronidazole 200 mg/5 ml", "ANY", "child", "28", adult, "per_kg", "mg", "10", "30", "400", "2400", "", "200", "mg", "5", "ml", "BNF for Children", "approved"),
                row(METHOTREXATE, "Methotrexate 2.5 mg", "ANY", "adult", "", "", "fixed", "mg", "25", "4", "", "", "", "2.5", "mg", "1", "Tablet", "BNF", "approved"),
                row(DRAFT_DRUG, "Draft drug 10 mg", "ANY", "all", "", "", "fixed", "mg", "", "40", "", "", "", "10", "mg", "1", "Tablet", "BNF", "draft"),
                row(ROUTED, "Routed drug 1 g", "O", "all", "", "", "fixed", "mg", "", "2000", "", "", "", "", "", "", "", "BNF", "approved"),
                row(ROUTED, "Routed drug 1 g", "P", "all", "", "", "fixed", "mg", "", "6000", "", "", "", "", "", "", "", "BNF", "approved"),
                row(PARA_SYRUP, "Paracetamol 120 mg/5 ml", "ANY", "all", "", "", "per_kg", "mg", "15", "60", "1000", "4000", "", "120", "mg", "5", "ml", "BNF for Children", "approved"),
                row(PARA_SYRUP, "Paracetamol 120 mg/5 ml", "ANY", "neonate", "0", "28", "per_kg", "mg", "15", "30", "", "", "", "120", "mg", "5", "ml", "BNF for Children", "approved"),
                row(GENTA, "Gentamicin 40 mg/ml", "ANY", "all", "", "", "per_kg", "mg", "7", "7", "", "", "", "40", "mg", "1", "ml", "BNF", "approved"),
                row(SALINE, "Sodium chloride 0.9%", "ANY", "child", "28", adult, "fluid_hs", "ml", "20", "1.5", "", "", "", "", "", "", "", "WHO Pocket Book", "approved"),
                row(ATROPINE_DROPS, "Atropine 1% drops", "ANY", "all", "", "", "fixed", "drop", "", "", "", "", "3", "", "", "", "", "BNF", "approved"),
                "# comment line ignored",
                row("bad-row", "Bad", "ANY", "all", "", "", "fixed", "mg", "abc", "1", "", "", "", "", "", "", "", "BNF", "approved")) + "\n");
        service = new NmhsDoseLimitService();
        service.load(tsv.toString(), false, 0);
    }

    static MedicationRequest request(String drugUuid, double dose, String unit, int frequency, double period,
                                     Timing.UnitsOfTime periodUnit, String route) {
        MedicationRequest mr = new MedicationRequest();
        CodeableConcept cc = new CodeableConcept();
        cc.addCoding(new Coding("http://snomed.info/sct", "84078002", "x"));
        cc.addCoding(new Coding(NmhsDoseLimitService.NMHS_DRUG_SYSTEM, drugUuid, "drug"));
        mr.setMedication(cc);
        Dosage d = new Dosage();
        d.addDoseAndRate().setDose(new Quantity().setValue(dose).setUnit(unit));
        if (frequency > 0) {
            d.setTiming(new Timing().setRepeat(new Timing.TimingRepeatComponent().setFrequency(frequency)
                    .setPeriod(BigDecimal.valueOf(period)).setPeriodUnit(periodUnit)));
        }
        if (route != null) d.setRoute(new CodeableConcept().setText(route));
        mr.addDosageInstruction(d);
        return mr;
    }

    static PatientContext ctx(Integer ageDays, Double kg) {
        return new PatientContext(ageDays, kg == null ? null : BigDecimal.valueOf(kg));
    }

    private List<CDSCard> cards(PatientContext c, MedicationRequest... requests) {
        return service.check(List.of(requests), c).cards();
    }

    private List<CDSCard> adult(MedicationRequest... requests) {
        return cards(ctx(ADULT, 70.0), requests);
    }

    @Test
    void loadsApprovedRowsSkipsDraftsAndBadRows() {
        assertEquals(8, service.activeDrugCount());
    }

    @Test
    void rejectsUnexpectedHeader() throws Exception {
        Path bad = dir.resolve("bad.tsv");
        Files.writeString(bad, "wrong\theader\n");
        assertThrows(ServiceException.class, () -> new NmhsDoseLimitService().load(bad.toString(), false, 0));
    }

    @Test
    void disabledWhenNoPath() throws ServiceException {
        NmhsDoseLimitService s = new NmhsDoseLimitService();
        s.load("", false, 0);
        assertTrue(s.check(List.of(request(METHYLDOPA, 10, "Tablet", 4, 1, Timing.UnitsOfTime.D, "O"))).cards().isEmpty());
    }

    @Test
    void fixedLimitCardNamesTheSource() {
        List<CDSCard> c = adult(request(METHYLDOPA, 4, "Tablet", 4, 1, Timing.UnitsOfTime.D, "O"));
        assertEquals(1, c.size());
        assertEquals(CDSIndicator.warning, c.get(0).getIndicator());
        assertEquals("Methyldopa 250 mg: 4000 mg/day exceeds the BNF maximum daily dose of 3000 mg.", c.get(0).getSummary());
        assertEquals("BNF", c.get(0).getSource().getLabel());
        assertEquals("High Dosage", c.get(0).getAlertType());
    }

    @Test
    void normalAdultDoseIsCoveredWithoutCard() {
        MedicationRequest mr = request(METHYLDOPA, 2, "Tablet", 3, 1, Timing.UnitsOfTime.D, "O");
        NmhsDoseLimitService.Result r = service.check(List.of(mr), ctx(ADULT, null));
        assertTrue(r.cards().isEmpty());
        assertTrue(r.covered().contains(mr));
    }

    @Test
    void singleDoseExceededInGrams() {
        List<CDSCard> c = adult(request(METHYLDOPA, 1.5, "g", 1, 1, Timing.UnitsOfTime.D, "O"));
        assertTrue(c.get(0).getSummary().contains("single dose 1500 mg exceeds the BNF maximum single dose of 1000 mg"));
    }

    @Test
    void childPerKgDailyLimitWithWeight() {
        // 15 kg child: 10 ml (400 mg) x4 = 1600 mg/day > 30 mg/kg/day (450 mg)
        List<CDSCard> c = cards(ctx(CHILD, 15.0), request(METRO_SUSP, 10, "ml", 4, 1, Timing.UnitsOfTime.D, "O"));
        assertEquals(1, c.size());
        assertEquals("Metronidazole 200 mg/5 ml: 1600 mg/day exceeds the BNF for Children maximum of 30 mg/kg/day (450 mg at 15 kg).",
                c.get(0).getSummary());
        // within: 2.5 ml (100 mg) x3 = 300 mg/day, 100 mg single <= 150 mg
        assertTrue(cards(ctx(CHILD, 15.0), request(METRO_SUSP, 2.5, "ml", 3, 1, Timing.UnitsOfTime.D, "O")).isEmpty());
    }

    @Test
    void childAbsoluteCapBindsForHeavyChild() {
        // 100 kg adolescent: 30 mg/kg/day = 3000 > cap 2400 -> cap applies; 75 ml (3000 mg) x1 = 3000 > 2400
        List<CDSCard> c = cards(ctx(16 * 365, 100.0), request(METRO_SUSP, 75, "ml", 1, 1, Timing.UnitsOfTime.D, "O"));
        assertTrue(c.get(0).getSummary().contains("exceeds the BNF for Children maximum daily dose of 2400 mg"));
    }

    @Test
    void childWithoutWeightGetsPromptOrCapWarning() {
        List<CDSCard> c = cards(ctx(CHILD, null), request(METRO_SUSP, 2.5, "ml", 3, 1, Timing.UnitsOfTime.D, "O"));
        assertEquals(1, c.size());
        assertEquals(CDSIndicator.info, c.get(0).getIndicator());
        assertTrue(c.get(0).getSummary().startsWith("Metronidazole 200 mg/5 ml: record the child's weight to check this dose"));
        // without weight an absolute cap is still enforced as a warning
        List<CDSCard> cap = cards(ctx(CHILD, null), request(METRO_SUSP, 20, "ml", 4, 1, Timing.UnitsOfTime.D, "O"));
        assertEquals(CDSIndicator.warning, cap.get(0).getIndicator());
    }

    @Test
    void adultPerKgWithoutWeightIsSilentWhenNoCap() {
        MedicationRequest mr = request(GENTA, 2, "ml", 1, 1, Timing.UnitsOfTime.D, "P");
        assertTrue(service.check(List.of(mr), ctx(ADULT, null)).cards().isEmpty());
        // with weight: 80 mg x3 = 240 mg vs 7 mg/kg at 30 kg = 210 mg
        assertEquals(1, cards(ctx(ADULT, 30.0), request(GENTA, 80, "mg", 3, 1, Timing.UnitsOfTime.D, "P")).size());
    }

    @Test
    void neonateBandIsNarrowerAndWins() {
        // 3 kg neonate: 15 mg/kg x4 = 180 mg/day > 30 mg/kg/day (90 mg); the 'all' row would allow 60 mg/kg/day
        List<CDSCard> c = cards(ctx(NEONATE, 3.0), request(PARA_SYRUP, 45, "mg", 4, 1, Timing.UnitsOfTime.D, "O"));
        assertTrue(c.get(0).getSummary().contains("maximum of 30 mg/kg/day (90 mg at 3 kg)"));
        assertTrue(cards(ctx(CHILD, 3.0), request(PARA_SYRUP, 45, "mg", 4, 1, Timing.UnitsOfTime.D, "O")).isEmpty());
    }

    @Test
    void perKgSingleDoseAndTeaspoons() {
        // 10 kg: 1 teaspoon = 5 ml = 120 mg <= 150 mg; 2 teaspoons = 240 mg > 15 mg/kg (150 mg)
        assertTrue(cards(ctx(CHILD, 10.0), request(PARA_SYRUP, 1, "Teaspoon", 4, 1, Timing.UnitsOfTime.D, "O")).isEmpty());
        List<CDSCard> c = cards(ctx(CHILD, 10.0), request(PARA_SYRUP, 2, "Teaspoon", 2, 1, Timing.UnitsOfTime.D, "O"));
        assertTrue(c.get(0).getSummary().contains("single dose 240 mg exceeds the BNF for Children maximum of 15 mg/kg (150 mg at 10 kg)"));
    }

    @Test
    void maintenanceFluidsAndBolus() {
        assertEquals(new BigDecimal("1500"), NmhsDoseLimitService.hollidaySegar(BigDecimal.valueOf(20)).stripTrailingZeros().setScale(0));
        // 20 kg: maintenance 1500 ml/day, limit 150% = 2250; 100 ml every hour = 2400 > 2250
        List<CDSCard> c = cards(ctx(CHILD, 20.0), request(SALINE, 100, "ml", 1, 1, Timing.UnitsOfTime.H, "P"));
        assertTrue(c.get(0).getSummary().contains("exceeds 150% of maintenance fluids (2250 ml/day at 20 kg; WHO Pocket Book)"));
        // bolus 500 ml once > 20 ml/kg (400 ml)
        List<CDSCard> b = cards(ctx(CHILD, 20.0), request(SALINE, 500, "Milliliter", 0, 0, null, "P"));
        assertTrue(b.get(0).getSummary().contains("bolus 500 ml exceeds the WHO Pocket Book maximum of 20 ml/kg (400 ml at 20 kg)"));
        assertTrue(cards(ctx(CHILD, 20.0), request(SALINE, 60, "ml", 1, 1, Timing.UnitsOfTime.H, "P")).isEmpty());
    }

    @Test
    void frequencyLimit() {
        List<CDSCard> c = adult(request(ATROPINE_DROPS, 1, "Drop", 4, 1, Timing.UnitsOfTime.D, null));
        assertEquals("Atropine 1% drops: 4 times/day exceeds the BNF maximum of 3 times/day.", c.get(0).getSummary());
        assertTrue(adult(request(ATROPINE_DROPS, 1, "Drop", 3, 1, Timing.UnitsOfTime.D, null)).isEmpty());
        // frequency-only rows apply whatever dose unit is used
        assertEquals(1, adult(request(ATROPINE_DROPS, 2, "Unit", 6, 1, Timing.UnitsOfTime.D, null)).size());
    }

    @Test
    void hourlyIntervalIsExactAtTheBoundary() {
        assertTrue(adult(request(METHYLDOPA, 1000, "mg", 1, 8, Timing.UnitsOfTime.H, "O")).isEmpty()); // exactly 3000
    }

    @Test
    void weeklyRegimenIsNotTreatedAsDaily() {
        assertTrue(adult(request(METHOTREXATE, 10, "Tablets", 1, 1, Timing.UnitsOfTime.WK, "O")).isEmpty());
        assertEquals(1, adult(request(METHOTREXATE, 12, "Tablet", 1, 1, Timing.UnitsOfTime.WK, "O")).size());
        assertEquals(1, adult(request(METHOTREXATE, 2, "Tablet", 1, 1, Timing.UnitsOfTime.D, "O")).size()); // 5 mg daily > 4
    }

    @Test
    void routeSpecificRowIsPreferred() {
        assertEquals(1, adult(request(ROUTED, 1000, "mg", 3, 1, Timing.UnitsOfTime.D, "O")).size());
        assertTrue(adult(request(ROUTED, 1000, "mg", 3, 1, Timing.UnitsOfTime.D, "P")).isEmpty());
        assertTrue(service.check(List.of(request(ROUTED, 1000, "mg", 3, 1, Timing.UnitsOfTime.D, "R")), ctx(ADULT, null)).covered().isEmpty());
    }

    @Test
    void unknownUnitIsNotCovered() {
        MedicationRequest mr = request(METHYLDOPA, 4, "NA", 4, 1, Timing.UnitsOfTime.D, "O");
        assertTrue(service.check(List.of(mr), ctx(ADULT, null)).covered().isEmpty());
    }

    @Test
    void existingOrderCodingIsNotChecked() {
        MedicationRequest mr = request("x", 4, "Tablet", 4, 1, Timing.UnitsOfTime.D, "O");
        mr.getMedicationCodeableConcept().getCoding().clear();
        mr.getMedicationCodeableConcept().addCoding(new Coding(NmhsDoseLimitService.OPENMRS_SYSTEM, METHYLDOPA, "drug"));
        assertTrue(service.check(List.of(mr), ctx(ADULT, null)).covered().isEmpty());
    }

    @Test
    void draftRowsOnlyWhenEnabledAndLabelled() throws ServiceException {
        assertTrue(adult(request(DRAFT_DRUG, 2, "Tablet", 3, 1, Timing.UnitsOfTime.D, "O")).isEmpty());
        service.load(tsv.toString(), true, 0);
        List<CDSCard> c = adult(request(DRAFT_DRUG, 2, "Tablet", 3, 1, Timing.UnitsOfTime.D, "O"));
        assertTrue(c.get(0).getSummary().endsWith("(draft limit)."));
    }

    @Test
    void optionalNearMaximumInfo() throws ServiceException {
        service.load(tsv.toString(), false, 0.9);
        List<CDSCard> c = adult(request(METHYLDOPA, 1000, "mg", 3, 1, Timing.UnitsOfTime.D, "O"));
        assertEquals(CDSIndicator.info, c.get(0).getIndicator());
    }

    @Test
    void contextFromPrefetchAppliesWeightRecency() {
        IParser parser = FhirContext.forR4().newJsonParser();
        LocalDate today = LocalDate.of(2026, 10, 24);
        Patient child = new Patient();
        child.setBirthDate(Date.from(LocalDate.of(2021, 10, 24).atStartOfDay(ZoneId.systemDefault()).toInstant()));
        Observation weight = new Observation();
        weight.setValue(new Quantity().setValue(18.5).setUnit("kg").setCode("kg"));
        weight.setEffective(new DateTimeType(Date.from(LocalDate.of(2026, 10, 10).atStartOfDay(ZoneId.systemDefault()).toInstant())));
        PatientContext c = service.contextFrom(parser.encodeResourceToString(child), parser.encodeResourceToString(weight), parser, today);
        assertEquals(1826, c.ageDays());
        assertEquals(0, c.weightKg().compareTo(BigDecimal.valueOf(18.5)));
        // a child weight older than 30 days is ignored
        weight.setEffective(new DateTimeType(Date.from(LocalDate.of(2026, 8, 1).atStartOfDay(ZoneId.systemDefault()).toInstant())));
        assertNull(service.contextFrom(parser.encodeResourceToString(child), parser.encodeResourceToString(weight), parser, today).weightKg());
        // missing prefetch values
        PatientContext none = service.contextFrom(null, "null", parser, today);
        assertNull(none.ageDays());
        assertNull(none.weightKg());
    }
}
