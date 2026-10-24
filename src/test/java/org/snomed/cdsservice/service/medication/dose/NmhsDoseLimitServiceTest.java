package org.snomed.cdsservice.service.medication.dose;

import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Dosage;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Timing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snomed.cdsservice.model.CDSCard;
import org.snomed.cdsservice.model.CDSIndicator;
import org.snomed.cdsservice.service.ServiceException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NmhsDoseLimitServiceTest {

    static final String METHYLDOPA = "11111111-1111-1111-1111-111111111111";
    static final String METRO_SUSP = "22222222-2222-2222-2222-222222222222";
    static final String METHOTREXATE = "33333333-3333-3333-3333-333333333333";
    static final String DRAFT_DRUG = "44444444-4444-4444-4444-444444444444";
    static final String ROUTED = "55555555-5555-5555-5555-555555555555";

    @TempDir
    Path dir;
    NmhsDoseLimitService service;
    Path tsv;

    @BeforeEach
    void setUp() throws Exception {
        tsv = dir.resolve("limits.tsv");
        Files.writeString(tsv, String.join("\n",
                NmhsDoseLimitService.EXPECTED_HEADER,
                row(METHYLDOPA, "Methyldopa 250 mg", "ANY", "mg", "1000", "3000", "250", "mg", "1", "Tablet", "approved"),
                row(METRO_SUSP, "Metronidazole 200 mg/5 ml", "ANY", "mg", "", "4000", "200", "mg", "5", "ml", "approved"),
                row(METHOTREXATE, "Methotrexate 2.5 mg", "ANY", "mg", "25", "", "2.5", "mg", "1", "Tablet", "approved"),
                row(DRAFT_DRUG, "Draft drug 10 mg", "ANY", "mg", "", "40", "10", "mg", "1", "Tablet", "draft"),
                row(ROUTED, "Routed drug 1 g", "O", "mg", "", "2000", "", "", "", "", "approved"),
                row(ROUTED, "Routed drug 1 g", "P", "mg", "", "6000", "", "", "", "", "approved"),
                "# comment line ignored",
                row("bad-row", "Bad", "ANY", "mg", "abc", "1", "", "", "", "", "approved")) + "\n");
        service = new NmhsDoseLimitService();
        service.load(tsv.toString(), false, 0.9);
    }

    static String row(String uuid, String name, String route, String unit, String single, String daily, String sv,
                      String su, String pv, String pu, String status) {
        return String.join("\t", uuid, name, route, unit, single, daily, sv, su, pv, pu, "adult", status, "test source", "");
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

    private List<CDSCard> cards(MedicationRequest... requests) {
        return service.check(List.of(requests)).cards();
    }

    @Test
    void loadsApprovedRowsSkipsDraftsAndBadRows() {
        assertEquals(4, service.activeDrugCount());
    }

    @Test
    void rejectsUnexpectedHeader() throws Exception {
        Path bad = dir.resolve("bad.tsv");
        Files.writeString(bad, "wrong\theader\n");
        assertThrows(ServiceException.class, () -> new NmhsDoseLimitService().load(bad.toString(), false, 0.9));
    }

    @Test
    void disabledWhenNoPath() throws ServiceException {
        NmhsDoseLimitService s = new NmhsDoseLimitService();
        s.load("", false, 0.9);
        assertTrue(s.check(List.of(request(METHYLDOPA, 10, "Tablet", 4, 1, Timing.UnitsOfTime.D, "O"))).cards().isEmpty());
    }

    @Test
    void normalDoseGivesNoCardButIsCovered() {
        MedicationRequest mr = request(METHYLDOPA, 2, "Tablet", 3, 1, Timing.UnitsOfTime.D, "O"); // 1500 mg/day
        NmhsDoseLimitService.Result r = service.check(List.of(mr));
        assertTrue(r.cards().isEmpty());
        assertTrue(r.covered().contains(mr));
    }

    @Test
    void dailyMaximumExceededInTablets() {
        List<CDSCard> c = cards(request(METHYLDOPA, 4, "Tablet", 4, 1, Timing.UnitsOfTime.D, "O")); // 1000 mg x4 = 4000
        assertEquals(1, c.size());
        assertEquals(CDSIndicator.warning, c.get(0).getIndicator());
        assertEquals("High Dosage", c.get(0).getAlertType());
        assertEquals("Methyldopa 250 mg: 4000 mg/day exceeds the NMHS maximum daily dose of 3000 mg.", c.get(0).getSummary());
        assertTrue(c.get(0).getSummary().length() < 140);
    }

    @Test
    void singleDoseExceededInGrams() {
        List<CDSCard> c = cards(request(METHYLDOPA, 1.5, "g", 1, 1, Timing.UnitsOfTime.D, "O"));
        assertEquals(1, c.size());
        assertTrue(c.get(0).getSummary().contains("single dose 1500 mg exceeds the NMHS maximum single dose of 1000 mg"));
    }

    @Test
    void nearMaximumGivesInfo() {
        List<CDSCard> c = cards(request(METHYLDOPA, 1000, "Milligram", 3, 1, Timing.UnitsOfTime.D, "O")); // exactly 3000
        assertEquals(1, c.size());
        assertEquals(CDSIndicator.info, c.get(0).getIndicator());
        assertTrue(c.get(0).getSummary().contains("is 100% of the NMHS maximum daily dose"));
    }

    @Test
    void nearMaximumInfoIsDisabledWhenThresholdIsZero() throws ServiceException {
        service.load(tsv.toString(), false, 0);
        assertTrue(cards(request(METHYLDOPA, 1000, "mg", 3, 1, Timing.UnitsOfTime.D, "O")).isEmpty()); // exactly 3000
        assertEquals(1, cards(request(METHYLDOPA, 1000, "mg", 4, 1, Timing.UnitsOfTime.D, "O")).size()); // 4000 still warns
    }

    @Test
    void hourlyIntervalIsExactAtTheBoundary() {
        // 1000 mg every 8 hours = 3000 mg/day: info (not warning)
        List<CDSCard> c = cards(request(METHYLDOPA, 1000, "mg", 1, 8, Timing.UnitsOfTime.H, "O"));
        assertEquals(CDSIndicator.info, c.get(0).getIndicator());
    }

    @Test
    void liquidDosesConvertThroughConcentrationIncludingTeaspoons() {
        assertTrue(cards(request(METRO_SUSP, 20, "mL", 4, 1, Timing.UnitsOfTime.D, "O")).isEmpty()); // 3200 mg = 80%
        List<CDSCard> c = cards(request(METRO_SUSP, 30, "ml", 4, 1, Timing.UnitsOfTime.D, "O"));      // 4800 mg
        assertEquals(CDSIndicator.warning, c.get(0).getIndicator());
        List<CDSCard> t = cards(request(METRO_SUSP, 6, "Teaspoon", 4, 1, Timing.UnitsOfTime.D, "O")); // 30 ml x4
        assertEquals(CDSIndicator.warning, t.get(0).getIndicator());
    }

    @Test
    void weeklyRegimenIsNotTreatedAsDaily() {
        // 10 tablets (25 mg) once a week: single dose at the maximum, no card
        assertTrue(cards(request(METHOTREXATE, 10, "Tablets", 1, 1, Timing.UnitsOfTime.WK, "O")).isEmpty());
        BigDecimal perDay = NmhsDoseLimitService.administrationsPerDay(
                request(METHOTREXATE, 1, "Tablet", 1, 1, Timing.UnitsOfTime.WK, "O").getDosageInstruction().get(0));
        assertEquals(0, perDay.compareTo(BigDecimal.ONE.divide(BigDecimal.valueOf(7), java.math.MathContext.DECIMAL64)));
        // 12 tablets (30 mg) once a week: single dose exceeded
        assertEquals(1, cards(request(METHOTREXATE, 12, "Tablet", 1, 1, Timing.UnitsOfTime.WK, "O")).size());
    }

    @Test
    void routeSpecificRowIsPreferred() {
        assertEquals(1, cards(request(ROUTED, 1000, "mg", 3, 1, Timing.UnitsOfTime.D, "O")).size()); // 3000 > 2000 oral
        assertTrue(cards(request(ROUTED, 1000, "mg", 3, 1, Timing.UnitsOfTime.D, "P")).isEmpty());   // 3000 < 6000 parenteral
        assertTrue(service.check(List.of(request(ROUTED, 1000, "mg", 3, 1, Timing.UnitsOfTime.D, "R"))).covered().isEmpty());
    }

    @Test
    void unknownUnitIsNotCovered() {
        MedicationRequest mr = request(METHYLDOPA, 4, "NA", 4, 1, Timing.UnitsOfTime.D, "O");
        NmhsDoseLimitService.Result r = service.check(List.of(mr));
        assertTrue(r.cards().isEmpty());
        assertFalse(r.covered().contains(mr));
    }

    @Test
    void existingOrderCodingIsNotChecked() {
        MedicationRequest mr = request("x", 4, "Tablet", 4, 1, Timing.UnitsOfTime.D, "O");
        mr.getMedicationCodeableConcept().getCoding().clear();
        mr.getMedicationCodeableConcept().addCoding(new Coding(NmhsDoseLimitService.OPENMRS_SYSTEM, METHYLDOPA, "drug"));
        assertTrue(service.check(List.of(mr)).covered().isEmpty());
    }

    @Test
    void draftRowsOnlyWhenEnabledAndLabelled() throws ServiceException {
        assertTrue(cards(request(DRAFT_DRUG, 2, "Tablet", 3, 1, Timing.UnitsOfTime.D, "O")).isEmpty());
        service.load(tsv.toString(), true, 0.9);
        List<CDSCard> c = cards(request(DRAFT_DRUG, 2, "Tablet", 3, 1, Timing.UnitsOfTime.D, "O")); // 60 > 40
        assertEquals(1, c.size());
        assertTrue(c.get(0).getSummary().endsWith("(draft limit)."));
    }

    @Test
    void noTimingMeansSingleAdministration() {
        assertTrue(cards(request(METHYLDOPA, 3, "Tablet", 0, 0, null, "O")).isEmpty()); // 750 mg single, 750/day
    }
}
