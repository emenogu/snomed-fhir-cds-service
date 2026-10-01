package org.snomed.cdsservice.model;

import org.hl7.fhir.r4.model.Coding;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MedicationInterationCDSTriggerTest {

    private static final String SNOMED = "http://snomed.info/sct";

    @Test
    void usesActualMedicationDisplaysWhenPresent() {
        MedicationInterationCDSTrigger trigger = createTrigger();

        CDSCard card = trigger.createRelevantCard(
                Set.of(new Coding(SNOMED, "96119002", "Albendazole 400 mg")),
                Set.of(new Coding(SNOMED, "33664007", "Acetazolamide 250 mg")));

        assertEquals(
                "Contraindication of drug-drug interaction: "
                        + "\"Acetazolamide 250 mg\" with \"Albendazole 400 mg\".",
                card.getSummary());
    }

    @Test
    void fallsBackToRuleMedicationLabelWhenDisplayIsNull() {
        MedicationInterationCDSTrigger trigger = createTrigger();

        CDSCard card = trigger.createRelevantCard(
                Set.of(new Coding(SNOMED, "96119002", null)),
                Set.of(new Coding(SNOMED, "33664007", "Acetazolamide 250 mg")));

        assertEquals(
                "Contraindication of drug-drug interaction: "
                        + "\"Acetazolamide 250 mg\" with \"Albendazole\".",
                card.getSummary());

        assertFalse(card.getSummary().contains("null"));
    }

    @Test
    void fallsBackToRuleMedicationLabelWhenDisplayIsBlank() {
        MedicationInterationCDSTrigger trigger = createTrigger();

        CDSCard card = trigger.createRelevantCard(
                Set.of(new Coding(SNOMED, "96119002", "   ")),
                Set.of(new Coding(SNOMED, "33664007", "Acetazolamide 250 mg")));

        assertEquals(
                "Contraindication of drug-drug interaction: "
                        + "\"Acetazolamide 250 mg\" with \"Albendazole\".",
                card.getSummary());

        assertFalse(card.getSummary().contains("null"));
    }

    private MedicationInterationCDSTrigger createTrigger() {
        Collection<Coding> medication1Codings =
                List.of(new Coding(SNOMED, "33664007", null));

        Collection<Coding> medication2Codings =
                List.of(new Coding(SNOMED, "96119002", null));

        CDSCard card = new CDSCard(
                "7db8e2f9-0baa-477e-8682-24bdffbd3fda",
                "Contraindication of drug-drug interaction: "
                        + "{{ActualMedication1}} with {{ActualMedication2}}.",
                "The use of {{RuleMedication1}} is contraindicated with "
                        + "{{RuleMedication2}}.",
                CDSIndicator.warning,
                new CDSSource("Drug Bank"),
                new ArrayList<>(),
                new ArrayList<>(),
                "Contraindication");

        return new MedicationInterationCDSTrigger(
                "Acetazolamide",
                medication1Codings,
                "Albendazole",
                medication2Codings,
                card);
    }
}
