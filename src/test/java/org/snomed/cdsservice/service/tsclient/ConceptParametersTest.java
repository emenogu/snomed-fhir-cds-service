package org.snomed.cdsservice.service.tsclient;

import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConceptParametersTest {

    @Test
    void shouldParseNormalFormWhenAttributeSectionBeginsWithGroupedAttributes() {
        ConceptParameters parameters = new ConceptParameters();

        var property = parameters.addParameter()
                .setName("property");

        property.addPart()
                .setName("code")
                .setValue(new StringType("normalFormTerse"));

        property.addPart()
                .setName("valueString")
                .setValue(new StringType(
                        "763158003:{411116001=385055001}"
                ));

        SnomedConceptNormalForm normalForm =
                assertDoesNotThrow(parameters::getNormalForm);

        assertEquals(
                Set.of("763158003"),
                normalForm.getParentCodes()
        );

        assertEquals(
                1,
                normalForm.getAttributeGroups().size()
        );

        assertEquals(
                "385055001",
                normalForm.getAttributeGroups()
                        .get(0)
                        .get("411116001")
        );
    }
}
