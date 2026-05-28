package co.tumipay.orchestrator.domain.model;

import java.util.Arrays;
import java.util.Optional;

public enum DocumentType {
    CC("Cédula de Ciudadanía"),
    CE("Cédula de Extranjería"),
    NIT("Número de Identificación Tributaria"),
    PP("Pasaporte"),
    TI("Tarjeta de Identidad"),
    RUT("Registro Único Tributario"),
    DNI("Documento Nacional de Identidad"),
    NUIP("Número Único de Identificación Personal");

    private final String description;

    DocumentType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public static Optional<DocumentType> fromCode(String code) {
        return Arrays.stream(values())
                .filter(dt -> dt.name().equalsIgnoreCase(code))
                .findFirst();
    }
}
