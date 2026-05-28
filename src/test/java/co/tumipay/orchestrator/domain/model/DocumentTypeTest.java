package co.tumipay.orchestrator.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DocumentType Enum Tests")
class DocumentTypeTest {

    @Test
    @DisplayName("fromCode returns matching type for known code")
    void fromCode_returnsMatch_forKnownCode() {
        assertThat(DocumentType.fromCode("CC")).contains(DocumentType.CC);
        assertThat(DocumentType.fromCode("CE")).contains(DocumentType.CE);
        assertThat(DocumentType.fromCode("NIT")).contains(DocumentType.NIT);
        assertThat(DocumentType.fromCode("PP")).contains(DocumentType.PP);
        assertThat(DocumentType.fromCode("TI")).contains(DocumentType.TI);
        assertThat(DocumentType.fromCode("RUT")).contains(DocumentType.RUT);
        assertThat(DocumentType.fromCode("DNI")).contains(DocumentType.DNI);
        assertThat(DocumentType.fromCode("NUIP")).contains(DocumentType.NUIP);
    }

    @Test
    @DisplayName("fromCode is case-insensitive")
    void fromCode_isCaseInsensitive() {
        assertThat(DocumentType.fromCode("cc")).contains(DocumentType.CC);
        assertThat(DocumentType.fromCode("Nit")).contains(DocumentType.NIT);
        assertThat(DocumentType.fromCode("PP")).contains(DocumentType.PP);
    }

    @Test
    @DisplayName("fromCode returns empty Optional for unknown code")
    void fromCode_returnsEmpty_forUnknown() {
        assertThat(DocumentType.fromCode("UNKNOWN")).isEmpty();
        assertThat(DocumentType.fromCode("XYZ")).isEmpty();
    }

    @Test
    @DisplayName("fromCode returns empty Optional for null input")
    void fromCode_returnsEmpty_forNull() {
        assertThat(DocumentType.fromCode(null)).isEmpty();
    }

    @Test
    @DisplayName("getDescription returns the full description for each type")
    void getDescription_returnsCorrectDescription() {
        assertThat(DocumentType.CC.getDescription()).isEqualTo("Cédula de Ciudadanía");
        assertThat(DocumentType.CE.getDescription()).isEqualTo("Cédula de Extranjería");
        assertThat(DocumentType.NIT.getDescription()).isEqualTo("Número de Identificación Tributaria");
        assertThat(DocumentType.PP.getDescription()).isEqualTo("Pasaporte");
        assertThat(DocumentType.TI.getDescription()).isEqualTo("Tarjeta de Identidad");
        assertThat(DocumentType.RUT.getDescription()).isEqualTo("Registro Único Tributario");
        assertThat(DocumentType.DNI.getDescription()).isEqualTo("Documento Nacional de Identidad");
        assertThat(DocumentType.NUIP.getDescription()).isEqualTo("Número Único de Identificación Personal");
    }

    @Test
    @DisplayName("values() returns all 8 document types")
    void values_returnsAllTypes() {
        assertThat(DocumentType.values()).hasSize(8);
    }
}
