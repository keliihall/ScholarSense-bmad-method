package cn.edu.suda.scholarsense.subjectregistry.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class IdentifierNormalizerTest {

    @ParameterizedTest
    @CsvSource({
        "' ００ab１２ ', '00AB12', UPPERCASE_PRESERVE_LEADING_ZERO_V1",
        "' ００Ab１２ ', '00ab12', LOWERCASE_PRESERVE_LEADING_ZERO_V1"
    })
    void appliesNfkcUnicodeTrimCaseAndPreservesLeadingZeros(
            String input, String expected, NormalizationProfile profile) {
        assertEquals(expected, IdentifierNormalizer.normalize(input, profile));
    }

    @Test
    void rejectsControlCharactersAndDoesNotGuessAcrossProfiles() {
        assertThrows(SubjectRegistryApplicationException.class, () ->
                IdentifierNormalizer.normalize(
                        "student\n001", NormalizationProfile.UPPERCASE_PRESERVE_LEADING_ZERO_V1));
        assertEquals(
                "User001",
                IdentifierNormalizer.normalize(
                        " User001 ", NormalizationProfile.EXACT_PRESERVE_CASE_V1));
    }
}
